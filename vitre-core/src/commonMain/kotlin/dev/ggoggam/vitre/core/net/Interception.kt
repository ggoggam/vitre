package dev.ggoggam.vitre.core.net

import kotlinx.coroutines.flow.SharedFlow

/**
 * A resource request the WebView is about to make, offered to the host before it happens.
 *
 * [headers] are the headers the WebView would have sent, keyed case-insensitively by
 * [headerValue]. The request **body is deliberately absent**: the platform hook this is built on
 * (`WebViewClient.shouldInterceptRequest`) does not expose one, so a non-idempotent request cannot
 * be replayed faithfully and is passed through untouched rather than corrupted.
 */
data class InterceptedRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val isForMainFrame: Boolean,
) {
    /** Case-insensitive header lookup, because neither the platform nor the wire agrees on case. */
    fun headerValue(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val host: String? get() = hostOf(url)

    /**
     * Whether this is a frame or page navigation rather than a subresource.
     *
     * `Accept` is the only signal the platform hook offers — `Sec-Fetch-Dest` would say it outright
     * and is added further down the network stack, after this callback.
     */
    fun looksLikeDocument(): Boolean = headerValue("Accept")?.contains("text/html", ignoreCase = true) == true
}

/** A response to hand back to the WebView in place of one it would have fetched. */
data class InterceptedResponse(
    val status: Int = 200,
    val reason: String = "OK",
    val contentType: String = "text/html",
    val charset: String? = "utf-8",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    // Generated equals/hashCode compare ByteArray by identity, which makes two responses with the
    // same bytes unequal and is never what a caller means. Compared by content instead.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is InterceptedResponse &&
                    status == other.status &&
                    reason == other.reason &&
                    contentType == other.contentType &&
                    charset == other.charset &&
                    headers == other.headers &&
                    body.contentEquals(other.body)
            )

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + reason.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (charset?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * Answers a request without going to the network. Fixtures, mocks and blocking are all this.
 *
 * Returning `null` declines, and the next handler — or, if none match, the network — gets it.
 *
 * **Handlers run on a platform callback thread and block the resource load, so keep them quick.**
 * Quick is a stronger requirement on the desktop than it sounds: CEF calls the interceptor on one
 * IO thread for the whole browser process, so a handler that blocks does not slow one lane down, it
 * stops every lane in the pool from starting a request. Answer from memory; anything that needs a
 * network round trip or a disk read belongs behind the interceptor's own fetch, not in here.
 */
fun interface RequestHandler {
    fun handle(request: InterceptedRequest): InterceptedResponse?
}

/**
 * What the interceptor is allowed to do.
 *
 * **Inert by default.** A default-constructed policy answers from its [handlers] and does nothing
 * else: no refetching, no rewriting, nothing on the tap. Every real site a lane loads under it is
 * loaded by the browser's own network stack, byte for byte the document the browser would have got.
 *
 * That default is a deliberate reversal, and the reason is that the old one was wrong in the field.
 * Interception used to cover documents and data out of the box, which meant this library refetched
 * a page through `HttpURLConnection` before any caller had asked it to — HTTP/1.1, no shared cache,
 * redirects followed by hand, a cookie jar bridged across, and none of the browser's TLS or HTTP/2
 * shape. Bot detection reads that as a non-browser and answers with a challenge, which renders in a
 * lane as "Webpage not available" and is indistinguishable from a lane that failed to load. Making
 * every caller pay that — including one that only wanted a WebView on screen — to enable a feature
 * they may never touch is the wrong way round.
 *
 * [AUTOMATION] is the opt-in for the other case: a pool driving sites the app deliberately
 * automates, where CORS relaxation and the tap are worth what the refetch costs.
 *
 * [permissiveCors] is the load-bearing switch, and it hands a page reads that the site's own CORS
 * policy was written to refuse — see `docs/PARALLEL-LANES.md`. Off by default for exactly that
 * reason: it belongs on sites the app is deliberately automating, never on a WebView showing
 * content someone else chose, and a default is read by both.
 */
data class InterceptionPolicy(
    /**
     * Reflect the request's `Origin` into `Access-Control-Allow-Origin` (with credentials), answer
     * preflights permissively, and widen the page's CSP `connect-src`, so script inside a lane can
     * `fetch` across origins.
     *
     * The CSP half is not an extra: CORS is the *server's* opinion about who may read a response,
     * `connect-src` is the *page's* opinion about where it may ask at all, and relaxing only the
     * first leaves the fetch blocked with an error that names neither.
     *
     * It also couples this to [intercept] in a way worth knowing before tuning either: the CSP
     * arrives on the *document*, so widening it requires the document to have been intercepted. A
     * policy that relaxes CORS while leaving the main frame to the browser still loses to
     * `connect-src 'self'`, with correct CORS headers on a request the page was never allowed to
     * make.
     */
    val permissiveCors: Boolean = false,
    /** Capture textual response bodies onto the [NetworkTap]. Headers are always captured. */
    val captureBodies: Boolean = true,
    /** Bodies larger than this are reported truncated rather than held in memory. */
    val maxCapturedBodyBytes: Int = DEFAULT_MAX_CAPTURED_BODY_BYTES,
    /**
     * Which requests to take over. Everything else goes to the platform untouched and unreported —
     * and by default that is all of them. `{ false }` still leaves [handlers] working, because a
     * handler is consulted *before* this predicate: answering from memory is a different question
     * from taking a request off the network, and a fixture pays none of the costs a refetch does.
     *
     * When it is on, this is a throughput control rather than a preference. Interception is
     * synchronous and blocks the resource it is handling, and a real site is mostly images, fonts,
     * CSS and script — none of which need a header rewritten, and all of which compete for the same
     * small pool of WebView worker threads as the document and the API calls that do. Intercepting
     * the lot made `developer.mozilla.org` take longer than thirty seconds to reach
     * `DOMContentLoaded` in a lane, which the workflow correctly reported as a navigation timeout
     * against a page that was visibly on screen.
     *
     * [isDocumentOrData] is the predicate to reach for, and what [AUTOMATION] uses: it keeps
     * documents and data, which is exactly what CORS relaxation and the tap are for. `{ true }`
     * sees everything.
     *
     * Main-frame requests arrive here like any other, tagged [InterceptedRequest.isForMainFrame].
     * That is how a policy says "rewrite my XHR, but let the browser fetch my pages":
     *
     * ```
     * intercept = { !it.isForMainFrame && isDocumentOrData(it) }
     * ```
     *
     * which buys back the browser's own document load at the cost named on [permissiveCors] — no
     * CSP widening, since there is no intercepted document to widen it on.
     */
    val intercept: (InterceptedRequest) -> Boolean = { false },
    /** Consulted in order before anything hits the network, and before [intercept] is asked. */
    val handlers: List<RequestHandler> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_CAPTURED_BODY_BYTES: Int = 256 * 1024

        /**
         * Interception doing its whole job: documents and data taken off the network, CORS and CSP
         * relaxed, bodies captured. For a pool driving sites the app deliberately automates — and
         * the thing to reach for when a workflow needs a header rewritten or the tap to see a
         * document.
         *
         * The cost applies to every real site loaded under it, and it is the cost the default
         * exists to avoid: an intercepted document is one this library refetched through
         * `HttpURLConnection`, not one the browser's network stack fetched. Redirects are followed
         * by hand (so the document's `location` is the URL that was *requested* rather than the one
         * that answered), the cookie jar is bridged across, and a `POST` navigation is passed
         * through untouched because the platform hook exposes no request body. A site that
         * fingerprints its clients will notice.
         */
        val AUTOMATION: InterceptionPolicy =
            InterceptionPolicy(
                permissiveCors = true,
                intercept = ::isDocumentOrData,
            )

        /**
         * See what a page fetches before deciding what to do about it: documents and data reach
         * the [NetworkTap], and no response header is rewritten.
         *
         * Not free, and not a read-only view of the browser's traffic — an exchange only reaches
         * the tap because this library fetched it, so everything [AUTOMATION] says about a
         * refetched document applies here too. It changes nothing about the *response*; it changes
         * who fetched it.
         */
        val OBSERVE_ONLY: InterceptionPolicy = InterceptionPolicy(intercept = ::isDocumentOrData)
    }
}

/**
 * The default [InterceptionPolicy.intercept]: documents and data yes, static assets no.
 *
 * Decided on the file extension and the `Accept` header, in that order, because those are the only
 * two things the platform hook reliably offers. `Sec-Fetch-Dest` would say this outright and is not
 * present in `WebResourceRequest.requestHeaders` — the network stack adds it further down.
 */
fun isDocumentOrData(request: InterceptedRequest): Boolean {
    // Checked before the extension, not after: a document is exactly what a handler answers and
    // what the tap wants, and a site is free to serve one from a URL that ends in anything at all.
    if (request.looksLikeDocument()) return true
    val path =
        request.url
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
    if (STATIC_ASSET_SUFFIXES.any { path.endsWith(it) }) return false
    val accept = request.headerValue("Accept")?.lowercase().orEmpty()
    return STATIC_ASSET_ACCEPTS.none { accept.startsWith(it) }
}

private val STATIC_ASSET_SUFFIXES =
    listOf(
        ".avif",
        ".css",
        ".eot",
        ".gif",
        ".ico",
        ".jpeg",
        ".jpg",
        ".js",
        ".m4a",
        ".mjs",
        ".mp3",
        ".mp4",
        ".otf",
        ".png",
        ".svg",
        ".ttf",
        ".webm",
        ".webp",
        ".woff",
        ".woff2",
    )

private val STATIC_ASSET_ACCEPTS = listOf("image/", "font/", "text/css", "audio/", "video/")

/** Where a [NetworkExchange] came from — what the app did, not what the server said. */
enum class ExchangeOutcome {
    /** Fetched over the network by the interceptor. */
    Fetched,

    /** Answered by a [RequestHandler] without a network round trip. */
    Handled,

    /** Left to the platform: wrong method, wrong scheme, or the policy declined it. */
    PassedThrough,

    /** The interceptor tried and failed; [NetworkExchange.error] says why. */
    Failed,
}

/**
 * One request/response pair the interceptor saw.
 *
 * [body] is the *response* body, decoded as text, and it is the reason this type is worth having:
 * a shop that renders its results from `GET /api/search` hands over typed JSON here, where the DOM
 * would offer `$1,299.00` split across three spans.
 */
data class NetworkExchange(
    val id: Long,
    val method: String,
    val url: String,
    val outcome: ExchangeOutcome,
    val status: Int,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val contentType: String?,
    val body: String?,
    val bodyTruncated: Boolean,
    val durationMs: Long,
    val error: String? = null,
) {
    val host: String? get() = hostOf(url)
}

/**
 * A non-consuming view of everything the interceptor saw.
 *
 * Non-consuming for the same reason `WebViewBridge.messages` is: a debug pane must not be able to
 * steal an exchange from whatever is actually extracting data out of it.
 */
interface NetworkTap {
    val exchanges: SharedFlow<NetworkExchange>
}

/** `https://shop.example/a/b?q=1` → `shop.example`. Null if [url] has no recognisable authority. */
internal fun hostOf(url: String): String? {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return null
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.substringAfterLast('@').substringBefore(':').ifEmpty { null }
}

/** `https://shop.example:8443/a` → `https://shop.example:8443`. Null if there is no authority. */
internal fun originOf(url: String): String? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty()) return null
    val authority =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    return if (authority.isEmpty()) null else "$scheme://$authority"
}

/**
 * The first handler that claims [request], or null if none do.
 *
 * A handler that throws is treated as one that declined. It is running inside somebody's page load
 * on a platform callback, and letting it take the load down with it would turn a bug in a fixture
 * into a lane that never renders.
 */
internal fun InterceptionPolicy.firstHandled(request: InterceptedRequest): InterceptedResponse? {
    for (handler in handlers) {
        runCatching { handler.handle(request) }.getOrNull()?.let { return it }
    }
    return null
}
