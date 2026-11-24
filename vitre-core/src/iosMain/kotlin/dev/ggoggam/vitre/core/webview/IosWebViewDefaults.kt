package dev.ggoggam.vitre.core.webview

import platform.UIKit.UIDevice
import platform.WebKit.WKWebViewConfiguration

/**
 * What a `WKWebView` built from this configuration tells sites it is. See [WebViewIdentity].
 *
 * Only [WebViewIdentity.Browser] changes anything: `WKWebView` has no `wv` token to take out, so
 * the default identity is WebKit's own agent. Applied by both hosts — the composable in
 * `vitre-compose` and every lane in `IosWebViewPool` — and it has to be applied before the
 * `WKWebView` is created, since WebKit copies the configuration then and reads it no more.
 */
fun WKWebViewConfiguration.applyVitreIdentity(identity: WebViewIdentity) {
    if (identity == WebViewIdentity.Browser) {
        applicationNameForUserAgent = safariApplicationName(UIDevice.currentDevice.systemVersion)
    }
}
