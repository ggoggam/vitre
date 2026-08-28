package dev.ggoggam.vitre.core.webview

/**
 * Removes Android WebView's `wv` self-identification token from a user agent string.
 *
 * An Android WebView announces itself. Its default user agent carries a `wv` token as the last item
 * of the platform parenthetical — `(Linux; Android 16; SM-S942N Build/BP4A.251205.006; wv)` — which
 * Chrome for Android does not emit. The token was added in Android 5.0 for exactly the purpose it
 * is used for: letting a server tell an app's embedded browser from a real one.
 *
 * That distinction is reasonable for a server and ruinous for this library. A site that sees `wv`
 * stops behaving like a web site: Google Maps redirects the main frame to
 * `intent://…;package=com.google.android.apps.maps;end` whether or not the app is installed, and
 * puts a modal over the page offering the handoff. `WKWebView` carries no equivalent marker — Apple
 * never added a positive one — so the same page, driven the same way, arrives in a different state
 * on each platform and no single workflow can describe both. Stripping the token is what makes
 * Android's page the same page iOS and the desktop get.
 *
 * What this is not: a general "look like Chrome" switch. `Version/4.0` — a frozen legacy token
 * Chrome for Android also does not emit — is left in place, so a server that wants to identify a
 * WebView still can, and Google's sign-in flows fingerprint far more than the user agent. This
 * removes the one token that changes how pages *lay out and navigate*, and nothing else.
 *
 * A caller that would rather keep the default assigns its own `userAgentString` afterwards; this is
 * applied to the platform default at construction and never re-applied.
 *
 * Returns [userAgent] unchanged when the token is absent, so it is safe over an already-stripped
 * string or over a custom agent that never had one.
 */
fun withoutWebViewToken(userAgent: String): String = WEB_VIEW_TOKEN.replace(userAgent, "")

/**
 * The `wv` token together with the separator that introduces it, anchored on the `)` that closes
 * the platform parenthetical.
 *
 * Anchored rather than matched loosely because `wv` is two very common letters: unanchored, the
 * same pattern would eat the `wv` in a device's build id or model name. The lookahead is what
 * confines it to the one position the platform actually writes it in — last item before the close —
 * and the tolerated whitespace covers agents that have been through a proxy that reformatted them.
 */
private val WEB_VIEW_TOKEN = Regex(";\\s*wv\\s*(?=\\))")
