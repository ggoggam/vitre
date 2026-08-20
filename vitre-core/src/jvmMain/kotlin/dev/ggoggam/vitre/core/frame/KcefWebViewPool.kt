package dev.ggoggam.vitre.core.frame

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import dev.ggoggam.vitre.core.net.CefNetworkInterceptor
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.net.NetworkTap
import dev.ggoggam.vitre.core.webview.CefSurface
import dev.ggoggam.vitre.core.webview.CefWebViewController
import dev.ggoggam.vitre.core.webview.DEFAULT_NAVIGATION_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.DEFAULT_SCRIPT_TIMEOUT_MS
import java.lang.management.ManagementFactory

/**
 * A pool of lanes for the desktop: one Kotlin CEF Browser each, every site loaded as a top-level
 * document.
 *
 * The same arrangement the other two pools use, and here it is the only one that was ever on the
 * table — but the *parallelism* is the best of the three. Chromium gives each browser its own
 * renderer process, so four lanes are four processes with four main threads, the way iOS's content
 * processes are and the way Android's single shared renderer is not (`docs/PARALLEL-LANES.md`).
 *
 * Interception is also the fullest of the three: CEF's resource pipeline lets an application answer
 * a request outright, so fixtures, response bodies on the [tap], and relaxed CORS all work here
 * exactly as they do on Android — where iOS gets a reduced version because WebKit will not let an
 * application answer for an `https` origin.
 *
 * ### Before constructing one
 *
 * KCEF downloads and unpacks a Chromium bundle on first use, so `KCEF.init` must have completed
 * before [create] is called — [create] asks it for a client and will fail if it has not. A host
 * that wants a progress bar for that download wants `KCEF.init`'s own progress callbacks; there is
 * nothing useful this class could add to them.
 *
 * The caller draws the [surfaces]. Unlike the other two pools there is nothing to mount: a lane
 * renders offscreen into a buffer, which is what lets Compose draw over it, clip it and scroll it
 * — see [CefSurface] for why a heavyweight component was the wrong trade here.
 */
class KcefWebViewPool private constructor(
    val laneIds: List<String>,
    private val clients: List<KCEFClient>,
    private val controllers: Map<String, CefWebViewController>,
    private val interceptor: CefNetworkInterceptor,
) {
    /** Everything the interceptor saw, across every lane — one tap, shared by all of them. */
    val tap: NetworkTap get() = interceptor

    /**
     * One per lane, in [laneIds] order. **Draw these** — a lane renders offscreen, so this is the
     * only way its page reaches the screen. See [dev.ggoggam.vitre.core.webview.CefSurface].
     */
    val surfaces: List<CefSurface> = laneIds.map { controllers.getValue(it).surface }

    /** The browsers behind the lanes, for the occasional thing the controller does not expose. */
    val browsers: List<KCEFBrowser> = laneIds.map { controllers.getValue(it).browser }

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
     * Closes every controller and tears down the browsers and clients behind them.
     *
     * Unlike the other two pools this one has something to dispose: a CEF browser holds a renderer
     * process, and a pool that is dropped without this leaves four of them running until the JVM
     * exits. Call it after the components have left the window.
     */
    fun dispose() {
        controllers.values.forEach { it.close() }
        browsers.forEach { runCatching { it.dispose() } }
        clients.forEach { runCatching { it.dispose() } }
        // The interceptor has threads of its own, for the same reason the browsers do: an
        // intercepted fetch must not happen on the thread CEF calls it on. See CefNetworkInterceptor.
        interceptor.dispose()
    }

    companion object {
        /**
         * Builds a pool of [laneCount] lanes, each with its own KCEF client.
         *
         * A client per lane rather than one shared: `KCEFClient` holds exactly one load handler and
         * one request handler, so two lanes on one client would overwrite each other's page-load
         * callbacks — which presents as a lane whose `navigate` never returns. See
         * [CefWebViewController].
         */
        suspend fun create(
            laneCount: Int = Lanes.MAX_LANES,
            policy: InterceptionPolicy = InterceptionPolicy(),
            scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
            navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
        ): KcefWebViewPool {
            val laneIds = Lanes.laneIds(laneCount)
            val interceptor = CefNetworkInterceptor(policy)
            val clients = laneIds.map { KCEF.newClient().apply { addRequestHandler(interceptor.requestHandler) } }
            // The controller builds its own browser, and the order in which it does so is
            // load-bearing — see CefWebViewController.create.
            val controllers =
                laneIds.zip(clients).associate { (id, client) ->
                    id to
                        CefWebViewController.create(
                            client = client,
                            navigationTimeoutMs = navigationTimeoutMs,
                            scriptTimeoutMs = scriptTimeoutMs,
                        )
                }
            return KcefWebViewPool(laneIds, clients, controllers, interceptor)
        }

        /**
         * How many lanes this machine can carry, capped at [Lanes.MAX_LANES].
         *
         * The most generous of the three tiers, because a desktop is the one platform where the
         * cost is genuinely affordable: a lane is its own renderer process, as on iOS, but there is
         * no jetsam and typically an order of magnitude more RAM than the 3GB phone that made
         * `IosWebViewPool.forDevice` cautious. Only a machine small enough that four live Chromium
         * renderers would swap gets trimmed.
         *
         * Decided on total RAM rather than on what is free right now, so that two runs of the same
         * scenario are comparable — and only trims, because [FramePool.run] queues: fewer lanes
         * means the work takes longer, not that some of it never happens.
         */
        fun forDevice(requested: Int = Lanes.MAX_LANES): Int {
            val totalMb = totalMemoryMb() ?: return requested.coerceIn(1, Lanes.MAX_LANES)
            val affordable =
                when {
                    totalMb < 4_096L -> 1
                    totalMb < 8_192L -> 2
                    else -> Lanes.MAX_LANES
                }
            return requested.coerceIn(1, minOf(affordable, Lanes.MAX_LANES))
        }

        /**
         * Physical RAM, or null where the JVM will not say.
         *
         * `Runtime.maxMemory()` is deliberately not used: it reports the *heap* ceiling, and the
         * renderers this is sizing live outside the JVM entirely, so it would answer a question
         * about the wrong memory.
         */
        private fun totalMemoryMb(): Long? =
            runCatching {
                val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                bean.totalMemorySize / BYTES_PER_MB
            }.getOrNull()

        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
