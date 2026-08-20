package dev.ggoggam.vitre.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.core.frame.FramePool
import dev.ggoggam.vitre.core.frame.KcefWebViewPool
import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.webview.CefSurface
import kotlinx.coroutines.CancellationException

/**
 * One CEF browser per lane, sized to the machine.
 *
 * The lane count is decided by [KcefWebViewPool.forDevice] rather than taken at face value. Fewer
 * lanes costs wall-clock and nothing else, because `FramePool.run` queues.
 *
 * Unlike the Android host, the pool is built in an effect rather than in `remember`: every step of
 * building it suspends, because KCEF hands out clients asynchronously and the browsers are created
 * on the EDT. So the grid appears a frame or two after this composable does, and [onUnavailable]
 * carries the reason when it does not appear at all — most often that `KCEF.init` was never called
 * or has not finished.
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
    var lanes by remember(laneCount, policy, navigationTimeoutMs, scriptTimeoutMs) { mutableStateOf<KcefWebViewPool?>(null) }

    LaunchedEffect(laneCount, policy, navigationTimeoutMs, scriptTimeoutMs) {
        val built =
            runCatching {
                KcefWebViewPool.create(
                    laneCount = KcefWebViewPool.forDevice(laneCount),
                    policy = policy,
                    scriptTimeoutMs = scriptTimeoutMs,
                    navigationTimeoutMs = navigationTimeoutMs,
                )
            }.getOrElse { failure ->
                // Cancellation is not a failure to report, it is this effect being torn down, and
                // `runCatching` does not know the difference. Reported, it arrives at the host as
                // "the lane pool could not be built: The coroutine scope left the composition" —
                // which names the one thing that did not go wrong.
                if (failure is CancellationException) throw failure
                unavailable(failure.message ?: "the lane pool could not be built")
                return@LaunchedEffect
            }
        // Published before `open()` so the components are in the window while the lanes load. The
        // browsers are drivable either way — `create` forces them into existence — but a lane
        // loading off screen is a lane nobody can see go wrong.
        lanes = built
        try {
            built.open()
            ready(built.pool)
        } catch (failure: CancellationException) {
            // A `CancellationException` is a `RuntimeException`, so it would otherwise be caught
            // below and reported as a lane that did not load.
            throw failure
        } catch (failure: RuntimeException) {
            unavailable(failure.message ?: "the lanes did not load")
        }
    }

    // Holds the pool it is keyed on rather than reading `lanes` back when it is disposed. The two
    // differ exactly once, and that once is fatal: publishing the pool *changes this effect's key*,
    // so Compose disposes the previous effect — and an `onDispose` that reads `lanes` reads it
    // after the assignment, sees the pool that has just been published, and tears it down.
    //
    // What that looks like is not a crash. `open()` is already loading the lanes' placeholders when
    // its browsers go away underneath it, so the load callbacks never arrive and the wait runs to
    // its limit: the host reports "timed out waiting for the page to load" and shows no lanes,
    // 30s later by default and 90s later for a scenario that raised the timeout for heavy sites.
    val mounted = lanes
    DisposableEffect(mounted) {
        onDispose { mounted?.dispose() }
    }

    lanes?.let { LaneGrid(it.surfaces, modifier) }
}

/**
 * Two columns of lanes, laid out in Compose and drawn by it.
 *
 * `weight` on both axes rather than a fixed size: a lane sized by its own content is a lane that
 * collapses, and four collapsed lanes look exactly like four that failed to load.
 */
@Composable
private fun LaneGrid(
    surfaces: List<CefSurface>,
    modifier: Modifier,
) {
    // Derived from what the pool actually built, not from what was asked for: `forDevice` may have
    // handed back fewer lanes, and a grid laid out for four with two in it looks like two failures.
    val columns = if (surfaces.size <= 2) 1 else 2
    Column(
        modifier = modifier.fillMaxSize().padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        surfaces.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                row.forEach { surface ->
                    CefSurfaceView(surface = surface, modifier = Modifier.weight(1f).fillMaxSize())
                }
                // A last row with one lane in a two-column grid would otherwise stretch it across
                // both, which reads as a different layout rather than as a missing lane.
                repeat(columns - row.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}
