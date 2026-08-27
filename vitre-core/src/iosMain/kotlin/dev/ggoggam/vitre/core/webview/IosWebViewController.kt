package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.BridgeReady
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewDispatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val BRIDGE_NAME = "vitre"

private val BRIDGE_INSTALL_JS =
    """
    (function () {
        if (window.vitre) return;
        window.vitre = {
            postMessage: function (m) {
                window.webkit.messageHandlers.$BRIDGE_NAME.postMessage(m);
            }
        };
        // Announced here, after the assignment and behind the idempotence guard above, so a
        // re-injection into a context that already has the bridge returns without re-firing ready.
        // See BridgeReady: the event is belt-and-braces, and `if (window.vitre)` is the check
        // that actually holds — it is true from this line onwards, before any page script runs.
        window.dispatchEvent(new Event('${BridgeReady.EVENT_NAME}'));
    })();
    """.trimIndent()

/**
 * Makes WebKit encode the script's value the way Android's `evaluateJavascript` already does.
 *
 * The two platforms disagree about what a script returns, and the disagreement is silent. Android
 * hands back JSON. WebKit hands back a Foundation object and the only thing to do with it is
 * `description`, which prints a JS `true` as **`"1"`**, `undefined` as `"<null>"`, and a string
 * without quotes or escaping. So `WaitFor`'s `found == "true"` check could never once have matched
 * on iOS — the step polled until it timed out, on every workflow, including the one `docs/PLAN.md`
 * nominates as the iOS smoke test.
 *
 * Normalising here rather than teaching the engine both dialects is deliberate: the engine should
 * not know which platform it is on, and every future step that inspects a return value would
 * otherwise have to remember this.
 *
 * `JSON.stringify` runs in the page, so the encoding is JavaScript's own rather than a
 * reconstruction from Foundation types — `NSNumber` cannot tell us whether `1` was a boolean or a
 * number, which is the root of the bug. `?? null` maps `undefined` onto Android's `"null"`.
 *
 * [script] must therefore be an *expression*. Everything the engine generates is one, and a caller
 * with statements to run wraps them in an IIFE, which is the convention the samples already follow.
 */
private fun asJsonExpression(script: String): String = "JSON.stringify((function(){return ($script);})() ?? null)"

/**
 * Copies an [NSData]'s bytes into a Kotlin [ByteArray].
 *
 * One `memcpy` rather than a per-byte read: an encoded screenshot is hundreds of kilobytes, and
 * crossing the interop boundary once per byte is the difference between microseconds and tens of
 * milliseconds. The copy itself is not avoidable — the caller gets a `ByteArray` it owns, and the
 * `NSData` is WebKit's to release.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/**
 * Wraps a [WKWebView]. Construct with a freshly configured WKWebView (the controller installs a
 * `WKUserScript` + script-message-handler for `window.vitre`, and its own navigation
 * delegate).
 *
 * Every call below reaches WebKit through [WebViewSerializer], which is what puts them on the main
 * thread. That is not a nicety on this platform: `WKWebView` is UIKit, and touching it from a
 * background dispatcher is undefined behaviour that usually presents as a hang or a corrupted
 * result rather than an exception, so it survives testing and fails in the field.
 *
 * [navigationTimeoutMs] bounds how long [navigate] waits for `didFinishNavigation`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosWebViewController(
    private val webView: WKWebView,
    private val navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
    /**
     * How long one [evaluateJs] may take. Worth raising for a lane driving a heavy third-party
     * site, where a locator can be waiting behind the page's own script for the main thread.
     */
    private val scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
) : WebViewController {
    private val inbox = WebViewInbox()
    private val serializer = WebViewSerializer()
    private val scriptResults = ScriptResults()

    // WKWebView.navigationDelegate is a weak reference. Held only by the init block below, this
    // object would be deallocated the moment init returned and no page-load callback would ever
    // arrive, so the controller keeps it alive for as long as it is itself alive.
    private val navigationDelegate = PageLoadNavigationDelegate(serializer, inbox, scriptResults)

    private var closed = false

    override val bridge: WebViewBridge =
        DefaultWebViewBridge(
            inbox = inbox,
            evaluateJs = ::evaluateJs,
        )

    // This WebView's own data store, not the default one: a host that configured an ephemeral
    // store did so to keep its session somewhere else, and reading the default would ignore that.
    override val cookies: CookieStore = IosCookieStore(webView.configuration.websiteDataStore.httpCookieStore)

    init {
        val controller: WKUserContentController =
            webView.configuration.userContentController
        controller.addUserScript(
            WKUserScript(
                source = BRIDGE_INSTALL_JS,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = false,
            ),
        )
        controller.addScriptMessageHandler(
            scriptMessageHandler = MessageHandler(inbox, scriptResults),
            name = BRIDGE_NAME,
        )
        webView.navigationDelegate = navigationDelegate
    }

    override suspend fun navigate(url: String) {
        checkOpen()
        // Reject a malformed URL up front rather than letting the caller wait out the timeout for
        // a load that was never started.
        val nsUrl = NSURL.URLWithString(url) ?: throw PageLoadException("Malformed URL: $url")
        serializer.navigate(navigationTimeoutMs) {
            webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
        }
    }

    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) {
        checkOpen()
        serializer.navigate(navigationTimeoutMs) {
            webView.loadHTMLString(html, baseURL = baseUrl?.let { NSURL.URLWithString(it) })
        }
    }

    // The settle logic lives in ScriptResults.evaluate, shared with Android. The wrapped script
    // still goes through asJsonExpression: the wrapper returns either the value or the pending
    // sentinel, and JSON.stringify over either produces byte-for-byte what Android's
    // evaluateJavascript encodes on its own — including the sentinel, since JSON-encoding a string
    // is exactly how the sentinel literal is built.
    override suspend fun evaluateJs(script: String): String {
        checkOpen()
        return scriptResults.evaluate(script, scriptTimeoutMs) { wrapped ->
            serializer.evaluate(scriptTimeoutMs) { cont ->
                webView.evaluateJavaScript(asJsonExpression(wrapped)) { result: Any?, error: NSError? ->
                    if (error != null) {
                        cont.resumeWithException(RuntimeException(error.localizedDescription))
                    } else {
                        cont.resume(result?.toString() ?: "null")
                    }
                }
            }
        }
    }

    /**
     * `takeSnapshotWithConfiguration:` — WebKit's own, and the only one worth using here.
     *
     * The alternatives are worse in ways that matter. Rendering the view's layer into a
     * `UIGraphicsImageRenderer` context misses everything WebKit composites out of process, which
     * on a modern WKWebView is most of the page. Asking the page to paint itself into a canvas
     * cannot reach cross-origin images. This call goes to the web process and comes back with what
     * was actually on screen.
     *
     * **`snapshotWidth` is in points; the image comes back at the screen's scale.** So the pixel
     * size a caller bounded has to be divided by that scale on the way in, or a 3× phone hands back
     * an image three times the size that was asked for. The size is reported back out of the
     * returned `UIImage` rather than from the request, so [PageScreenshot.width] is what the bytes
     * actually contain.
     *
     * `rect` is left unset (`CGRectNull`), which means the visible viewport — see
     * [WebViewController.screenshot] for why the full page is not offered here.
     *
     * **The encode runs off the main thread and outside the ordering lock**, because PNG-encoding a
     * megapixel is pure CPU that no longer touches the WebView, and `WKWebView` is UIKit — the one
     * thread it can be touched from is the one thread the UI needs.
     *
     * `afterScreenUpdates` is true so the picture includes whatever the step just before it did.
     * The known caveat is that WebKit does not answer this reliably for a view that is not in a
     * window, so the wait is bounded by `scriptTimeoutMs` and reported as a
     * [ScreenshotFailedException] rather than left to hang.
     */
    override suspend fun screenshot(options: ScreenshotOptions): PageScreenshot {
        checkOpen()
        val image =
            serializer.exclusively {
                withContext(WebViewDispatcher) { takeSnapshot(options) }
            }
        return withContext(Dispatchers.Default) { image.encode(options) }
    }

    /** Runs on the main thread, under the ordering lock. */
    private suspend fun takeSnapshot(options: ScreenshotOptions): UIImage {
        val screenScale = webView.window?.screen?.scale ?: UIScreen.mainScreen.scale
        val (pointWidth, pointHeight) = webView.bounds.useContents { size.width to size.height }
        val sourceWidth = (pointWidth * screenScale).roundToInt()
        val sourceHeight = (pointHeight * screenScale).roundToInt()
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw ScreenshotFailedException(
                "The WKWebView is ${pointWidth}x$pointHeight points — it has no bounds to snapshot yet",
            )
        }
        val target = options.fit(sourceWidth, sourceHeight)
        val configuration =
            WKSnapshotConfiguration().apply {
                afterScreenUpdates = true
                snapshotWidth = NSNumber(double = target.width / screenScale)
            }
        return try {
            withTimeout(scriptTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    webView.takeSnapshotWithConfiguration(configuration) { image: UIImage?, error: NSError? ->
                        when {
                            error != null -> {
                                cont.resumeWithException(ScreenshotFailedException(error.localizedDescription))
                            }

                            image == null -> {
                                cont.resumeWithException(ScreenshotFailedException("WebKit returned no image and no error"))
                            }

                            else -> {
                                cont.resume(image)
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // A cancellation escaping here would be indistinguishable, one frame up, from the
            // caller having been cancelled — the same reasoning as ScriptTimeoutException.
            throw ScreenshotFailedException("WebKit did not return a snapshot within ${scriptTimeoutMs}ms")
        }
    }

    /** Runs anywhere. `UIImage` is safe to read off the main thread, and encoding is all this does. */
    private fun UIImage.encode(options: ScreenshotOptions): PageScreenshot {
        val data =
            when (options.format) {
                ScreenshotFormat.Png -> UIImagePNGRepresentation(this)

                // UIKit takes 0..1 where the rest of this API takes 1..100, so it is the one place
                // the quality number is translated rather than passed through.
                ScreenshotFormat.Jpeg -> UIImageJPEGRepresentation(this, options.quality / 100.0)
            } ?: throw ScreenshotFailedException("UIKit could not encode the snapshot as ${options.format}")
        val (pointWidth, pointHeight) = size.useContents { width to height }
        return PageScreenshot(
            bytes = data.toByteArray(),
            format = options.format,
            width = (pointWidth * scale).roundToInt(),
            height = (pointHeight * scale).roundToInt(),
        )
    }

    override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T {
        checkOpen()
        return serializer.exclusively(block)
    }

    override fun close() {
        if (closed) return
        closed = true
        // This is the one that has to happen. A WKUserContentController retains its message
        // handlers strongly, and the handler here holds the inbox, which holds everything the
        // controller has buffered — so skipping this leaks the controller for as long as the
        // WKWebView's configuration is alive, whatever the host does with its own references.
        webView.configuration.userContentController.removeScriptMessageHandlerForName(BRIDGE_NAME)
        // The delegate is weak, so this is not about lifetime: it stops in-flight page callbacks
        // from reaching a serializer nobody will read the result of.
        webView.navigationDelegate = null
        // The user scripts stay. `removeAllUserScripts` is the only removal WebKit offers — there
        // is no way to take back just the one added in `init` — and a host that added its own
        // scripts to this configuration would lose them silently. An orphaned bridge stub whose
        // message handler is gone is inert, which is the cheaper of the two wrongs.
    }

    /** Rejects work aimed at a WebView this controller has already taken its bridge back off. */
    private fun checkOpen() = check(!closed) { "controller is closed" }

    /**
     * The callbacks below collapse onto the same Kotlin signatures — only the selector's argument
     * name separates `webView:didStartProvisionalNavigation:` from `webView:didFinishNavigation:`
     * — so each needs [ObjCSignatureOverride] to be allowed to collide.
     */
    private class PageLoadNavigationDelegate(
        private val serializer: WebViewSerializer,
        private val inbox: WebViewInbox,
        private val scriptResults: ScriptResults,
    ) : NSObject(),
        WKNavigationDelegateProtocol {
        /**
         * Set when [decidePolicyForNavigationAction] has just refused a scheme, and read once by
         * [didFailProvisionalNavigation] below.
         *
         * Cancelling a *redirect* — which is the case that matters, since a handoff is something
         * the page decides after it has already begun loading — reaches WebKit as a failed
         * provisional navigation, and reporting that to the serializer would fail the very
         * navigation this refusal exists to protect. Android has no equivalent flag because
         * `shouldOverrideUrlLoading` returning true produces no error callback at all; this is the
         * cost of expressing the same policy through WebKit's API.
         */
        private var refusedScheme = false

        /**
         * Refuses page-initiated navigations to schemes this WebView cannot render a document from
         * — the counterpart to Android's `shouldOverrideUrlLoading`, and for the same reasons. See
         * [RENDERABLE_SCHEMES].
         *
         * iOS reaches this by a different route than Android does. `WKWebView` has no `wv` token in
         * its user agent for a site to key on, so the app-handoff redirect fires far less often
         * here — but `comgooglemaps://`, `itms-apps://` and every vendor scheme still exist, and
         * without this a page that tries one takes the workflow down with it.
         */
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            if (isRenderableScheme(decidePolicyForNavigationAction.request.URL?.scheme)) {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            } else {
                refusedScheme = true
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didStartProvisionalNavigation: WKNavigation?,
        ) {
            // A new document means the old page's unread messages can never be answered and would
            // only mislead the next step that goes looking for one — and its promises can never
            // settle, so armed waits fail now rather than sitting out their timeout.
            inbox.clear()
            scriptResults.clear()
            serializer.started()
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFinishNavigation: WKNavigation?,
        ) = serializer.finished()

        /**
         * The document never began loading — bad host, no network, cancelled redirect.
         *
         * A cancellation this delegate caused is swallowed: refusing an `intent://`-style handoff
         * leaves the previous document in place and on screen, which is a success for the workflow
         * driving it, not a page-load failure. Only the flag set by
         * [decidePolicyForNavigationAction] is trusted for that — matching on the error code would
         * also swallow a genuine cancellation the host asked for.
         */
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailProvisionalNavigation: WKNavigation?,
            withError: NSError,
        ) {
            if (refusedScheme) {
                refusedScheme = false
                return
            }
            serializer.failed(withError.localizedDescription)
        }

        /** The document committed but then failed part-way through. */
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            didFailNavigation: WKNavigation?,
            withError: NSError,
        ) = serializer.failed(withError.localizedDescription)
    }

    private class MessageHandler(
        private val inbox: WebViewInbox,
        private val scriptResults: ScriptResults,
    ) : NSObject(),
        WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            val raw = didReceiveScriptMessage.body as? String ?: return
            val frameInfo = didReceiveScriptMessage.frameInfo
            val mainFrame = frameInfo.mainFrame
            // The settle plane gets first refusal: a settled-promise report never reaches the
            // inbox, and only a main-frame report can be credited. See ScriptResults.deliver.
            // What it declines reaches the inbox tagged, so an iframe's message can be observed
            // without being able to answer a workflow's await.
            if (!scriptResults.deliver(raw, fromMainFrame = mainFrame)) {
                inbox.deliver(
                    raw,
                    fromMainFrame = mainFrame,
                    sourceOrigin = originOf(frameInfo),
                )
            }
        }

        /**
         * `scheme://host[:port]`, rebuilt from the parts WebKit reports — there is no whole-origin
         * string on `WKSecurityOrigin`. An opaque origin (a sandboxed frame, a document loaded
         * from raw HTML with no base URL) comes back with an empty host, which is not an origin
         * and must not be reported as one. Port 0 means "the scheme's default", which belongs to
         * the serialised origin no more than `:443` belongs in an `https` URL.
         */
        private fun originOf(frameInfo: WKFrameInfo): String? {
            val origin = frameInfo.securityOrigin
            val host = origin.host
            if (host.isEmpty()) return null
            val port = origin.port
            return buildString {
                append(origin.protocol)
                append("://")
                append(host)
                if (port != 0L) append(":$port")
            }
        }
    }
}
