package dev.ggoggam.vitre.core.net

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.system.measureTimeMillis

/**
 * The half of the trick the origin model cannot object to: the app *is* the network stack.
 *
 * Sitting in `WebViewClient.shouldInterceptRequest`, this fetches the resource itself and hands
 * back a response of its own construction. That is what lets a `RequestHandler` answer for a whole
 * origin, and what makes a cross-origin `fetch` inside a lane succeed: the CORS headers the server
 * did not send are added on the way past, before any renderer sees the response.
 *
 * Framing headers are *not* touched. A lane loads its site as a top-level document, so
 * `X-Frame-Options` and `frame-ancestors` have nothing to say about it — stripping them was what
 * the iframe arrangement needed, and it is gone (`docs/PARALLEL-LANES.md`).
 *
 * The fetching, the tap and the body-capture policy are all shared with the desktop interceptor —
 * see [HttpResourceFetcher] and [ExchangeRecorder]. What is Android's alone is the three platform
 * types below: `WebResourceRequest` in, `WebResourceResponse` out, and the WebView's cookie jar.
 *
 * Called on a WebView background thread, once per resource, blocking that resource's load.
 */
class AndroidNetworkInterceptor(
    private val policy: InterceptionPolicy,
) : NetworkTap {
    private val recorder = ExchangeRecorder(policy)
    private val fetcher = HttpResourceFetcher(WebViewCookieJar)

    override val exchanges: SharedFlow<NetworkExchange> get() = recorder.exchanges

    /** Returns null to leave the request to the platform, which is the default for anything odd. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val method = request.method?.uppercase() ?: "GET"
        val headers = request.requestHeaders.orEmpty()
        val intercepted = InterceptedRequest(url = url, method = method, headers = headers, isForMainFrame = request.isForMainFrame)

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
            return preflightResponse(intercepted)
        }

        // The platform hook exposes a method and headers but no body, so a POST cannot be replayed
        // faithfully. Passing it through untouched is the only honest option: a POST replayed
        // without its body is not the same request, and silently sending one would be worse than
        // not intercepting.
        if (method != "GET" && method != "HEAD") {
            recorder.passthrough(intercepted, "no request body available for $method")
            return null
        }

        return fetch(intercepted)
    }

    private fun handle(request: InterceptedRequest): WebResourceResponse? {
        for (handler in policy.handlers) {
            val response = runCatching { handler.handle(request) }.getOrNull() ?: continue
            recorder.record(request, response, ExchangeOutcome.Handled, durationMs = 0)
            return response.toWebResourceResponse(request, policy)
        }
        return null
    }

    private fun fetch(request: InterceptedRequest): WebResourceResponse? {
        var response: InterceptedResponse? = null
        var failure: String? = null
        val elapsed =
            measureTimeMillis {
                try {
                    response = fetcher.fetch(request)
                } catch (e: IOException) {
                    failure = e.message ?: e::class.simpleName ?: "network error"
                } catch (e: RuntimeException) {
                    // A malformed URL or an unsupported protocol reaches here. Falling back to the
                    // platform is better than an error page: WebView may well handle what we could
                    // not, and a lane that renders is worth more than a tidy failure.
                    failure = e.message ?: e::class.simpleName ?: "interception error"
                }
            }
        val body = response
        if (body == null) {
            recorder.passthrough(request, failure ?: "interception declined", ExchangeOutcome.Failed)
            return null
        }
        recorder.record(request, body, ExchangeOutcome.Fetched, elapsed)
        return body.toWebResourceResponse(request, policy)
    }

    private fun preflightResponse(request: InterceptedRequest): WebResourceResponse {
        val response =
            InterceptedResponse(
                status = HTTP_NO_CONTENT,
                reason = "No Content",
                contentType = "text/plain",
                headers = HeaderRewriter.corsHeaders(request.headerValue("Origin")),
            )
        recorder.record(request, response, ExchangeOutcome.Handled, durationMs = 0)
        return response.toWebResourceResponse(request, policy)
    }

    private companion object {
        const val HTTP_NO_CONTENT = 204
    }
}

/** The WebView's own cookie jar, which is where the page's session actually lives. */
private object WebViewCookieJar : CookieJar {
    override fun cookieHeader(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    override fun store(
        url: String,
        setCookie: String,
    ) {
        runCatching { CookieManager.getInstance().setCookie(url, setCookie) }
    }
}

/**
 * Builds the object the platform wants, with the policy's rewrites applied.
 *
 * `WebResourceResponse` rejects an empty reason phrase outright — with an exception thrown from
 * inside the WebView, so it surfaces as a resource that mysteriously failed — hence the fallback.
 */
private fun InterceptedResponse.toWebResourceResponse(
    request: InterceptedRequest,
    policy: InterceptionPolicy,
): WebResourceResponse =
    WebResourceResponse(
        contentType.ifBlank { "application/octet-stream" },
        charset ?: "utf-8",
        status,
        reason.ifBlank { reasonFor(status) },
        HeaderRewriter.rewriteResponseHeaders(headers, request.headerValue("Origin"), policy),
        ByteArrayInputStream(body),
    )
