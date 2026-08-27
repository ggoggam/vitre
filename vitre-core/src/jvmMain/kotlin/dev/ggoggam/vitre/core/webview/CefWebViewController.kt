package dev.ggoggam.vitre.core.webview

import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

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

    /**
     * The DevTools session [screenshot] captures through, opened on first use and kept.
     *
     * Kept because attaching is a protocol handshake with the browser process, and doing one per
     * screenshot would make a per-frame capture — the obvious way to record a run — pay for a
     * session it immediately throws away. Opened lazily rather than in `init` because a browser
     * whose page is never captured should not carry a DevTools session at all: it is an inspector
     * attached to the user's page, and the cheapest way to be sure it costs nothing is not to have
     * one.
     */
    private var devTools: CefDevToolsClient? = null

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

    /**
     * Captures through the **Chrome DevTools protocol**, because the obvious API does not work for
     * the kind of browser this controller creates.
     *
     * `CefBrowser.createScreenshot(nativeResolution)` looks like the answer and is the first thing
     * anyone tries. On the browser here it throws outright: this lane renders offscreen *with a
     * host render handler* (`CefRenderingWithHandler`, for the Compose z-order reasons in
     * [CefSurface]), so the browser is a `CefBrowserOsrWithHandler`, and that class's
     * `createScreenshot` is a one-line `throw UnsupportedOperationException` in the JCEF build this
     * module pins. Only `CefBrowserOsr` — the AWT-component variant this repo deliberately does not
     * use — implements it.
     *
     * The other candidate was [CefSurface.frames], which already holds the last painted BGRA frame
     * and would cost nothing to read. It loses on **freshness**, which is the property a screenshot
     * is for: CEF paints only when something changed and no faster than the windowless frame rate
     * this controller caps, so the newest frame can predate the step that just ran — a picture
     * taken after a click that shows the page before it. `Page.captureScreenshot` forces a render
     * and answers with that one. It is asked for **PNG** regardless of what the caller wanted, so
     * the only lossy step is the re-encode in [encode] rather than a JPEG decoded and recompressed.
     *
     * **Both routes are bounded by the size the host reported.** An offscreen browser's viewport
     * *is* its render handler's view rect, so a lane that has never had [CefSurface.resize] called
     * on it is 1×1, and a capture of it is one pixel — of a page that loaded perfectly well. That
     * is not something CDP can rescue, and a headless lane wanting a usable picture has to size its
     * surface first. It is the one place this platform's screenshot is weaker than the other two,
     * where the view has a size because it is in a hierarchy.
     *
     * This is also the one platform that *could* capture the whole scroll height — CDP takes
     * `captureBeyondViewport` — and deliberately does not; see [WebViewController.screenshot].
     */
    override suspend fun screenshot(options: ScreenshotOptions): PageScreenshot {
        checkOpen()
        // Only the capture is an operation on the browser. The re-encode is arithmetic over bytes
        // already in hand, so it stays outside the lock rather than making every other caller wait
        // on an ImageIO round trip.
        val png = serializer.exclusively { captureViaDevTools() }
        return withContext(Dispatchers.Default) { encode(png, options) }
    }

    private suspend fun captureViaDevTools(): ByteArray {
        val client =
            withContext(WebViewDispatcher) {
                devTools ?: browser.devToolsClient?.also { devTools = it }
            } ?: throw ScreenshotFailedException("CEF would not open a DevTools session on this browser")
        val response =
            try {
                withTimeout(scriptTimeoutMs) {
                    client.executeDevToolsMethod("Page.captureScreenshot", CAPTURE_PNG_PARAMS).await()
                }
            } catch (_: TimeoutCancellationException) {
                // A cancellation escaping here would be indistinguishable, one frame up, from the
                // caller having been cancelled — the same reasoning as ScriptTimeoutException.
                throw ScreenshotFailedException("Page.captureScreenshot did not answer within ${scriptTimeoutMs}ms")
            } ?: throw ScreenshotFailedException("Page.captureScreenshot returned no result")
        val encoded =
            runCatching {
                Json
                    .parseToJsonElement(response)
                    .jsonObject["data"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
                ?: throw ScreenshotFailedException("Page.captureScreenshot returned no image data: $response")
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw ScreenshotFailedException("Page.captureScreenshot returned data that is not base64") }
    }

    /**
     * Fits and re-encodes the captured PNG, and does neither when neither is needed.
     *
     * The pass-through matters more than it looks: a viewport already inside the caller's bound,
     * asked for as PNG, is the common case, and decoding a megapixel only to write the same pixels
     * back out is pure waste.
     */
    private fun encode(
        png: ByteArray,
        options: ScreenshotOptions,
    ): PageScreenshot {
        val source =
            ImageIO.read(ByteArrayInputStream(png))
                ?: throw ScreenshotFailedException("Could not decode the PNG CEF returned")
        val target = options.fit(source.width, source.height)
        if (options.format == ScreenshotFormat.Png && target.width == source.width && target.height == source.height) {
            return PageScreenshot(png, ScreenshotFormat.Png, source.width, source.height)
        }
        // JPEG has no alpha channel, and an ARGB image written as one comes out with the
        // transparent pixels black. TYPE_INT_RGB over white is what a browser would have shown.
        val opaque = options.format == ScreenshotFormat.Jpeg
        val scaled = resample(source, target, opaque)
        val out = ByteArrayOutputStream()
        when (options.format) {
            ScreenshotFormat.Png -> ImageIO.write(scaled, "png", out)
            ScreenshotFormat.Jpeg -> writeJpeg(scaled, options.quality, out)
        }
        return PageScreenshot(out.toByteArray(), options.format, target.width, target.height)
    }

    /**
     * Downscales by repeated halving, then one bilinear step onto the exact target.
     *
     * `Image.getScaledInstance` is the one-liner and is the wrong tool twice over. It hands back a
     * *lazily produced* image, so `drawImage(…, null)` with no `ImageObserver` can return before
     * any pixels exist and leave a blank or half-drawn frame — a bug that reproduces on a slow
     * machine and not on the one it was written on. And a single interpolated step across a large
     * ratio samples rather than averages, which on a page of 12px text is the difference between
     * legible and speckled. Halving averages every source pixel into the result, which is what
     * makes a screenshot still readable at a third of its size — and readable is the entire point.
     */
    private fun resample(
        source: BufferedImage,
        target: ScreenshotSize,
        opaque: Boolean,
    ): BufferedImage {
        var current = source
        var width = source.width
        var height = source.height
        while (width / 2 > target.width && height / 2 > target.height) {
            width /= 2
            height /= 2
            current = draw(current, width, height, opaque = false)
        }
        return draw(current, target.width, target.height, opaque)
    }

    private fun draw(
        source: BufferedImage,
        width: Int,
        height: Int,
        opaque: Boolean,
    ): BufferedImage {
        val out = BufferedImage(width, height, if (opaque) TYPE_INT_RGB else TYPE_INT_ARGB)
        out.createGraphics().apply {
            if (opaque) {
                color = Color.WHITE
                fillRect(0, 0, width, height)
            }
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            // A BufferedImage source is fully realised, so this blocks until the pixels are there
            // and the null observer is safe — which is exactly what getScaledInstance is not.
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        return out
    }

    /**
     * `ImageIO.write` has no quality argument, so the writer has to be driven directly — this is
     * the whole of what the other two platforms get from one parameter on `compress` /
     * `UIImageJPEGRepresentation`.
     */
    private fun writeJpeg(
        image: BufferedImage,
        quality: Int,
        out: ByteArrayOutputStream,
    ) {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").let {
                if (it.hasNext()) it.next() else throw ScreenshotFailedException("This JVM has no JPEG encoder")
            }
        val params =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            try {
                writer.write(null, IIOImage(image, null, null), params)
            } finally {
                writer.dispose()
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
        // Detached because this controller is what caused it to be opened. Safe to do while leaving
        // the browser alive: `getDevToolsClient` reopens on demand, so a host that keeps driving
        // this browser some other way is not left without one.
        devTools?.close()
        devTools = null
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

        /**
         * Always PNG, whatever the caller asked for: CDP's JPEG is a second lossy step on top of
         * the caller's own re-encode, and the pass-through in `encode` means the PNG is usually
         * handed straight back anyway.
         */
        private const val CAPTURE_PNG_PARAMS = """{"format":"png"}"""
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
