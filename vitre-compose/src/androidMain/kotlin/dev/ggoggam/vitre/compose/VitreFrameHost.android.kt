package dev.ggoggam.vitre.compose

import android.util.Log
import android.webkit.WebView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ggoggam.vitre.core.frame.AndroidWebViewPool
import dev.ggoggam.vitre.core.frame.FramePool
import dev.ggoggam.vitre.core.net.InterceptionPolicy

/**
 * One WebView per lane, sized to the device.
 *
 * The lane count is decided by [AndroidWebViewPool.forDevice] rather than taken at face value.
 * Fewer lanes costs wall-clock and nothing else, because `FramePool.run` queues.
 */
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
    val context = LocalContext.current
    val pool =
        remember(context, laneCount, policy, navigationTimeoutMs, scriptTimeoutMs) {
            runCatching {
                AndroidWebViewPool(
                    context = context,
                    laneCount = AndroidWebViewPool.forDevice(context, laneCount),
                    policy = policy,
                    scriptTimeoutMs = scriptTimeoutMs,
                    navigationTimeoutMs = navigationTimeoutMs,
                ).also { it.allowThirdPartyCookies() }
            }
        }

    // Mounted before the pool is handed over, so nothing can start a workflow against a WebView
    // that is not yet in the hierarchy.
    pool.getOrNull()?.let { LaneGrid(it.webViews, modifier) }

    LaunchedEffect(pool) {
        val opened = pool.getOrNull()
        if (opened == null) {
            unavailable(pool.exceptionOrNull()?.message ?: "the lane pool could not be built")
            return@LaunchedEffect
        }
        try {
            opened.open()
            ready(opened.pool)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "webview pool failed to open", failure)
            unavailable(failure.message ?: "the lanes did not load")
        }
    }
}

/**
 * Two columns of lanes, laid out in Compose.
 *
 * `weight` on both axes rather than a fixed size: a lane sized by its own content is a lane that
 * collapses, and four collapsed WebViews look exactly like four that failed to load.
 */
@Composable
private fun LaneGrid(
    webViews: List<WebView>,
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
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        factory = { webView },
                    )
                }
                // A last row with one lane in a two-column grid would otherwise stretch it across
                // both, which reads as a different layout rather than as a missing lane.
                repeat(columns - row.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}

private const val TAG = "VitreFrameHost"
