package dev.ggoggam.vitre.compose

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.ggoggam.vitre.core.webview.AndroidWebViewController
import dev.ggoggam.vitre.core.webview.PageLoadException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun VitreWebView(
    state: VitreWebViewState,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    // onRelease has to close the same controller the factory built, and it is handed only the
    // WebView. Remembering the controller beside the factory is what connects the two; reading it
    // back off `state` would not, because release is exactly when `state.controller` is cleared.
    val mounted = remember { MountedController() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // No webViewClient here: AndroidWebViewController installs its own to observe page
                // loads, and overwriting it would break every awaited navigate().
                val controller = AndroidWebViewController(this)
                mounted.controller = controller
                // The initial load goes through the controller, and is started undispatched so it
                // takes the controller's navigation lock right here — before the assignment below
                // hands a caller something it could immediately navigate with. Otherwise a
                // workflow's first Navigate could race this load and be resolved by it.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        controller.navigate(state.initialUrl)
                    } catch (failure: PageLoadException) {
                        // A host page that will not load is for the workflow to report; it is not
                        // a reason to tear down the composition.
                        Log.w(TAG, "initial load of ${state.initialUrl} failed", failure)
                    }
                }
                state.controller = controller
            }
        },
        onRelease = { webView ->
            // Cleared first, so anything keyed on the controller stops before it is closed rather
            // than getting one frame in which to call a closed one.
            state.controller = null
            mounted.controller?.close()
            mounted.controller = null
            // Safe here and nowhere earlier: onRelease runs after the view has been detached, and
            // destroy() on a WebView still in a hierarchy is what crashes.
            webView.destroy()
        },
    )
}

/** Carries the factory's controller across to [AndroidView]'s `onRelease`, which only sees the view. */
private class MountedController {
    var controller: AndroidWebViewController? = null
}

private const val TAG = "VitreWebView"
