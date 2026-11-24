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
 * removes the one token that changes how pages *lay out and navigate*, and nothing else. A host
 * that does want Chrome's agent asks for [WebViewIdentity.Browser], which is [chromeOnAndroid].
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

/**
 * Android WebView's user agent as Chrome for Android sends it: [WebViewIdentity.Browser]'s agent.
 *
 * A WebView's is `Mozilla/5.0 (Linux; Android 16; SM-A235N Build/BP2A.250605.031.A3; wv)
 * AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.46 Mobile Safari/537.36`.
 * Chrome has sent the reduced form for years — `(Linux; Android 10; K)` and `Chrome/158.0.0.0`,
 * with the model, the real Android version and the full Chrome version only to a site that asks
 * through client hints — and has never sent `Version/4.0`. The `wv`, the `Version/4.0`, the build
 * id and the full version are each a WebView's alone, so all of them go, and only the major
 * version is carried across.
 *
 * Returns [userAgent] unchanged when it is not an Android agent with a Chrome version in it, so a
 * custom agent a host set is not replaced by a guess.
 */
fun chromeOnAndroid(userAgent: String): String {
    if (!userAgent.startsWith("Mozilla/5.0 (Linux; Android")) return userAgent
    val major = CHROME_VERSION.find(userAgent)?.groupValues?.get(1) ?: return userAgent
    return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
}

/**
 * What a `WKWebView` is given as its `applicationNameForUserAgent` to end its agent as Safari's:
 * [WebViewIdentity.Browser] on iOS.
 *
 * WebKit writes the start of the agent itself and appends this, which defaults to `Mobile/15E148`.
 * Safari's ends `Version/26.1 Mobile/15E148 Safari/604.1`, its version being the system's, and the
 * missing `Safari/` is how a site tells an app's WebView from the browser at a glance. A
 * [systemVersion] that cannot be read gives `18.0`, the last version before Safari's tracked the
 * system's year.
 */
fun safariApplicationName(systemVersion: String): String {
    val parts = systemVersion.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return safariTail("18.0")
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return safariTail("$major.$minor")
}

/** The brand Chrome puts in its client hints where Android WebView puts [ANDROID_WEBVIEW_BRAND]. */
const val CHROME_BRAND: String = "Google Chrome"
const val ANDROID_WEBVIEW_BRAND: String = "Android WebView"

private fun safariTail(version: String) = "Version/$version Mobile/15E148 Safari/604.1"

private val CHROME_VERSION = Regex("""\bChrome/(\d+)\.""")
