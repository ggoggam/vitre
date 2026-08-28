package dev.ggoggam.vitre.core.testing

import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.PageScreenshot
import dev.ggoggam.vitre.core.webview.ScreenshotOptions
import dev.ggoggam.vitre.core.webview.WebViewController

/**
 * Records every interaction and replays scripted JS-eval responses.
 *
 * Tests configure [nextEvalResult] to control what `evaluateJs` returns, then assert against
 * [navigations] / [evaluatedScripts]. Use [simulatePageMessage] to push a string into the bridge as
 * if the page had posted it, and [onNavigate] to stand in for the page load a real controller waits
 * on.
 *
 * The inbox is the production [WebViewInbox], not a stand-in, because its buffering is exactly what
 * the workflow tests need to exercise: a message simulated *before* the `AwaitMessage` step runs
 * has to still be matchable.
 */
class FakeWebViewController : WebViewController {
    val navigations = mutableListOf<String>()
    val evaluatedScripts = mutableListOf<String>()

    /** Called with the script that was just submitted to `evaluateJs`. Default returns "null". */
    var nextEvalResult: (String) -> String = { "null" }

    /**
     * Stands in for the page load `navigate` awaits: it runs after the URL is recorded, so a test
     * can hold a navigation open, or throw from it to simulate a load failure. Default returns
     * immediately, i.e. a page that loads instantly.
     */
    var onNavigate: suspend (String) -> Unit = { }

    private val inbox = WebViewInbox()

    /**
     * The production ordering policy, not a stand-in — the same reason the inbox is real.
     *
     * A simpler ordering written for tests would be the one place a lease bug could hide from the
     * tests written to catch it, which is precisely how the two bugs in `docs/PLAN.md` survived: the
     * fake was laxer than production in exactly the places production was wrong. Thread confinement
     * is the half deliberately left out, since a unit test has no main thread to confine to.
     */
    private val order = WebViewOrdering()

    override val bridge: WebViewBridge =
        DefaultWebViewBridge(
            inbox = inbox,
            evaluateJs = { script -> evaluateJs(script) },
        )

    /** A real jar, unlike the platform ones, so a test can seed a session or assert on one. */
    override val cookies: FakeCookieStore = FakeCookieStore()

    /** `html to baseUrl` for each [loadHtml] call, in order. */
    val loadedHtml = mutableListOf<Pair<String, String?>>()

    /** True once [close] has run, so a test can assert the host tore the controller down. */
    var closed = false
        private set

    override suspend fun navigate(url: String) {
        checkOpen()
        order.ordered {
            navigations += url
            onNavigate(url)
        }
    }

    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) {
        checkOpen()
        order.ordered {
            loadedHtml += html to baseUrl
            onNavigate(baseUrl ?: "about:blank")
        }
    }

    override suspend fun evaluateJs(script: String): String {
        checkOpen()
        return order.ordered {
            evaluatedScripts += script
            nextEvalResult(script)
        }
    }

    /** The options each [screenshot] call asked for, in order. */
    val screenshotRequests = mutableListOf<ScreenshotOptions>()

    /**
     * Called with the options [screenshot] was asked for.
     *
     * The default answers with the eight-byte PNG signature, sized by running the *production*
     * [ScreenshotOptions.fit] over a notional 800×600 viewport — real enough for a caller that
     * sniffs the format or reasons about the size, and small enough that no test carries a bitmap
     * around. Sharing the real fitting rule is the point: a fake that made its own size up would be
     * laxer than production in exactly the way `docs/PLAN.md` says the last two bugs hid.
     */
    var nextScreenshot: (ScreenshotOptions) -> PageScreenshot = { options ->
        val size = options.fit(FAKE_VIEWPORT_WIDTH, FAKE_VIEWPORT_HEIGHT)
        PageScreenshot(PNG_SIGNATURE, options.format, size.width, size.height)
    }

    /**
     * Records the request and hands back [nextScreenshot].
     *
     * Ordered through the same [WebViewOrdering] as everything else, because that is the property a
     * test would want to assert: a screenshot is an operation on the page, and one taken between a
     * `WaitFor` and its `Extract` is exactly the interleaving `exclusively` exists to prevent. A
     * fake that skipped the lock here would be laxer than production in a new place — see the note
     * on [order].
     */
    override suspend fun screenshot(options: ScreenshotOptions): PageScreenshot {
        checkOpen()
        return order.ordered {
            screenshotRequests += options
            nextScreenshot(options)
        }
    }

    override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

    /** Mirrors production: closing is idempotent and takes the controller out of service. */
    override fun close() {
        closed = true
    }

    private fun checkOpen() = check(!closed) { "controller is closed" }

    /**
     * Pushes [message] into the bridge as if the page had posted it. Pass `fromMainFrame = false`
     * to stand in for an embedded iframe, whose messages observers see but awaits never match.
     */
    fun simulatePageMessage(
        message: String,
        fromMainFrame: Boolean = true,
    ) = inbox.deliver(message, fromMainFrame)

    /** Stands in for the new-document reset a real controller performs on page start. */
    fun simulatePageStart() = inbox.clear()

    private companion object {
        const val FAKE_VIEWPORT_WIDTH = 800
        const val FAKE_VIEWPORT_HEIGHT = 600

        /** The PNG magic number. Enough for a caller that checks what it was handed. */
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
