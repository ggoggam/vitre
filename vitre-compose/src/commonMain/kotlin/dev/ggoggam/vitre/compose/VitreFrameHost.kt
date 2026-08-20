package dev.ggoggam.vitre.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ggoggam.vitre.core.frame.FramePool
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.webview.DEFAULT_NAVIGATION_TIMEOUT_MS
import dev.ggoggam.vitre.core.webview.DEFAULT_SCRIPT_TIMEOUT_MS

/**
 * Mounts a grid of lanes and hands back a [FramePool] once every one of them is drivable.
 *
 * A lane is one WebView with one site loaded top-level, on both platforms. The one seam that
 * reaches a caller is that [policy]'s rewriting is inert on iOS, where `WKWebView` will not let an
 * application intercept `https` — fixtures still work there, and the tap sees only what the page's
 * own script asked for. `docs/PARALLEL-LANES.md` has the reasoning.
 *
 * The lanes are visible, and that is the point: four shops searching at once is the sort of claim
 * that is tedious to believe from a log and instant to believe on screen. It is also most of the
 * debugging — a lane that comes back empty looks very different from a lane showing a bot check.
 *
 * ```
 * var pool by remember { mutableStateOf<FramePool?>(null) }
 * VitreFrameHost(
 *     laneCount = 4,
 *     policy = InterceptionPolicy(handlers = shops.map { it.handler }),
 *     onPoolReady = { pool = it },
 *     onUnavailable = { reason -> … },
 * )
 * ```
 *
 * [onPoolReady] fires only after every lane has loaded and answered, because a pool handed over
 * earlier has nothing to drive. [onUnavailable] fires instead when the lanes could not be built or
 * did not load.
 */
@Composable
expect fun VitreFrameHost(
    /**
     * How many lanes to ask for. A ceiling rather than a promise — a pool sizes itself to the device
     * and may hand back fewer, which `FramePool.run` makes safe by queueing rather than by dropping
     * the surplus work.
     */
    laneCount: Int = 4,
    policy: InterceptionPolicy = InterceptionPolicy(),
    /**
     * How long a lane waits for a document to parse.
     *
     * Worth raising well past the single-page default for heavy third-party sites. On Android in
     * particular, four lanes share one renderer and one main thread — every WebView in the app
     * does — so each is slower in a pool than it would be alone, and the failure is a navigation
     * timeout against a page that has visibly rendered.
     */
    navigationTimeoutMs: Long = DEFAULT_NAVIGATION_TIMEOUT_MS,
    scriptTimeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
    onPoolReady: (FramePool) -> Unit = {},
    onUnavailable: (String) -> Unit = {},
    modifier: Modifier = Modifier,
)
