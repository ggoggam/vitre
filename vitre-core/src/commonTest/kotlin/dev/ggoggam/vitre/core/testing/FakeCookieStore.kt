package dev.ggoggam.vitre.core.testing

import dev.ggoggam.vitre.core.webview.Cookie
import dev.ggoggam.vitre.core.webview.CookieStore
import dev.ggoggam.vitre.core.webview.cookiePathMatches
import dev.ggoggam.vitre.core.webview.isLiveAt
import dev.ggoggam.vitre.core.webview.requireDomainCovers
import dev.ggoggam.vitre.core.webview.requireWellFormed
import dev.ggoggam.vitre.core.webview.storedDomainMatches

/**
 * An in-memory jar that behaves the way a real one does about scope: a cookie set with no `Domain`
 * belongs to that host alone, one set with a `Domain` reaches its subdomains, a `Secure` cookie is
 * not sent over `http`, an expired one is not sent at all, and a second write to the same
 * name/domain/path replaces the first.
 *
 * The matching and validation are the production ones from `CookieRules`, for the same reason
 * [FakeWebViewController] uses the production ordering: a double that is laxer than the contract is
 * where a bug in the code under test hides. It reproduces neither platform's quirks — a fake that
 * copied Android's attribute-blindness would make every test that used it a test of Android — but
 * it must not be *wrong* about the rules both platforms share.
 */
class FakeCookieStore : CookieStore {
    /**
     * Every cookie held, in write order, with the domain filled in as a jar would record it: bare
     * for a host-only cookie, dot-prefixed for one that named a `Domain`. A test can assert on the
     * jar rather than through it.
     */
    val stored = mutableListOf<Cookie>()

    /**
     * The clock [read] judges expiry against. Zero by default, so any cookie with an expiry in
     * ordinary epoch milliseconds is live and one written to expire — `expiresAtMs = 0` — is not.
     * Move it forward to age a session out.
     */
    var nowMs: Long = 0L

    override suspend fun read(url: String): List<Cookie> {
        val target = Target.of(url)
        return stored.filter { target.wouldSend(it) && it.isLiveAt(nowMs) }
    }

    override suspend fun write(
        url: String,
        cookie: Cookie,
    ) {
        val target = Target.of(url)
        cookie.requireWellFormed()
        cookie.domain?.let { requireDomainCovers(it, target.host) }
        // How a jar records the difference between the two kinds of scope, and the thing a double
        // that stored the caller's own `domain` field would lose: without it, a host-only cookie
        // reads as one that covers every subdomain.
        val scoped =
            cookie.copy(
                domain = cookie.domain?.let { ".${it.removePrefix(".")}" } ?: target.host,
                path = cookie.path ?: "/",
            )
        stored.removeAll { it.name == scoped.name && it.domain == scoped.domain && it.path == scoped.path }
        stored += scoped
    }

    override suspend fun clear(url: String) {
        val target = Target.of(url)
        stored.removeAll { target.wouldSend(it) }
    }

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

        companion object {
            /** Enough URL parsing for a test double: scheme, host, path. No ports, no queries. */
            fun of(url: String): Target {
                val scheme = url.substringBefore("://", "").lowercase()
                val afterScheme = url.substringAfter("://", url)
                val host = afterScheme.substringBefore('/').substringBefore('?')
                require(host.isNotEmpty()) { "cookies need a URL with a host: $url" }
                val path = afterScheme.removePrefix(host).substringBefore('?').substringBefore('#')
                return Target(host = host.lowercase(), path = path.ifEmpty { "/" }, secureScheme = scheme == "https")
            }
        }
    }
}
