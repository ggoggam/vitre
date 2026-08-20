package dev.ggoggam.vitre.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import dev.ggoggam.vitre.core.frame.FramePool
import dev.ggoggam.vitre.core.frame.IosWebViewPool
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKWebView

/**
 * One `WKWebView` per lane, sized to the device — see [IosWebViewPool] for what that costs here.
 *
 * [onUnavailable] fires only if the pool itself cannot be built. There is no platform capability
 * left for a lane to be missing.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VitreFrameHost(
    laneCount: Int,
    policy: InterceptionPolicy,
    navigationTimeoutMs: Long,
    scriptTimeoutMs: Long,
    onPoolReady: (FramePool) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
) {
    val ready = remember(onPoolReady) { onPoolReady }
    val unavailable = remember(onUnavailable) { onUnavailable }

    val host =
        remember(laneCount, policy, navigationTimeoutMs, scriptTimeoutMs) {
            runCatching {
                IosWebViewPool(
                    laneCount = IosWebViewPool.forDevice(laneCount),
                    policy = policy,
                    scriptTimeoutMs = scriptTimeoutMs,
                    navigationTimeoutMs = navigationTimeoutMs,
                )
            }
        }

    // The lanes are mounted before the pool is handed over. A WKWebView outside the view hierarchy
    // has its timers throttled and its layout skipped, so a workflow started against a detached
    // lane does not fail — it hangs, which is a much worse thing to debug.
    host.getOrNull()?.let { LaneGrid(it.webViews, modifier) }

    LaunchedEffect(host) {
        val opened = host.getOrNull()
        if (opened == null) {
            unavailable(host.exceptionOrNull()?.message ?: "the lane pool could not be built")
            return@LaunchedEffect
        }
        try {
            opened.open()
            ready(opened.pool)
        } catch (failure: RuntimeException) {
            unavailable(failure.message ?: "the lanes did not load")
        }
    }
}

/**
 * The same two-column arrangement the Android pool lays out, in Compose.
 *
 * `weight` on both axes rather than a fixed size: a lane sized by its own content is a lane that
 * collapses, and four collapsed WebViews look exactly like four that failed to load.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun LaneGrid(
    webViews: List<WKWebView>,
    modifier: Modifier,
) {
    // Derived from what the pool actually built, not from what was asked for: `forDevice` may have
    // handed back fewer lanes, and a grid laid out for four with two in it looks like two failures.
    val columns = if (webViews.size <= 2) 1 else 2
    Column(
        modifier = modifier.fillMaxSize().padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        webViews.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                row.forEach { webView ->
                    UIKitView(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        factory = { webView },
                    )
                }
                // A last row with one lane in a two-column grid would otherwise stretch that lane
                // across both, which reads as a different layout rather than a missing lane.
                repeat(columns - row.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}
