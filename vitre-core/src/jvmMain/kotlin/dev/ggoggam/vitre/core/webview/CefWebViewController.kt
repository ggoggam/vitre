package dev.ggoggam.vitre.core.webview

import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

/**
 * Wraps a Kotlin CEF Browser, which it creates itself — see [create], and the ordering rule that
 * makes that non-negotiable.
 *
 * The controller forwards navigation/script/message I/O, installs `window.vitre` through a
 * [CefBridgeChannel], and owns the client's load handler. Thread confinement and the ordering of
 * operations both belong to [WebViewSerializer]; nothing here posts to the browser by hand.
 *
 * The browser renders **offscreen**, into [surface], rather than into an AWT component — see
 * [CefSurface] for the two things that go wrong when a lane is a heavyweight component inside
 * Compose, and why neither is a trade worth making.
 *
 * Disposing [browser] stays the owner's job, as on the other platforms — [close] gives the client
 * its handlers back and nothing more. `KcefWebViewPool` does it for a pool; a host that called
 * [create] itself does it after the component has left the window.
 *
 * ### One client per controller
 *
 * `KCEFClient` takes exactly one load handler and one request handler — `removeLoadHandler()` takes
 * no argument, because there is nothing to choose between. So a client cannot carry two controllers
 * without them silently overwriting each other's page-load callbacks, which presents as a lane
 * whose `navigate` never returns.
 *
 * [navigationTimeoutMs] bounds how long [navigate] waits for `onLoadEnd`.
 *
 * [scriptTimeoutMs] bounds one [evaluateJs]. Worth raising for a lane driving a heavy third-party
 * site, where a locator can be waiting behind the page's own script for the main thread.
 */
class CefWebViewController private constructor(
    /** The browser this controller drives. */
    val browser: KCEFBrowser,
    /**
     * Where this lane's pixels come out. Draw it; a lane whose surface nobody draws still loads and
     * is still drivable, it is simply invisible.
     */
    val surface: CefSurface,
    private val channel: CefBridgeChannel,
    private val navigationTimeoutMs: Long,
    private val scriptTimeoutMs: Long,
) : WebViewController {
    private val inbox = WebViewInbox()
    private val serializer = WebViewSerializer()
    private val scriptResults = ScriptResults()

    private val loadHandler = PageLoadHandler()

    /**
     * Completed once the browser has gone idle for the first time.
     *
     * Creating a CEF browser starts a load of its own — `about:blank`, before any caller has asked
     * for anything — and `WebViewSerializer` correlates page-load callbacks positionally, so that
     * stray load's `started`/`finished` pair can satisfy the *caller's* first awaited navigation
     * instead of the load they actually asked for. It shows up as `loadHtml` returning against a
     * blank document, intermittently, and then the real page arriving a moment later.
     *
     * So [create] waits for this before handing the controller over. `onLoadingStateChange` is what
     * makes the wait deterministic rather than a sleep: CEF reports the browser idle exactly once
     * the create-time load is done.
     */
    private val initiallyIdle = CompletableDeferred<Unit>()

    private var closed = false

    override val bridge: WebViewBridge =
        DefaultWebViewBridge(
            inbox = inbox,
            evaluateJs = ::evaluateJs,
        )

    init {
        channel.router.addHandler(
            channel.handler(inbox, scriptResults, ownedBy = { browser.identifier }),
            // Not the first handler: KCEFClient installs a router of its own for the
            // `evaluateJavaScript` this controller deliberately does not use, and displacing it
            // would break any caller that does.
            false,
        )
        // The router itself was registered in [create], before the browser existed. Only the
        // handler is added here, which has no such ordering constraint.
        //
        // The load handler is the only source of page-load callbacks, so the controller claims it
        // rather than leaving it to the host — see the note on one client per controller above.
        browser.client.addLoadHandler(loadHandler)
    }

    override suspend fun navigate(url: String) {
        checkOpen()
        serializer.navigate(navigationTimeoutMs) {
            browser.loadURL(url)
        }
    }

    /**
     * Loads [html] through KCEF's virtual-resource mechanism.
     *
     * **[baseUrl] does not become the document's origin here**, which is the one place this
     * platform's `loadHtml` is weaker than the other two. KCEF serves the markup from a generated
     * `file://` URL and uses the URL it is given only as the key, so relative URLs resolve against
     * that generated path and the document's origin is opaque. Android honours `baseUrl` properly
     * and iOS honours it as far as WebKit allows.
     *
     * It costs nothing for what `loadHtml` is mostly for — a page the workflow itself controls,
     * `FramePool`'s lane placeholders, a fixture rendered inline — and it does mean a workflow
     * that loads markup at a base URL and then expects a *relative* fetch to reach that origin
     * should navigate to a [dev.ggoggam.vitre.core.net.RequestHandler]-served URL instead.
     */
    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) {
        checkOpen()
        serializer.navigate(navigationTimeoutMs) {
            browser.loadHtml(html, baseUrl ?: KCEFBrowser.BLANK_URI)
        }
    }

    /**
     * The settle logic — and the reason the wait happens outside the serializer's lock — lives in
     * [ScriptResults.evaluate], shared with the other two platforms. This platform's contribution
     * is one raw evaluate, and it is the awkward one: CEF's `executeJavaScript` returns nothing and
     * calls nobody back, so the value comes home over [CefBridgeChannel] rather than off the call.
     */
    override suspend fun evaluateJs(script: String): String {
        checkOpen()
        return scriptResults.evaluate(script, scriptTimeoutMs) { wrapped ->
            serializer.evaluate(scriptTimeoutMs) { cont ->
                browser.executeJavaScript(channel.submit(wrapped, cont), browser.url.orEmpty(), 0)
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
        browser.client.removeLoadHandler()
        browser.client.removeMessageRouter(channel.router)
        channel.dispose()
        // The browser is deliberately left alive, even though [create] is what made it — see
        // WebViewController.close. Disposing it while its AWT component is still in a hierarchy is
        // the same mistake as calling WebView.destroy() before detach, and only the owner knows
        // when it has come out. `KcefWebViewPool.dispose` does it for a pool.
    }

    /** Rejects work aimed at a browser this controller has already taken its bridge back off. */
    private fun checkOpen() = check(!closed) { "controller is closed" }

    companion object {
        /**
         * Registers a bridge channel on [client], then creates the browser it will drive.
         *
         * **That order is the whole reason this is a factory.** A `CefMessageRouter`'s JavaScript
         * query function is injected by the render process, and the render process is told which
         * functions to inject when a browser is created — so a router added to a client that
         * already has a browser never reaches that browser's pages. The failure is quiet and
         * thoroughly misleading: navigation works, the page renders, and every `evaluateJs` times
         * out with nothing in the log but `window.vitreQuery1 is not a function` on the
         * console. Making the browser here is what stops a caller assembling the two in the order
         * that does not work.
         *
         * On the EDT because browser creation still goes through AWT machinery even offscreen,
         * and `createImmediately` because a browser that waits to be realised has nowhere to put a
         * `loadURL` in the meantime — a lane built lazily accepts its first navigation, never
         * performs it, and reports a timeout thirty seconds later. Offscreen is what makes forcing
         * creation safe here: there is no native window that would need a parent it does not have.
         */
        suspend fun create(
            client: KCEFClient,
            navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
            scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
        ): CefWebViewController {
            val channel = CefBridgeChannel()
            client.addMessageRouter(channel.router)
            val surface = CefSurface()
            val browser =
                withContext(Dispatchers.Swing) {
                    client.createBrowser(
                        url = KCEFBrowser.BLANK_URI,
                        rendering = CefRendering.CefRenderingWithHandler(surface.renderHandler, surface.eventSource),
                        // Transparent, so a lane with nothing loaded yet shows the host's own
                        // background rather than an opaque white rectangle punched into the UI.
                        isTransparent = true,
                    )
                }
            surface.attach(browser)
            // The controller — and so the load handler — before `createImmediately`, so the
            // create-time load is observed rather than missed. See `initiallyIdle`.
            val controller = CefWebViewController(browser, surface, channel, navigationTimeoutMs, scriptTimeoutMs)
            withContext(Dispatchers.Swing) { browser.createImmediately() }
            // Offscreen rendering has no vsync to pace it, so the frame rate is whatever this says.
            // 30 is what the page is *allowed* to repaint at, not what it costs: CEF paints only on
            // change, and each paint is a full-surface copy on the host side.
            browser.setWindowlessFrameRate(WINDOWLESS_FRAME_RATE)
            // Best-effort: a browser that never reports itself idle is still worth handing back,
            // because every operation on it is independently time-bounded anyway.
            withTimeoutOrNull(navigationTimeoutMs) { controller.initiallyIdle.await() }
            return controller
        }

        private const val WINDOWLESS_FRAME_RATE = 30
    }

    private inner class PageLoadHandler : CefLoadHandlerAdapter() {
        /**
         * Fires for every load, and the only one that matters here is the first time it says the
         * browser has stopped — that is the create-time `about:blank` finishing. See [initiallyIdle].
         */
        override fun onLoadingStateChange(
            browser: CefBrowser?,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) {
            if (browser?.identifier != this@CefWebViewController.browser.identifier) return
            if (!isLoading) initiallyIdle.complete(Unit)
        }

        override fun onLoadStart(
            browser: CefBrowser?,
            frame: CefFrame?,
            transitionType: CefRequest.TransitionType?,
        ) {
            if (browser?.identifier != this@CefWebViewController.browser.identifier) return
            // Injected into every frame, not just the main one: a site that renders its results in
            // an iframe of its own is still a site this lane is driving, and the inbox reports
            // subframe traffic (without letting it answer a workflow's await).
            frame?.executeJavaScript(channel.installScript(), frame.url.orEmpty(), 0)
            if (frame?.isMain != true) return
            // A new document means the old page's unread messages can never be answered and would
            // only mislead the next step that goes looking for one — and its promises can never
            // settle, so armed waits fail now rather than sitting out their timeout.
            inbox.clear()
            scriptResults.clear()
            channel.clearPending()
            serializer.started()
        }

        override fun onLoadEnd(
            browser: CefBrowser?,
            frame: CefFrame?,
            httpStatusCode: Int,
        ) {
            if (browser?.identifier != this@CefWebViewController.browser.identifier) return
            if (frame?.isMain != true) return
            serializer.finished()
        }

        /**
         * Sub-resource failures land here too but do not stop the document from loading, so only a
         * main-frame failure ends the navigation.
         *
         * `ERR_ABORTED` is excluded, and that exclusion is load-bearing on this platform in a way
         * it is not on the others: CEF reports an aborted load for the ordinary case of a page
         * navigating away from itself — a redirect, a click that leaves — and treating that as a
         * failure would turn every such workflow into a `PageLoadException` against a page that
         * went on to load perfectly well.
         */
        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (browser?.identifier != this@CefWebViewController.browser.identifier) return
            if (frame?.isMain != true) return
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            serializer.failed("${errorText.orEmpty().ifBlank { errorCode?.name ?: "load failed" }} loading $failedUrl")
        }
    }
}
