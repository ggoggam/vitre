package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.BridgeReady
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
