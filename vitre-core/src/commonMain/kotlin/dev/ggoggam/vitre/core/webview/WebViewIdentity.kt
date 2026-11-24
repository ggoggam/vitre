package dev.ggoggam.vitre.core.webview

/**
 * What a vitre WebView tells the sites it loads it is.
 *
 * [WebView] is the default and what vitre has always done: the platform's own WebView, with only
 * Android's `wv` token taken out (see [withoutWebViewToken] for why that one token and no other).
 * A server that wants to tell an app's WebView from a browser still can.
 *
 * [Browser] goes the rest of the way, for a host that wants its WebViews served the page the
 * phone's browser is served. It is the phone's own browser as far as the user agent goes:
 *
 *  - **Android:** Chrome for Android's agent ([chromeOnAndroid]), and "Google Chrome" in place of
 *    "Android WebView" in the client hints — the `Sec-CH-UA` header and `navigator.userAgentData`,
 *    which a site reads as readily as the agent. A WebView too old to have its client hints set
 *    keeps that brand.
 *  - **iOS:** Safari's `Version/… Safari/604.1` after WebKit's part of the agent
 *    ([safariApplicationName]), where `WKWebView` stops at `Mobile/15E148`.
 *  - **Desktop:** unchanged. CEF sends Chromium's own agent, which is a desktop browser's already;
 *    its client hints say "Chromium" with no "Google Chrome" beside it, as Chromium builds do.
 *
 * What it cannot reach is stated rather than implied: Android WebView still sends
 * `X-Requested-With` with the app's package on every request. androidx.webkit's allow-list for that
 * header is deprecated and changed nothing on WebView 134 (checked 2026-11-24), and no other API
 * touches it. Nor is this anything beyond the user agent — a site that fingerprints the JS
 * environment (`window.webkit.messageHandlers`, the bridge vitre installs) can still tell.
 */
enum class WebViewIdentity {
    WebView,
    Browser,
}
