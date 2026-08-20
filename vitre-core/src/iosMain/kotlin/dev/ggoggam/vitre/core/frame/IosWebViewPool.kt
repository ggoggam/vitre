package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.net.FixtureScheme
import dev.ggoggam.vitre.core.net.FixtureSchemeHandler
import dev.ggoggam.vitre.core.net.InterceptedRequest
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.net.LaneNetworkTap
import dev.ggoggam.vitre.core.net.NetworkTap
import dev.ggoggam.vitre.core.net.ScriptedTap
import dev.ggoggam.vitre.core.net.ScriptedTapMessageHandler
import dev.ggoggam.vitre.core.net.firstHandled
import dev.ggoggam.vitre.core.webview.DEFAULT_NAVIGATION_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.DEFAULT_SCRIPT_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.IosWebViewController
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSProcessInfo
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

/**
 * A pool of lanes for iOS: one `WKWebView` each, every site loaded as a top-level document.
 *
 * The same arrangement `AndroidWebViewPool` uses, reached from the other direction. Android could
 * have framed these sites — `shouldInterceptRequest` will strip `X-Frame-Options` on the way past —
 * and iOS never could, because `WKURLSchemeHandler` refuses to register for `https` precisely so
 * that no application can answer for a real origin. Rather than build a proxy to fight that, the
 * lane stopped being a frame; `X-Frame-Options` and `frame-ancestors` govern who may *frame* a
 * page, and nobody is framing it. That arrangement then turned out to be the better one on Android
 * too, which is why it is the only one left (`docs/PARALLEL-LANES.md`).
 *
 * What is particular to iOS is the *parallelism*: four `WKWebView`s are four content processes with
 * four main threads, so the lanes are concurrent for the work and not only for the waiting. Android
 * shares one renderer across every WebView in the app and gets no such thing.
 *
 * Two things are worse here than on Android, both stated plainly on [tap] and [FixtureScheme] and
 * both consequences of having no interception hook: nothing can rewrite a response header, and the
 * network tap sees only what the page's own script asked for.
 *
 * The caller owns the [webViews] and must put them in the view hierarchy — a `WKWebView` that is
 * not in a window throttles timers and skips layout, which presents as a lane that loads and then
 * never finishes anything.
 */
@OptIn(ExperimentalForeignApi::class)
class IosWebViewPool(
    laneCount: Int = Lanes.MAX_LANES,
    private val policy: InterceptionPolicy = InterceptionPolicy(),
    scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
    navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
) {
    val laneIds: List<String> = Lanes.laneIds(laneCount)

    private val exchanges = LaneNetworkTap(policy)

    /**
     * What the lanes' own scripts reported, plus everything a [dev.ggoggam.vitre.core.net.RequestHandler] answered.
     *
     * Not the same thing as Android's tap and the gap is structural rather than an omission: with
     * no interception hook there is nothing below the page to watch, so document loads, images and
     * stylesheets are invisible here. See [ScriptedTap].
     */
    val tap: NetworkTap get() = exchanges

    /** One per lane, in [laneIds] order. Mount these; the pool cannot drive a detached WebView. */
    val webViews: List<WKWebView> = laneIds.map { newWebView() }

    private val controllers: Map<String, WebViewController> =
        laneIds.zip(webViews).associate { (id, webView) ->
            id to
                FixtureRoutingController(
                    delegate =
                        IosWebViewController(
                            webView = webView,
                            navigationTimeoutMs = navigationTimeoutMs,
                            scriptTimeoutMs = scriptTimeoutMs,
                        ),
                    policy = policy,
                )
        }

    val pool: FramePool =
        FramePool(
            laneIds = laneIds,
            tap = exchanges,
            lanes = controllers,
        )

    /**
     * Puts every lane on its placeholder.
     *
     * Not load-bearing — the lanes are drivable the moment they are mounted — but a lane still
     * showing the last run's results is indistinguishable from one that has already finished this
     * run, and that ambiguity costs more debugging time than it sounds like it should.
     */
    suspend fun open() = pool.resetAll()

    private fun newWebView(): WKWebView {
        val configuration = WKWebViewConfiguration()
        // The shared persistent store rather than a per-lane ephemeral one, which matches the
        // single cookie jar Android's WebViews share. A lane that logs in and a lane that then
        // reads the account are frequently the same workflow twice.
        configuration.websiteDataStore = WKWebsiteDataStore.defaultDataStore()
        // Registered only when there is something to serve. An unclaimed scheme is not inert —
        // every URL on it would answer 404 — and a pool driving real sites should have no private
        // scheme in play at all.
        if (policy.handlers.isNotEmpty()) {
            configuration.setURLSchemeHandler(
                urlSchemeHandler = FixtureSchemeHandler(policy, exchanges),
                forURLScheme = FixtureScheme.SCHEME,
            )
        }
        configuration.userContentController.addUserScript(
            WKUserScript(
                source = ScriptedTap.script(policy.maxCapturedBodyBytes, policy.captureBodies),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                // Every frame, not just the main one: a site that renders its results in an iframe
                // of its own is still a site this lane is driving.
                forMainFrameOnly = false,
            ),
        )
        configuration.userContentController.addScriptMessageHandler(
            scriptMessageHandler = ScriptedTapMessageHandler(exchanges),
            // A channel of its own rather than the bridge's. Tap reports on the bridge would sit
            // unread in the inbox forever — `AwaitMessage` consumes by type and would never match
            // them — and a chatty page would grow that deque without bound.
            name = ScriptedTap.HANDLER,
        )
        return WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
    }

    companion object {
        /**
         * How many lanes this device can carry, capped at [Lanes.MAX_LANES].
         *
         * A lane is genuinely expensive here in a way it is not on Android — each one is a separate
         * content process, which is the same fact that makes the lanes parallel. The cost and the
         * benefit are the same purchase, and on a 3GB phone four of them is how an app gets
         * jetsammed while it is in the background. Android's pool is deliberately more generous:
         * see `AndroidWebViewPool.forDevice` for why the tiers here do not transfer.
         *
         * Decided on total RAM rather than on what is free right now, so that two runs of the same
         * scenario are comparable.
         *
         * The cap is safe only because [FramePool.run] queues: fewer lanes now means the work takes
         * longer rather than some of it never happening.
         */
        fun forDevice(requested: Int = Lanes.MAX_LANES): Int {
            val totalMb = NSProcessInfo.processInfo.physicalMemory / BYTES_PER_MB
            val affordable =
                when {
                    totalMb < 2_048uL -> 1
                    totalMb < 4_096uL -> 2
                    totalMb < 6_144uL -> 3
                    else -> Lanes.MAX_LANES
                }
            return requested.coerceIn(1, minOf(affordable, Lanes.MAX_LANES))
        }

        private val BYTES_PER_MB: ULong = 1_048_576uL
    }
}

/**
 * A lane, with one thing added: a navigation to a URL some [dev.ggoggam.vitre.core.net.RequestHandler]
 * claims is moved onto [FixtureScheme] first.
 *
 * This is what keeps a workflow portable. The same `Navigate("https://shop.test/search?q=…")` step
 * runs on both platforms; Android answers it from `shouldInterceptRequest` and iOS answers it from
 * a scheme handler, and neither the workflow nor the handler that serves it has to know which.
 *
 * The probe runs the matching handler an extra time — once to find out whether it claims the URL,
 * and again for real when the load arrives. Handlers are documented as quick and side-effect-free,
 * and the alternative is caching a response against a load that may never happen.
 */
private class FixtureRoutingController(
    private val delegate: IosWebViewController,
    private val policy: InterceptionPolicy,
) : WebViewController by delegate {
    override suspend fun navigate(url: String) {
        val probe =
            InterceptedRequest(
                url = url,
                method = "GET",
                headers = mapOf("Accept" to "text/html"),
                isForMainFrame = true,
            )
        val handled = policy.handlers.isNotEmpty() && policy.firstHandled(probe) != null
        delegate.navigate(if (handled) FixtureScheme.encode(url) else url)
    }
}
