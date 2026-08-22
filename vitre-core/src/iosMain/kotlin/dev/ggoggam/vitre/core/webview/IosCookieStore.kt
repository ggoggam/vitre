package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.concurrent.WebViewDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSameSiteLax
import platform.Foundation.NSHTTPCookieSameSitePolicy
import platform.Foundation.NSHTTPCookieSameSiteStrict
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKHTTPCookieStore
import kotlin.coroutines.resume

/**
 * The cookie jar behind a `WKWebsiteDataStore`, which is the one place WebKit keeps a session.
 *
 * Unlike Android's, this one is genuinely scoped — to the data store, not to the WebView — and the
 * pool hands every lane `WKWebsiteDataStore.defaultDataStore()` on purpose, so in practice the
 * scope is again "the app". Constructed from the store rather than reaching for the default here,
 * so a host that gives its WebView an ephemeral data store gets that store's cookies rather than
 * quietly reading someone else's.
 *
 * Confined to the main thread like every other WebKit call: `WKHTTPCookieStore` is part of the
 * data-store API, and touching it from a background dispatcher is the same undefined behaviour
 * `WebViewDispatcher` exists to rule out.
 */
internal class IosCookieStore(
    private val store: WKHTTPCookieStore,
) : CookieStore {
    override suspend fun read(url: String): List<Cookie> {
        val target = targetOf(url)
        return withContext(WebViewDispatcher) {
            val now = nowMs()
            allCookies().map { it.toCookie() }.filter { target.wouldSend(it) && it.isLiveAt(now) }
        }
    }

    override suspend fun write(
        url: String,
        cookie: Cookie,
    ) {
        val target = targetOf(url)
        // Both checks are this library's own. `WKHTTPCookieStore.setCookie` takes no URL, so WebKit
        // has nothing to validate against and accepts a cookie for any host at all — see
        // requireDomainCovers — and `NSHTTPCookie` will happily store a name of `a=b`, which a
        // server then reads as a different cookie entirely.
        cookie.requireWellFormed()
        cookie.domain?.let { requireDomainCovers(it, target.host) }
        val properties: MutableMap<Any?, Any?> =
            mutableMapOf(
                NSHTTPCookieName to cookie.name,
                NSHTTPCookieValue to cookie.value,
                // Both are required: `cookieWithProperties` returns nil without them rather than
                // filling in anything from context, since it has no request to take context from.
                NSHTTPCookieDomain to (cookie.domain ?: target.host),
                NSHTTPCookiePath to (cookie.path ?: "/"),
            )
        // Any non-empty value means secure; the key's presence is what is read, not what it says.
        if (cookie.secure == true) properties[NSHTTPCookieSecure] = "TRUE"
        cookie.expiresAtMs?.let { properties[NSHTTPCookieExpires] = NSDate.dateWithTimeIntervalSince1970(it / 1000.0) }
        // Lax and Strict have property values; None does not exist in this API, so it is dropped
        // and WebKit applies its own default. See SameSite, which documents that asymmetry.
        when (cookie.sameSite) {
            SameSite.Lax -> properties[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteLax
            SameSite.Strict -> properties[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteStrict
            SameSite.None, null -> Unit
        }
        // `httpOnly` is deliberately not here — see CookieStore.write. There is no public property
        // key for it, and inventing one silently produces a cookie without the flag either way.
        val native =
            NSHTTPCookie.cookieWithProperties(properties)
                ?: throw IllegalArgumentException("WebKit rejected the cookie `${cookie.name}` for $url")
        withContext(WebViewDispatcher) {
            suspendCancellableCoroutine { continuation ->
                store.setCookie(native) { continuation.resume(Unit) }
            }
        }
    }

    override suspend fun clear(url: String) {
        val target = targetOf(url)
        withContext(WebViewDispatcher) {
            // Precise here, unlike Android: the store hands back whole cookies, so the ones that
            // match can be deleted as themselves — path, domain and all — rather than expired by a
            // guess at how they were scoped. An expired-but-unpruned cookie is deleted too; it is
            // one this URL owns, and leaving it would keep it in the jar indefinitely.
            allCookies()
                .filter { target.wouldSend(it.toCookie()) }
                .forEach { native ->
                    suspendCancellableCoroutine { continuation ->
                        store.deleteCookie(native) { continuation.resume(Unit) }
                    }
                }
        }
    }

    private suspend fun allCookies(): List<NSHTTPCookie> =
        suspendCancellableCoroutine { continuation ->
            store.getAllCookies { cookies -> continuation.resume(cookies.orEmpty().filterIsInstance<NSHTTPCookie>()) }
        }

    private fun targetOf(url: String): Target {
        val parsed = NSURL.URLWithString(url)
        val host = parsed?.host
        require(!host.isNullOrEmpty()) { "cookies need a URL with a host: $url" }
        return Target(
            host = host,
            path = parsed.path?.takeIf { it.isNotEmpty() } ?: "/",
            secureScheme = parsed.scheme?.lowercase() == "https",
        )
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    /**
     * What a request to one URL would send, spelled out rather than delegated.
     *
     * `NSHTTPCookieStorage` has `cookiesForURL:`; `WKHTTPCookieStore` — the one that actually holds
     * a WebView's session — has only "all of them", so the match is ours to make. The rules it
     * applies are in `CookieRules`, shared with the test double so the two cannot disagree about
     * what a jar would have sent.
     */
    private data class Target(
        val host: String,
        val path: String,
        val secureScheme: Boolean,
    ) {
        fun wouldSend(cookie: Cookie): Boolean {
            if (cookie.secure == true && !secureScheme) return false
            val domain = cookie.domain ?: return false
            return storedDomainMatches(domain, host) && cookiePathMatches(cookie.path, path)
        }
    }
}

private fun NSHTTPCookie.toCookie(): Cookie =
    Cookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = HTTPOnly,
        sameSite =
            when (sameSitePolicy) {
                NSHTTPCookieSameSiteLax -> SameSite.Lax

                NSHTTPCookieSameSiteStrict -> SameSite.Strict

                // Null rather than SameSite.None: WebKit reports nothing both for a cookie that
                // said `None` and for one that said nothing at all, and null is this library's
                // word for "the platform did not tell us".
                else -> null
            },
        // Session cookies have no expiry date, which is the null this field already means.
        expiresAtMs = expiresDate?.timeIntervalSince1970?.let { (it * 1000).toLong() },
    )
