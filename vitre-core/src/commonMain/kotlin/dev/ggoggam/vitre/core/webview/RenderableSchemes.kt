package dev.ggoggam.vitre.core.webview

/**
 * The URL schemes a WebView can produce a document from, and so the ones a page is allowed to
 * navigate *itself* to.
 *
 * A page that wants to hand off to a native app navigates the main frame to `intent://…`,
 * `market://`, `comgooglemaps://`, or a vendor's own scheme. A browser turns that into an app
 * launch; a bare WebView has no such rule, so it tries to fetch the URL and fails — and because
 * that is a main-frame failure, it takes the navigation, and the workflow, down with it. Refusing
 * the navigation instead leaves the current document in place, which is the outcome a workflow
 * wants: the handoff was the page's idea, not the caller's, and the automation is here to drive the
 * web page rather than to leave for an app.
 *
 * Deciding by what the WebView can *render*, rather than by blocklisting the schemes seen so far,
 * is the part worth keeping: an app scheme this list has never heard of is refused for the same
 * reason `intent` is. `about`, `data`, `blob` and `file` are here because the library itself
 * navigates to them — `about:blank` is where a hosted WebView starts, and `loadHtml` gives a
 * document a `data:` or custom base URL to run relative URLs against.
 *
 * Shared by both platforms so the two cannot drift: Android enforces it in
 * `shouldOverrideUrlLoading`, iOS in `decidePolicyForNavigationAction`.
 */
internal val RENDERABLE_SCHEMES = setOf("http", "https", "about", "data", "blob", "file")

/** Whether a page-initiated navigation to [scheme] is one the WebView could render. */
internal fun isRenderableScheme(scheme: String?): Boolean = scheme?.lowercase() in RENDERABLE_SCHEMES
