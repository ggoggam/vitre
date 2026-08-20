package dev.ggoggam.vitre.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFClient
import dev.ggoggam.vitre.core.webview.CefWebViewController
import dev.ggoggam.vitre.core.webview.PageLoadException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The desktop host. Two differences from the other two platforms are visible to a caller.
 *
 * **The page is Compose content, not a platform view.** It renders offscreen and is drawn as an
 * image, so anything the host draws over it — a sheet, a dialog, a toolbar — appears over it, and
 * the lane clips and scrolls like any other composable. See `CefSurface` for why windowed rendering
 * was the wrong trade here.
 *
 * **The browser cannot be built synchronously.**
 *
 * `KCEF.newClient()` suspends — on first run the Chromium bundle is still being unpacked — so
 * `state.controller` stays null for longer here than on Android or iOS, where the WebView exists
 * the moment the composable is measured. That is not a new contract, though: `controller` is
 * documented as null until the view is mounted, and an effect keyed on it already handles the wait.
 * A host that wants to show a spinner should key it on `state.controller == null` rather than
 * assume the first frame has one.
 *
 * KCEF must have been initialised (`KCEF.init`) before this composable enters the composition.
 */
@Composable
actual fun VitreWebView(
    state: VitreWebViewState,
    modifier: Modifier,
) {
    var mounted by remember(state) { mutableStateOf<MountedBrowser?>(null) }

    LaunchedEffect(state) {
        // A client of its own, because a KCEFClient holds one load handler and the controller
        // claims it — see CefWebViewController.
        val client = KCEF.newClient()
        val controller = CefWebViewController.create(client)
        mounted = MountedBrowser(client, controller)
        // Started undispatched so it takes the controller's navigation lock right here — before the
        // assignment below hands a caller something it could immediately navigate with. Otherwise a
        // workflow's first Navigate could race this load and be resolved by it. Same reasoning, and
        // the same ordering, as the Android host.
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                controller.navigate(state.initialUrl)
            } catch (failure: PageLoadException) {
                // A host page that will not load is for the workflow to report; it is not a reason
                // to tear down the composition.
                System.err.println("VitreWebView: initial load of ${state.initialUrl} failed: ${failure.message}")
            }
        }
        state.controller = controller
    }

    DisposableEffect(state) {
        onDispose {
            // Cleared first, so anything keyed on the controller stops before it is closed rather
            // than getting one frame in which to call a closed one.
            state.controller = null
            mounted?.dispose()
        }
    }

    // Drawn only once there is a lane to draw. Before that the host's own background shows
    // through, which is what a caller wants for the length of the first-run bundle download.
    mounted?.let { mount ->
        CefSurfaceView(surface = mount.controller.surface, modifier = modifier)
    }
}

/**
 * One browser, its client, and the teardown the two of them need.
 *
 * A holder rather than two locals because disposal order matters and is easy to get wrong: the
 * controller has to give the client its handlers back before the browser goes, and the browser has
 * to go before the client, or the client disposes a browser that is still holding a renderer
 * process.
 */
private class MountedBrowser(
    private val client: KCEFClient,
    val controller: CefWebViewController,
) {
    fun dispose() {
        controller.close()
        runCatching { controller.browser.dispose() }
        runCatching { client.dispose() }
    }
}
