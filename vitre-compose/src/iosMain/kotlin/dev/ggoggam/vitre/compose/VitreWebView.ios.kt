package dev.ggoggam.vitre.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.ggoggam.vitre.core.webview.IosWebViewController
import dev.ggoggam.vitre.core.webview.PageLoadException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VitreWebView(
    state: VitreWebViewState,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    // onRelease has to close the same controller the factory built, and it is handed only the
    // WKWebView. Remembering the controller beside the factory is what connects the two; reading it
    // back off `state` would not, because release is exactly when `state.controller` is cleared.
    val mounted = remember { MountedController() }
    UIKitView(
        modifier = modifier,
        factory = {
            val config = WKWebViewConfiguration()
            val webView =
                WKWebView(
                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                    configuration = config,
                )
            // IosWebViewController installs its own navigation delegate; nothing here may replace
            // it, or awaited navigations would never resume.
            val controller = IosWebViewController(webView)
            mounted.controller = controller
            // The initial load goes through the controller, and is started undispatched so it takes
            // the controller's navigation lock right here — before the assignment below hands a
            // caller something it could immediately navigate with. Otherwise a workflow's first
            // Navigate could race this load and be resolved by it.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    controller.navigate(state.initialUrl)
                } catch (failure: PageLoadException) {
                    // A host page that will not load is for the workflow to report; it is not a
                    // reason to tear down the composition.
                    println("VitreWebView: initial load of ${state.initialUrl} failed: ${failure.message}")
                }
            }
            state.controller = controller
            webView
        },
        onRelease = { webView ->
            // Cleared first, so anything keyed on the controller stops before it is closed rather
            // than getting one frame in which to call a closed one.
            state.controller = null
            // The close() is what actually frees this WKWebView: its configuration retains the
            // script message handler strongly, so without it the whole graph outlives the screen.
            mounted.controller?.close()
            mounted.controller = null
            // Not destroy() — WKWebView has no such thing. Stopping the load is what keeps a page
            // that is still fetching from running on after its host is gone.
            webView.stopLoading()
        },
    )
}

/** Carries the factory's controller across to [UIKitView]'s `onRelease`, which only sees the view. */
@OptIn(ExperimentalForeignApi::class)
private class MountedController {
    var controller: IosWebViewController? = null
}
