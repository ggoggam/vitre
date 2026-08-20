package dev.ggoggam.vitre.core.net

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.create
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * Everything a pool of lanes saw, on a platform with nothing to intercept with.
 *
 * Two very different sources feed this. [publishHandled] carries responses a [RequestHandler]
 * produced, which are complete — the application wrote them. [publishScripted] carries what the
 * page reported about its own `fetch` and `XHR` traffic, which is partial by construction; see
 * [ScriptedTap] for exactly how partial.
 *
 * Both are published from the main thread — a `WKURLSchemeHandler` callback and a
 * `WKScriptMessageHandler` callback both arrive there — which is what lets the id counter be a
 * plain `var`.
 */
internal class LaneNetworkTap(
    private val policy: InterceptionPolicy,
) : NetworkTap {
    private val published =
        MutableSharedFlow<NetworkExchange>(
            extraBufferCapacity = EXCHANGE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private var nextId = 0L

    override val exchanges: SharedFlow<NetworkExchange> get() = published.asSharedFlow()

    fun publishHandled(
        request: InterceptedRequest,
        response: InterceptedResponse,
        outcome: ExchangeOutcome = ExchangeOutcome.Handled,
        error: String? = null,
    ) {
        val body =
            response.body
                .takeIf { policy.captureBodies && it.isNotEmpty() }
                ?.decodeToString()
        published.tryEmit(
            NetworkExchange(
                id = ++nextId,
                method = request.method,
                url = request.url,
                outcome = outcome,
                status = response.status,
                requestHeaders = request.headers,
                responseHeaders = response.headers,
                contentType = response.contentType,
                body = body?.take(policy.maxCapturedBodyBytes),
                bodyTruncated = (body?.length ?: 0) > policy.maxCapturedBodyBytes,
                durationMs = 0,
                error = error,
            ),
        )
    }

    fun publishScripted(raw: String) {
        scriptExchange(raw, ++nextId, policy)?.let { published.tryEmit(it) }
    }

    private companion object {
        const val EXCHANGE_BUFFER = 256
    }
}

/**
 * Answers [FixtureScheme] URLs out of [InterceptionPolicy.handlers], and nothing else.
 *
 * The whole exchange is synchronous, which is not a shortcut but the thing that makes this safe:
 * calling back into a `WKURLSchemeTask` after WebKit has stopped it raises an Objective-C
 * exception, and an ObjC exception crossing back into Kotlin/Native terminates the process rather
 * than throwing. Finishing inside `startURLSchemeTask` leaves no window in which a stop can
 * interleave. It is affordable because a handler is memory-resident by definition — the moment
 * something here needs the network, this design is the wrong one.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FixtureSchemeHandler(
    private val policy: InterceptionPolicy,
    private val tap: LaneNetworkTap,
) : NSObject(),
    WKURLSchemeHandlerProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        startURLSchemeTask: WKURLSchemeTaskProtocol,
    ) {
        val request = startURLSchemeTask.request
        val schemeUrl = request.URL?.absoluteString
        if (schemeUrl == null) {
            startURLSchemeTask.didFinish()
            return
        }
        val headers =
            buildMap {
                request.allHTTPHeaderFields?.forEach { (name, value) -> put(name.toString(), value.toString()) }
            }
        val asked =
            InterceptedRequest(
                // Handlers match on the `https` URL the workflow asked for. That iOS moved it onto
                // a private scheme is this file's problem and must not become theirs.
                url = FixtureScheme.decode(schemeUrl),
                method = request.HTTPMethod?.uppercase() ?: "GET",
                headers = headers,
                isForMainFrame = false,
            )
        // An approximation, and the honest one available: WebKit does not tell a scheme handler
        // which frame asked. On iOS a lane's document *is* the main frame, so anything that looks
        // like a document is one.
        val intercepted = asked.copy(isForMainFrame = asked.looksLikeDocument())

        val handled = policy.firstHandled(intercepted)
        val response = handled ?: notFound(intercepted)
        tap.publishHandled(
            request = intercepted,
            response = response,
            outcome = if (handled != null) ExchangeOutcome.Handled else ExchangeOutcome.Failed,
            error = if (handled != null) null else "no RequestHandler claimed this URL",
        )

        val contentType =
            buildString {
                append(response.contentType)
                response.charset?.let { append("; charset=").append(it) }
            }
        val rewritten =
            HeaderRewriter.rewriteResponseHeaders(
                // Replaced case-insensitively rather than merged: a handler that wrote
                // `content-type` in lower case would otherwise leave two of them in the map, and
                // which one WebKit believes is not worth finding out.
                headers =
                    response.headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) } +
                        mapOf("Content-Type" to contentType),
                // `null` when the page did not send one, which is what `corsHeaders` turns into a
                // wildcard. WebKit sends no `Origin` for a same-origin fetch and, on some versions,
                // sends a null one from a custom-scheme document — reflecting a literal "null"
                // back would be a header no browser accepts.
                requestOrigin = intercepted.headerValue("Origin")?.takeIf { it.isNotBlank() && it != "null" },
                policy = policy,
            )
        // `Content-Length` is added back after the rewrite, which drops it — there it guards against
        // a length that no longer matches a rewritten body, and here we are the ones who know the
        // length. Without it WebKit is told the size is unknown, which is legal and makes some
        // resource loads wait for the connection to close.
        //
        // Widened to `Any?` keys rather than cast, because WebKit's binding wants `Map<Any?, *>`
        // and an unchecked cast would be a lie about a map this function just built.
        val headerFields =
            (rewritten + mapOf("Content-Length" to response.body.size.toString()))
                .mapKeys { (name, _) -> name as Any? }
        val nsResponse =
            NSHTTPURLResponse(
                uRL = request.URL ?: NSURL(string = schemeUrl),
                statusCode = response.status.convert(),
                HTTPVersion = "HTTP/1.1",
                headerFields = headerFields,
            )
        startURLSchemeTask.didReceiveResponse(nsResponse)
        startURLSchemeTask.didReceiveData(response.body.toNSData())
        startURLSchemeTask.didFinish()
    }

    /** Nothing to cancel: [startURLSchemeTask] has already finished by the time this can arrive. */
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        stopURLSchemeTask: WKURLSchemeTaskProtocol,
    ) = Unit

    /**
     * A 404 rather than a failed load, because the two look very different from a workflow.
     *
     * `didFailWithError` surfaces as a navigation failure, which for a missing subresource — a
     * favicon, a stylesheet a fixture never wrote — would abort a page that is otherwise perfectly
     * fine. A 404 body says the same thing to anyone reading the tap and renders.
     */
    private fun notFound(request: InterceptedRequest): InterceptedResponse =
        InterceptedResponse(
            status = 404,
            reason = "Not Found",
            contentType = "text/html",
            body = "<!doctype html><meta charset=\"utf-8\"><title>404</title><p>No handler for ${request.url}".encodeToByteArray(),
        )
}

/** Feeds [ScriptedTap] reports into [LaneNetworkTap], on a channel of their own. */
internal class ScriptedTapMessageHandler(
    private val tap: LaneNetworkTap,
) : NSObject(),
    WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        (didReceiveScriptMessage.body as? String)?.let(tap::publishScripted)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.convert()) }
    }
