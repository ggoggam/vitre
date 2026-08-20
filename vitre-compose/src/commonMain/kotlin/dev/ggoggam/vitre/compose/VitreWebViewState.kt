package dev.ggoggam.vitre.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ggoggam.vitre.core.webview.WebViewController

/**
 * The handle a caller keeps on a [VitreWebView]: what page it starts on, and the
 * [WebViewController] driving it once there is one.
 *
 * Held by the caller rather than delivered through a callback because the controller's *absence* is
 * as meaningful as its presence. It is null before the WebView is mounted and null again once the
 * WebView leaves the composition, and both transitions are ordinary Compose state changes — so an
 * effect keyed on [controller] starts work when the page arrives and, just as importantly, tears it
 * down when the page goes away. A callback can only ever report the first of those two.
 *
 * @see rememberVitreWebViewState
 */
@Stable
class VitreWebViewState internal constructor(
    /** The page the WebView loads on mount. Changing it later has no effect — see [VitreWebView]. */
    val initialUrl: String,
) {
    /** Null until the WebView is mounted; null again after it leaves the composition. */
    var controller: WebViewController? by mutableStateOf(null)
        internal set
}

/**
 * Remembers a [VitreWebViewState] for the lifetime of this composition.
 *
 * ```
 * val webViewState = rememberVitreWebViewState("https://example.com")
 * val controller = webViewState.controller
 *
 * LaunchedEffect(controller) {
 *     val page = controller ?: return@LaunchedEffect
 *     WorkflowEngine(page).run(workflow).collect { … }
 * }
 *
 * VitreWebView(state = webViewState, modifier = Modifier.fillMaxSize())
 * ```
 *
 * Keyed on [initialUrl]: a different starting page is a different WebView, and reusing the old
 * state would leave a stale controller visible for the frame between the two mounts.
 */
@Composable
fun rememberVitreWebViewState(initialUrl: String = "about:blank"): VitreWebViewState =
    remember(initialUrl) { VitreWebViewState(initialUrl) }
