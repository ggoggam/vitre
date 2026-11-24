package dev.ggoggam.vitre.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.webview.WebViewIdentity

/**
 * Hosts a platform WebView, publishing its [WebViewController] on [state] while it is mounted.
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
 * The WebView is created once and `state.initialUrl` is loaded into it once, on mount; a later
 * change to that URL is not a navigation and does nothing. Everything after the first page is the
 * controller's job — that is what `navigate` is for.
 *
 * On leaving the composition, `state.controller` goes null, the controller is closed, and the
 * platform WebView is torn down. A controller taken from [state] must not outlive the composable:
 * calls made on it afterwards fail with `IllegalStateException`.
 *
 * [identity] is what the WebView tells sites it is, read once when the WebView is created: the
 * platform's WebView by default, or the phone's browser with [WebViewIdentity.Browser].
 */
@Composable
expect fun VitreWebView(
    state: VitreWebViewState,
    modifier: Modifier = Modifier,
    identity: WebViewIdentity = WebViewIdentity.WebView,
)
