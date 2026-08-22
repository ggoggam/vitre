package dev.ggoggam.vitre.core.webview

import android.net.Uri
import android.webkit.CookieManager
import dev.ggoggam.vitre.core.concurrent.WebViewDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android's cookie jar, which is one jar for the whole process.
 *
 * An object rather than a class because `CookieManager.getInstance()` is a process-wide singleton:
 * there is nothing per-WebView to hold, and pretending otherwise by taking a `WebView` in the
 * constructor would suggest a scoping this platform does not have. See [CookieStore] for what that
 * means for a caller — a write here is visible to every lane and to the host app's own WebViews.
 *
 * Calls that touch the jar are confined to the WebView thread. Not because `CookieManager` needs it
 * — it is thread-safe — but because the async form of `setCookie` delivers its result through a
 * `Handler` built from the calling thread's `Looper`, and a caller arriving on `Dispatchers.Default`
 * has none. That failure is an `IllegalStateException` thrown from inside the platform, which reads
 * as a library bug rather than as the dispatcher mismatch it is.
 */
internal object AndroidCookieStore : CookieStore {
    override suspend fun read(url: String): List<Cookie> {
        requireHost(url)
        return withContext(WebViewDispatcher) {
            CookieManager
                .getInstance()
                .getCookie(url)
                ?.let(::parseCookieHeader)
                .orEmpty()
        }
    }

    override suspend fun write(
        url: String,
        cookie: Cookie,
    ) {
        val host = requireHost(url)
        cookie.domain?.let { requireDomainCovers(it, host) }
        // Built before the dispatcher switch so a malformed cookie is rejected on the caller's
        // thread, with the caller's stack.
        val header = cookie.toSetCookieHeader(System.currentTimeMillis())
        val accepted = withContext(WebViewDispatcher) { CookieManager.getInstance().setCookieAwaiting(url, header) }
        // The value is left out on purpose: it is a session token, and this message reaches logcat.
        require(accepted) { "the platform rejected the cookie `${cookie.name}` for $url" }
        flush()
    }

    override suspend fun clear(url: String) {
        val host = requireHost(url)
        // Uncancellable as a whole. Every expiry below is a suspension point, and a cancelled clear
        // that had got halfway would leave a jar holding some of a session and none of the flush,
        // which the next `read` reports as a login that is simply strange rather than absent.
        withContext(NonCancellable) {
            withContext(WebViewDispatcher) {
                val manager = CookieManager.getInstance()
                val now = System.currentTimeMillis()
                val names =
                    manager
                        .getCookie(url)
                        ?.let(::parseCookieHeader)
                        .orEmpty()
                        .map { it.name }
                for (name in names) {
                    for (domain in domainsToTry(host)) {
                        // A name the platform accepted but this library will not build a header for
                        // is left alone rather than allowed to abort the rest of the deletion.
                        runCatching { manager.setCookieAwaiting(url, expiryFor(name, domain, url, now)) }
                    }
                }
            }
            flush()
        }
    }

    /**
     * The deletion for one name, aimed at one candidate domain.
     *
     * `Secure` is set whenever the URL is one — not for protection, but because `__Secure-` and
     * `__Host-` prefixed names *require* it: Chromium's prefix check rejects a `Set-Cookie` that
     * lacks it outright, so without this the deletion of exactly the cookies most likely to hold a
     * real session is refused, and refused silently. It costs nothing on an ordinary name, since
     * `Secure` is not part of the key a cookie is stored under.
     */
    private fun expiryFor(
        name: String,
        domain: String?,
        url: String,
        nowMs: Long,
    ): String =
        Cookie(
            name = name,
            value = "",
            domain = domain,
            path = "/",
            secure = url.startsWith("https://", ignoreCase = true),
            expiresAtMs = 0L,
        ).toSetCookieHeader(nowMs)

    /**
     * Host-only first, then every domain the cookie could have been scoped to.
     *
     * The header the jar answers with carries no domains, so which of these a cookie was stored
     * under is exactly the thing that cannot be known — and Chromium keys a `Domain=` cookie
     * separately from a host-only one of the same name, so an expiry aimed at the wrong one deletes
     * nothing while reporting success. Trying each is the only way to cover a session cookie set on
     * a parent domain. A candidate the URL may not set — a public suffix, most obviously — is
     * rejected by the platform, which is why the results are not read: for any given name, most of
     * these are *expected* to fail.
     *
     * Stops at two labels, since nothing shorter is a domain a site is allowed to write to.
     */
    private fun domainsToTry(host: String): List<String?> {
        val labels = host.split('.')
        val parents = (0..(labels.size - 2)).map { labels.drop(it).joinToString(".") }
        return listOf(null) + parents
    }

    /** True if the jar took the cookie. The async form is the only one that reports that at all. */
    private suspend fun CookieManager.setCookieAwaiting(
        url: String,
        header: String,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            setCookie(url, header) { accepted -> continuation.resume(accepted == true) }
        }

    /**
     * Asks for the jar to be written back to disk, off the WebView thread.
     *
     * Off it deliberately: the platform documents `flush` as blocking the caller and performing I/O,
     * and the WebView thread is the UI thread. A cookie write that stalls a frame to be a little
     * more durable is a bad trade, and an invisible one until someone profiles a login.
     */
    private suspend fun flush() = withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }

    private fun requireHost(url: String): String {
        val host = Uri.parse(url).host
        require(!host.isNullOrEmpty()) { "cookies need a URL with a host: $url" }
        return host
    }
}
