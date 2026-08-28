package dev.ggoggam.vitre.core.frame

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import dev.ggoggam.vitre.core.net.AndroidNetworkInterceptor
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.net.NetworkTap
import dev.ggoggam.vitre.core.webview.AndroidWebViewController
import dev.ggoggam.vitre.core.webview.DEFAULT_NAVIGATION_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.DEFAULT_SCRIPT_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.webview.applyVitreLayoutParams
import dev.ggoggam.vitre.core.webview.applyVitreWebSettings

/**
 * A pool of lanes for Android: one `WebView` each, every site loaded as a top-level document.
 *
 * The same arrangement `IosWebViewPool` uses — iOS reached it because `WKWebView` will not let an
 * application answer for an `https` origin, and Android reached it by measurement.
 *
 * There *was* a second arrangement here: four `<iframe>`s in one WebView, with
 * `shouldInterceptRequest` stripping `X-Frame-Options` on the way past so a site that refuses to be
 * framed would render anyway. It worked, and it was deleted, because what it bought did not survive
 * being measured:
 *
 *  - **No parallelism was lost.** Four `WebView`s produce *one* renderer process, and so does one
 *    WebView with four iframes — Android WebView shares a single renderer across the whole app,
 *    unlike Chrome. Four lanes share one JS main thread whichever way they are built. The
 *    parallelism argument for a WebView per lane is an iOS argument and does not transfer.
 *  - **No memory was lost.** App PSS came out within noise of the iframe host's, because the memory
 *    lives in that shared renderer rather than in each `WebView`.
 *  - **Four documented failure modes stopped existing**: the host document's collapsing grid, the
 *    lane adoption handshake, the navigation token that made it race-free, and `'unsafe-eval'`
 *    having to be spliced into every site's CSP so the injected runtime could evaluate anything.
 *  - **Sessions became first-party.** A lane is top-level on its own site, so its cookies and
 *    storage behave the way the site expects. A framed shop was third-party and lost whatever
 *    partitioning took.
 *
 * What the iframe arrangement did buy, and what is genuinely gone with it, is the ability to put a
 * site inside a document of *ours* — so a caller that wanted to frame `github.com` next to its own
 * chrome now has to render its own chrome in Compose around a lane instead.
 *
 * `docs/PARALLEL-LANES.md` has the numbers and the traps that still bite. The caller owns the
 * [webViews] and must put them in the view hierarchy.
 */
@SuppressLint("SetJavaScriptEnabled")
class AndroidWebViewPool(
    context: Context,
    laneCount: Int = Lanes.MAX_LANES,
    /**
     * Interception is what makes fixtures, the tap and CORS relaxation work, and by default it
     * covers the lane's own document — see [InterceptionPolicy.interceptMainFrame], which is worth
     * reading before pointing a pool at real sites, since a document this library refetched is not
     * byte-for-byte a document the browser fetched.
     */
    policy: InterceptionPolicy = InterceptionPolicy(),
    scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
    navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
) {
    val laneIds: List<String> = Lanes.laneIds(laneCount)

    private val interceptor = AndroidNetworkInterceptor(policy)

    /** Everything the interceptor saw, across every lane — one tap, shared by all of them. */
    val tap: NetworkTap get() = interceptor

    val webViews: List<WebView> = laneIds.map { newWebView(context) }

    private val controllers: Map<String, WebViewController> =
        laneIds.zip(webViews).associate { (id, webView) ->
            id to
                AndroidWebViewController(
                    webView = webView,
                    navigationTimeoutMs = navigationTimeoutMs,
                    interceptor = interceptor,
                    scriptTimeoutMs = scriptTimeoutMs,
                )
        }

    val pool: FramePool =
        FramePool(
            laneIds = laneIds,
            tap = interceptor,
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

    /**
     * A lane is first-party on its own site, so this is about the *site's* embeds rather than about
     * the lane — an ad frame, an auth iframe, a CDN that sets a cookie. Left on to match what a
     * browser would do.
     */
    fun allowThirdPartyCookies(allow: Boolean = true) {
        webViews.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it, allow) }
    }

    private fun newWebView(context: Context): WebView =
        WebView(context).apply {
            // A lane is handed to the caller to put in a hierarchy, so it is the caller who
            // could get the layout params wrong — and getting them wrong costs a silently blank
            // page whose DOM still extracts correctly. Defaulted here so that only a caller who
            // deliberately replaces them can reintroduce it. See applyVitreLayoutParams.
            applyVitreLayoutParams()
            // JavaScript, DOM storage and the user agent — shared with the `vitre-compose` host so
            // a page cannot behave differently in one and not the other.
            applyVitreWebSettings()
            // A lane may be pointed at plain http, and a page that ends up mixed is otherwise
            // blocked silently. Lane-only: the composable host is showing a page to a person, and
            // relaxing mixed content there is the host app's call rather than this library's.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // No webViewClient here: AndroidWebViewController installs its own and that is also
            // where the interceptor is wired in. Overwriting it removes both.
        }

    companion object {
        /**
         * How many lanes this device can carry, capped at [Lanes.MAX_LANES].
         *
         * Deliberately more generous than `IosWebViewPool.forDevice`, and the asymmetry is measured
         * rather than an oversight: on iOS a lane is its own content process, which is what makes
         * the lanes parallel and what gets an app jetsammed on a 3GB phone. Here every `WebView` in
         * the app shares one renderer, that renderer is where the memory is, and four lanes came
         * out within noise of one on the emulator. Scaling the count down by total RAM would buy
         * almost nothing and cost the parallelism the pool exists for.
         *
         * So only the genuinely small devices are trimmed, and even that is precautionary: the case
         * it guards against — four heavy third-party sites live at once, each with its own Java-side
         * object graph and compositor layers — is the one that was never measured.
         *
         * Decided on total RAM rather than on what is free right now, because a pool whose width
         * changed between two runs of the same scenario would make every measurement unrepeatable.
         *
         * Trimming is safe only because [FramePool.run] queues: fewer lanes means the work takes
         * longer, not that some of it never happens.
         */
        fun forDevice(
            context: Context,
            requested: Int = Lanes.MAX_LANES,
        ): Int {
            val manager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return requested.coerceIn(1, Lanes.MAX_LANES)
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val totalMb = info.totalMem / BYTES_PER_MB
            val affordable = if (manager.isLowRamDevice || totalMb < SMALL_DEVICE_MB) SMALL_DEVICE_LANES else Lanes.MAX_LANES
            return requested.coerceIn(1, minOf(affordable, Lanes.MAX_LANES))
        }

        private const val BYTES_PER_MB = 1024L * 1024L
        private const val SMALL_DEVICE_MB = 2_048L
        private const val SMALL_DEVICE_LANES = 2
    }
}
