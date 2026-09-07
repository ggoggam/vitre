package dev.ggoggam.vitre.core.net

import kotlinx.coroutines.flow.SharedFlow
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.CookiePolicy
import java.net.URI
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/**
 * The desktop half of "the app is the network stack", sitting in CEF's resource pipeline.
 *
 * `CefResourceRequestHandler.getResourceHandler` is the direct counterpart of Android's
 * `shouldInterceptRequest`: return a handler and it answers the request, return null and Chromium
 * loads it normally. So desktop gets the full arrangement — fixtures, the tap with response bodies,
 * and relaxed CORS — rather than iOS's reduced one, and the decision logic below is deliberately
 * the same shape as [AndroidNetworkInterceptor]'s so the two cannot quietly diverge.
 *
 * Header rewriting is why the response is refetched rather than merely observed. CEF has hooks that
 * *see* a response (`onResourceResponse`) but none that let an application change its headers on
 * the way past, so the only way to add the `Access-Control-Allow-Origin` a server declined to send
 * is to be the one who sends the whole response. [HttpResourceFetcher] does that, and is shared
 * with Android.
 *
 * ### The IO thread is one thread, for every lane
 *
 * `getResourceHandler` is called on CEF's IO thread, and CEF has exactly **one** of those per
 * browser process — not one per browser, the way Android hands each `WebView` its own background
 * thread for `shouldInterceptRequest`. Every lane in a pool is a browser in that one process, so
 * every lane's requests arrive on the same thread, one at a time.
 *
 * That makes a synchronous fetch here catastrophic rather than merely slow: it holds the thread for
 * a whole HTTP round trip, and no other lane can so much as *start* a request until it lets go.
 * Four lanes against sites that take a second and a half each took 6.3s — exactly four times one —
 * with the four interceptions starting 1500ms apart, while the same four with interception off took
 * 1.6s. The renderers were parallel the whole time; the network in front of them was a queue of one.
 *
 * So nothing on this thread may block. The decision to intercept is made here, because it is
 * pure predicate work, and the fetch is handed to [FetchedResourceHandler], which does it on a
 * worker and answers CEF later through the callback CEF supplies for exactly this.
 */
class CefNetworkInterceptor(
    private val policy: InterceptionPolicy,
) : NetworkTap {
    private val recorder = ExchangeRecorder(policy)
    private val fetcher = HttpResourceFetcher(JvmCookieJar)

    /**
     * Where an intercepted fetch actually happens, off the IO thread.
     *
     * Both threads and waiting requests are bounded. Admission never blocks CEF's IO thread:
     * overload returns a 503, and work that waited over twenty seconds returns a 504 when dequeued.
     * Cancelled waiting requests are removed immediately so new pages can use their queue slots.
     *
     * Daemon threads: a host that forgets [dispose] should still be able to exit.
     */
    private val fetches = FetchQueue(workers = FETCH_THREADS, capacity = FETCH_QUEUE_CAPACITY)

    override val exchanges: SharedFlow<NetworkExchange> get() = recorder.exchanges

    /** Install this on a `KCEFClient` with `addRequestHandler`. One client may hold one. */
    val requestHandler: CefRequestHandler =
        object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler = resourceRequestHandler
        }

    private val resourceRequestHandler =
        object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
            ): CefResourceHandler? = request?.let(::intercept)
        }

    /** Returns null to leave the request to Chromium, which is the default for anything odd. */
    private fun intercept(request: CefRequest): CefResourceHandler? {
        val url = request.url ?: return null
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null

        val isForMainFrame = request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME
        val method = request.method?.uppercase() ?: "GET"
        val headers = HashMap<String, String>().also { request.getHeaderMap(it) }
        val intercepted = InterceptedRequest(url = url, method = method, headers = headers, isForMainFrame = isForMainFrame)

        // Handlers first, and before the resource filter: a fixture may well be a stylesheet or an
        // image, and "the app answers this itself" is a different question from "is this worth
        // taking off the network".
        handle(intercepted)?.let { return it }

        if (!policy.intercept(intercepted)) {
            // Declining a stylesheet is routine and reporting it would drown the tap. Declining a
            // *document* is recorded, because it is the difference between a page the browser
            // fetched and a page this library refetched — which is the first thing worth knowing
            // when a lane renders something nobody expected, and which under the default policy is
            // every page. The tap is where that answer lives.
            if (intercepted.looksLikeDocument()) {
                recorder.passthrough(intercepted, "declined by InterceptionPolicy.intercept")
            }
            return null
        }

        // A preflight is a question about policy, not a resource, and the answer is entirely ours
        // to give. Forwarding it would ask the origin server to approve a request its own CORS
        // configuration exists to refuse.
        if (policy.permissiveCors && method == "OPTIONS") {
            return preflight(intercepted)
        }

        // CEF *does* expose a request body, unlike Android's hook — but replaying a non-idempotent
        // request through a second HTTP client is a decision this library has already made once,
        // and made the other way. Keeping the platforms identical here is worth more than the
        // capability: a workflow that works on one and silently double-posts on the other is a
        // worse outcome than a POST that is simply left alone on both.
        if (method != "GET" && method != "HEAD") {
            recorder.passthrough(intercepted, "request bodies are not replayed for $method")
            return null
        }

        return fetch(intercepted)
    }

    private fun handle(request: InterceptedRequest): CefResourceHandler? {
        for (handler in policy.handlers) {
            val response = runCatching { handler.handle(request) }.getOrNull() ?: continue
            recorder.record(request, response, ExchangeOutcome.Handled, durationMs = 0)
            return response.toResourceHandler(request, policy)
        }
        return null
    }

    /**
     * Commits to answering [request], and goes to the network for it on a worker thread.
     *
     * **Committing is the part that changed, and it is a real trade.** This used to fetch inline and
     * return null when the fetch threw, which left the request to Chromium — a nicety worth having
     * when it cost nothing. It cannot be kept here: CEF decides between "the application answers
     * this" and "the network stack does" the moment this method returns, and there is no way back
     * once a handler is in hand ([CefResourceHandler.processRequest] returning false cancels the
     * request rather than passing it on). Keeping the fallback means fetching before returning,
     * which means fetching on the IO thread, which is what serialised every lane in the pool.
     *
     * So a fetch that fails is now reported as a failure — [FetchedResourceHandler] serves a 502
     * naming the cause, and records it on the tap as [ExchangeOutcome.Failed] — rather than quietly
     * becoming Chromium's problem. For a transient error that is the better outcome: a document
     * Chromium loaded after we declined came back with none of the header rewriting the lane was
     * relying on, and appeared on the tap as nothing at all.
     *
     * **What it costs is the shape of one failure, and it is worth recognising.** An origin that
     * cannot be reached at all used to produce Chromium's own error page, which is a *failed* load,
     * so the workflow died at its `Navigate` step saying so. A 502 is a perfectly successful load of
     * a page that happens to explain itself, so the workflow now gets that far and dies at its first
     * `WaitFor` with a timeout on a selector. The reason is on screen in the lane and on the tap;
     * it is the step index that moved.
     */
    private fun fetch(request: InterceptedRequest): CefResourceHandler =
        FetchedResourceHandler(
            request = request,
            policy = policy,
            recorder = recorder,
            fetcher = fetcher,
            queue = fetches,
        )

    /**
     * Stops the fetch workers. A pool does this in `KcefWebViewPool.dispose`.
     *
     * In-flight fetches finish. Waiting requests and later submissions receive a 503, completing
     * their outstanding CEF callbacks without fetching pages that are being disposed.
     */
    fun dispose() {
        fetches.close()
    }

    private fun preflight(request: InterceptedRequest): CefResourceHandler {
        val response =
            InterceptedResponse(
                status = HTTP_NO_CONTENT,
                reason = "No Content",
                contentType = "text/plain",
                headers = HeaderRewriter.corsHeaders(request.headerValue("Origin")),
            )
        recorder.record(request, response, ExchangeOutcome.Handled, durationMs = 0)
        return response.toResourceHandler(request, policy)
    }

    private companion object {
        const val HTTP_NO_CONTENT = 204

        /**
         * Four lanes' worth of headroom. Chromium itself allows six connections per host and around
         * ten times that in total, so this is well inside what the far end expects.
         */
        const val FETCH_THREADS = 16
        const val FETCH_QUEUE_CAPACITY = 128
    }
}

/**
 * The cookie jar for requests this library fetches itself.
 *
 * Not CEF's own jar, and the reason is that CEF's is asynchronous: `visitUrlCookies` answers
 * through a visitor callback, and blocking a resource load on it — which is what a synchronous
 * `Cookie` header would require — risks waiting on the very thread that would deliver the answer.
 *
 * The cost is a genuine seam worth knowing about: a session established by an intercepted response
 * lives here, and one established by a request Chromium loaded itself lives in CEF's jar, so the
 * two halves of a login flow can end up on opposite sides. Under the default policy nothing is
 * intercepted and this jar stays empty, so the seam only exists for a caller who asked for
 * interception — and [InterceptionPolicy.AUTOMATION] keeps the document on this side of it, which
 * is the half a site's own login flow runs through.
 */
private object JvmCookieJar : CookieJar {
    private val jar = java.net.CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }

    override fun cookieHeader(url: String): String? =
        runCatching {
            jar.get(URI(url), emptyMap())["Cookie"]?.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }.getOrNull()

    override fun store(
        url: String,
        setCookie: String,
    ) {
        runCatching { jar.put(URI(url), mapOf("Set-Cookie" to listOf(setCookie))) }
    }
}

/** Serves bytes that are already in hand — a [RequestHandler]'s answer, or a preflight. */
private fun InterceptedResponse.toResourceHandler(
    request: InterceptedRequest,
    policy: InterceptionPolicy,
): CefResourceHandler = InterceptedResourceHandler(request, policy, initial = this)

/**
 * Hands an [InterceptedResponse] to CEF, with the policy's header rewrites applied.
 *
 * CEF drives this as a pull: [processRequest] says "yes, I have this", [getResponseHeaders]
 * describes it, and [readResponse] is called repeatedly for as much as fits in the buffer it is
 * given. Returning false from [readResponse] is how the response is ended — there is no separate
 * completion call, and a handler that keeps returning true with zero bytes read hangs the load.
 *
 * All four methods are called on the IO thread, one at a time for one request, which is what lets
 * [offset] be an ordinary field. [served] is not: [FetchedResourceHandler] writes it from a worker.
 */
private open class InterceptedResourceHandler(
    protected val request: InterceptedRequest,
    private val policy: InterceptionPolicy,
    initial: InterceptedResponse?,
) : CefResourceHandler {
    /** Null until there is something to serve; a subclass that fetches fills it in before it continues. */
    @Volatile
    protected var served: InterceptedResponse? = initial

    private var offset = 0

    override fun processRequest(
        request: CefRequest?,
        callback: CefCallback?,
    ): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse?,
        responseLength: IntRef?,
        redirectUrl: StringRef?,
    ) {
        val body = served ?: return
        if (response == null) return
        response.status = body.status
        response.statusText = body.reason.ifBlank { reasonFor(body.status) }
        val mimeType = body.contentType.ifBlank { "application/octet-stream" }
        response.mimeType = mimeType
        val charset = body.charset ?: "utf-8"
        val headers = HeaderRewriter.rewriteResponseHeaders(body.headers, request.headerValue("Origin"), policy)
        // The charset does not travel on `mimeType`, so it has to go on the header itself or the
        // renderer decodes a UTF-8 document as Latin-1 and every non-ASCII character is mojibake.
        response.setHeaderMap(HashMap(headers + ("Content-Type" to "$mimeType; charset=$charset")))
        responseLength?.set(body.body.size)
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
        callback: CefCallback?,
    ): Boolean {
        val body = served?.body
        if (dataOut == null || body == null || offset >= body.size) {
            bytesRead?.set(0)
            return false
        }
        val count = minOf(bytesToRead, dataOut.size, body.size - offset)
        System.arraycopy(body, offset, dataOut, 0, count)
        offset += count
        bytesRead?.set(count)
        return true
    }

    override fun cancel() = Unit
}

/**
 * The same handler, with the response fetched on a worker thread instead of supplied up front.
 *
 * This is the whole point of the class: returning true from [processRequest] *without* calling
 * `Continue()` is CEF's contract for "this request is mine, the answer is coming later", and it is
 * what frees the one IO thread every lane in the pool shares. See [CefNetworkInterceptor].
 */
private class FetchedResourceHandler(
    request: InterceptedRequest,
    policy: InterceptionPolicy,
    private val recorder: ExchangeRecorder,
    private val fetcher: HttpResourceFetcher,
    queue: FetchQueue,
) : InterceptedResourceHandler(request, policy, initial = null) {
    private var callback: CefCallback? = null
    private var startedAt = 0L
    private val task =
        queue.task(
            work = { fetcher.fetch(this.request) },
            complete = { result ->
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                val body = result.getOrNull()
                if (body != null) {
                    recorder.record(this.request, body, ExchangeOutcome.Fetched, elapsed)
                    served = body
                } else {
                    val failure = result.exceptionOrNull()
                    val reason = failure?.message ?: "interception failed"
                    val status =
                        when (failure) {
                            is RejectedExecutionException -> 503
                            is TimeoutException -> 504
                            else -> 502
                        }
                    val error = gatewayError(status, reason)
                    recorder.record(this.request, error, ExchangeOutcome.Failed, elapsed, reason)
                    served = error
                }
                // Task completion and cancellation are serialized; cancellation cannot return
                // while this callback is still being invoked on a worker.
                try {
                    callback?.Continue()
                } catch (_: RuntimeException) {
                    // CEF may already have destroyed the browser. One invalid native callback
                    // must not prevent dispose() from answering the other waiting requests.
                } finally {
                    callback = null
                }
            },
        )

    override fun processRequest(
        request: CefRequest?,
        callback: CefCallback?,
    ): Boolean {
        this.callback = callback
        startedAt = System.nanoTime()
        task.start()
        return true
    }

    override fun cancel() {
        task.cancel()
        callback = null
    }

    /**
     * What a lane shows when the interceptor could not fetch the document at all.
     *
     * A real response rather than a cancelled request: cancelling surfaces as `ERR_ABORTED`, which
     * `CefWebViewController` deliberately ignores — a page navigating away from itself reports the
     * same code — so the lane would sit on its previous document with nothing anywhere saying why.
     */
    private fun gatewayError(
        status: Int,
        reason: String,
    ): InterceptedResponse =
        InterceptedResponse(
            status = status,
            reason =
                when (status) {
                    503 -> "Service Unavailable"
                    504 -> "Gateway Timeout"
                    else -> "Bad Gateway"
                },
            contentType = "text/plain",
            charset = "utf-8",
            body = "vitre could not fetch ${request.url}: $reason".toByteArray(),
        )
}
