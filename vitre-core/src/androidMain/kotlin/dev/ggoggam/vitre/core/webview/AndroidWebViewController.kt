package dev.ggoggam.vitre.core.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.ggoggam.vitre.core.bridge.BridgeReady
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.net.AndroidNetworkInterceptor
import kotlin.coroutines.resume

/**
 * Wraps an Android [WebView]. The caller is responsible for the WebView's lifecycle —
 * this class only forwards navigation/script/message I/O, installs the `window.vitre`
 * message listener via `WebViewCompat`, and owns the [WebViewClient].
 *
 * Thread confinement and the ordering of operations both belong to [WebViewSerializer]; nothing
 * here posts to the WebView by hand.
 *
 * [navigationTimeoutMs] bounds how long [navigate] waits for `onPageFinished`.
 *
 * [interceptor], when given, is consulted for what the page fetches — and, by default, for the
 * page's own document too. It is optional and absent by default because it is the piece that
 * answers for someone else's origin: it relaxes the site's CORS protections and refetches through
 * `HttpURLConnection` rather than the browser's network stack. See `docs/PARALLEL-LANES.md`. A
 * WebView that is merely showing a page has no business doing either.
 */
@SuppressLint("RequiresFeature")
class AndroidWebViewController(
    private val webView: WebView,
    private val navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
    private val interceptor: AndroidNetworkInterceptor? = null,
    /**
     * How long one [evaluateJs] may take. Worth raising for a lane driving a heavy third-party
     * site, where a locator can be waiting behind the page's own script for the main thread.
     */
    private val scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
) : WebViewController {
    private val inbox = WebViewInbox()
    private val serializer = WebViewSerializer()
    private val scriptResults = ScriptResults()

    /**
     * The handle for the document-start script installed below, kept because it is the only way to
     * take that script back off the WebView: [close] calls `.remove()` on it. Null when the
     * platform WebView is too old to support the feature.
     */
    private var documentStartScript: ScriptHandler? = null

    private var closed = false

    override val bridge: WebViewBridge =
        DefaultWebViewBridge(
            inbox = inbox,
            evaluateJs = ::evaluateJs,
        )

    // Process-wide on this platform, so the same jar whichever controller it is reached through.
    override val cookies: CookieStore = AndroidCookieStore

    init {
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "androidx.webkit WebMessageListener required (Android System WebView too old)"
        }
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf("*"),
        ) { _: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
            // The settle plane gets first refusal: a settled-promise report never reaches the
            // inbox, and only a main-frame report can be credited. See ScriptResults.deliver.
            // What it declines reaches the inbox tagged, so an iframe's message can be observed
            // without being able to answer a workflow's await.
            message.data?.let { raw ->
                if (!scriptResults.deliver(raw, isMainFrame)) {
                    inbox.deliver(
                        raw,
                        fromMainFrame = isMainFrame,
                        sourceOrigin = sourceOrigin.toString().takeUnless { it == OPAQUE_ORIGIN },
                    )
                }
            }
        }
        // The bridge object is in place before any page script runs — that is what makes
        // `if (window.vitre)` the authoritative check — and this announces it to anything that
        // would rather listen than ask. See BridgeReady.
        //
        // Silently skipped where the platform WebView does not support document-start scripts:
        // nothing is lost that a page can depend on, because the synchronous existence check holds
        // on every WebView, and on this platform the event is only ever the belt-and-braces half of
        // the contract. It is load-bearing on the desktop, where the check is the racy half; see
        // BridgeReady before reusing this reasoning anywhere but here.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartScript = WebViewCompat.addDocumentStartJavaScript(webView, BridgeReady.announceScript, setOf("*"))
        }
        // The client is the only source of page-load callbacks, so the controller claims it rather
        // than leaving it to the host: a host-installed client would silently break navigate().
        webView.webViewClient = PageLoadWebViewClient(serializer, inbox, scriptResults, interceptor)
    }

    override suspend fun navigate(url: String) {
        checkOpen()
        serializer.navigate(navigationTimeoutMs) {
            webView.loadUrl(url)
        }
    }

    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) {
        checkOpen()
        serializer.navigate(navigationTimeoutMs) {
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        }
    }

    // The settle logic — and the reason the wait happens outside the serializer's lock — lives in
    // ScriptResults.evaluate, shared with iOS. This platform's contribution is one raw evaluate.
    override suspend fun evaluateJs(script: String): String {
        checkOpen()
        return scriptResults.evaluate(script, scriptTimeoutMs) { wrapped ->
            serializer.evaluate(scriptTimeoutMs) { cont ->
                webView.evaluateJavascript(wrapped) { result -> cont.resume(result ?: "null") }
            }
        }
    }

    override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T {
        checkOpen()
        return serializer.exclusively(block)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Both removals are safe to reach here unguarded. WEB_MESSAGE_LISTENER is required in
        // `init`, so a controller that exists at all is on a WebView that supports it; and the
        // document-start handle is null precisely when that feature was missing.
        WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
        documentStartScript?.remove()
        documentStartScript = null
        // The WebViewClient stays: replacing it would mean inventing a policy for a WebView we do
        // not own, and the owner is about to destroy it anyway. Nor is `destroy()` called here —
        // see WebViewController.close.
    }

    /** Rejects work aimed at a WebView this controller has already taken its bridge back off. */
    private fun checkOpen() = check(!closed) { "controller is closed" }

    private class PageLoadWebViewClient(
        private val serializer: WebViewSerializer,
        private val inbox: WebViewInbox,
        private val scriptResults: ScriptResults,
        private val interceptor: AndroidNetworkInterceptor?,
    ) : WebViewClient() {
        /**
         * Called on a WebView background thread, once per resource, blocking that resource's load.
         *
         * Returning null — which is what happens with no interceptor, and for anything the policy
         * declines — leaves the request entirely to the platform.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = interceptor?.intercept(request)

        /**
         * Refuses navigations to schemes this WebView cannot render a document from.
         *
         * A page that wants to hand off to a native app navigates the *main frame* to
         * `intent://…;package=com.google.android.apps.maps;end` (or `market://`, or a vendor's own
         * scheme). A browser turns that into an `Intent`; a bare WebView has no such rule, so it
         * tries to fetch the URL, fails with `ERR_UNKNOWN_URL_SCHEME`, and — because that is a
         * main-frame failure — [onReceivedError] below fails the navigation and takes the workflow
         * with it. The page that was loading is replaced by an error page, so retrying lands
         * nowhere either.
         *
         * Returning true leaves the current document in place, which is the outcome a workflow
         * wants: the handoff was the page's idea, not the caller's, and the automation is here to
         * drive the web page rather than to leave for an app. Google Maps is the case that found
         * this — it reads the `wv` token in an Android WebView's user agent and redirects
         * unconditionally, whether or not the app is installed — but nothing about the rule is
         * specific to it.
         *
         * Deciding by what the WebView can *render*, rather than by blocklisting the schemes seen
         * so far, is the part worth keeping: an app scheme this list has never heard of is refused
         * for the same reason `intent` is. `about`, `data`, `blob` and `file` are here because the
         * library itself navigates to them — `about:blank` is where a hosted WebView starts, and
         * `loadHtml` gives a document a `data:` or custom base URL to run relative URLs against.
         *
         * Not called for the app's own `loadUrl`/`loadDataWithBaseURL` calls, so this sits on
         * page-initiated navigation only and no step can be refused by it.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = request.url.scheme?.lowercase() !in RENDERABLE_SCHEMES

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            // A new document means the old page's unread messages can never be answered and would
            // only mislead the next step that goes looking for one — and its promises can never
            // settle, so armed waits fail now rather than sitting out their timeout.
            inbox.clear()
            scriptResults.clear()
            serializer.started()
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) = serializer.finished()

        /**
         * Sub-resource failures (an image, an XHR) land here too but do not stop the document from
         * loading, so only a main-frame failure ends the navigation. The error page that follows
         * still fires `onPageFinished`, which is harmless: the latch has already resumed.
         */
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            serializer.failed("${error.description} (${error.errorCode}) loading ${request.url}")
        }
    }

    private companion object {
        const val BRIDGE_NAME = "vitre"

        /**
         * The schemes a WebView can produce a document from, and so the ones
         * [PageLoadWebViewClient.shouldOverrideUrlLoading] lets a page navigate itself to.
         */
        val RENDERABLE_SCHEMES = setOf("http", "https", "about", "data", "blob", "file")

        /**
         * What the platform hands us for a frame with an opaque origin — a sandboxed iframe, a
         * `data:` document. It arrives as the four-character string, not as a null Uri, so it has
         * to be spelled out here or every such frame would be reported as an origin literally
         * named "null".
         */
        const val OPAQUE_ORIGIN = "null"
    }
}
