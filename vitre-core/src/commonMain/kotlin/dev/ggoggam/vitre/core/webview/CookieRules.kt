package dev.ggoggam.vitre.core.webview

/**
 * Rejects a cookie whose own text would change the shape of what it is written into.
 *
 * Not pedantry about RFC 6265's grammar. A `;` in a caller-supplied name or value is attribute
 * injection into a `Set-Cookie` header — `value; Domain=evil.example` is a cookie sent to a host
 * the caller never named — and there is no escaping in that grammar to reach for instead. The
 * platform APIs do not agree on which of these they reject: Chromium refuses most, `NSHTTPCookie`
 * accepts a name of `a=b` and stores it, so the check has to be ours to be the same on both.
 *
 * That is why this file exists at all. Everything in it is the web's rule rather than a platform's,
 * and each implementation got a different subset of them wrong on its own: Android inherited
 * validation from the `Set-Cookie` text it builds, iOS builds a property map and so had none, and a
 * test double matching more loosely than either hides the bug it was written to catch.
 */
internal fun Cookie.requireWellFormed() {
    require(name.isNotEmpty()) { "cookie name is empty" }
    require(name.none { it == ';' || it == '=' || it == ',' || it.isWhitespace() || it.isISOControl() }) {
        "cookie name contains a character that would terminate it: $name"
    }
    // The value is never quoted back in a message: it is the session token this whole API exists to
    // carry, and an exception message ends up in logcat and in crash reports.
    require(value.none { it == ';' || it == ',' || it.isISOControl() }) {
        "the value of cookie `$name` contains a character that would terminate it"
    }
    domain?.let { requireNoTerminator(it, "domain") }
    path?.let { requireNoTerminator(it, "path") }
}

private fun requireNoTerminator(
    attribute: String,
    what: String,
) = require(attribute.none { it == ';' || it.isISOControl() }) { "cookie $what contains a character that would terminate it: $attribute" }

/**
 * Rejects a [Cookie.domain] the site at [host] could not have set for itself.
 *
 * Chromium enforces this and reports it as a rejected write; WebKit's `WKHTTPCookieStore.setCookie`
 * takes no URL at all and so enforces nothing, which means a caller could plant a cookie on an
 * unrelated host — one that is then sent on every request the app makes to that host, and that
 * neither [CookieStore.read] nor [CookieStore.clear] for the URL it was written through can reach.
 * Being in-process is not what makes a host yours to speak for.
 *
 * The match is deliberately the permissive one — `example.com` covers `shop.example.com` — because
 * that is what a browser allows a site to set. Public-suffix checks are the platform's; the point
 * here is that both platforms refuse the same obviously-not-yours domain.
 */
internal fun requireDomainCovers(
    domain: String,
    host: String,
) {
    val candidate = domain.removePrefix(".").lowercase()
    val target = host.lowercase()
    require(candidate.isNotEmpty() && (target == candidate || target.endsWith(".$candidate"))) {
        "cookie domain `$domain` is not one $host may set"
    }
}

/**
 * Whether a cookie *stored* with [storedDomain] is sent to [host].
 *
 * Dot-sensitive, unlike [requireDomainCovers], because in a jar the dot is the record of how the
 * cookie was set: a `Set-Cookie` with no `Domain` is host-only and stored bare, one with a `Domain`
 * is stored with a leading dot — CFNetwork adds it even when the header did not have it. Reading
 * the bare form as a suffix match is what makes a parent's session appear to belong to a subdomain,
 * and — because deletion filters with the same predicate — what makes clearing `cdn.example.com`
 * log the workflow out of `example.com`.
 */
internal fun storedDomainMatches(
    storedDomain: String,
    host: String,
): Boolean {
    val target = host.lowercase()
    val candidate = storedDomain.removePrefix(".").lowercase()
    if (candidate.isEmpty()) return false
    return if (storedDomain.startsWith(".")) target == candidate || target.endsWith(".$candidate") else target == candidate
}

/** `/app` covers `/app` and `/app/x`, and does not cover `/apples`. A null or `/` scope covers all. */
internal fun cookiePathMatches(
    cookiePath: String?,
    requestPath: String,
): Boolean {
    if (cookiePath.isNullOrEmpty() || cookiePath == "/") return true
    if (requestPath == cookiePath) return true
    return requestPath.startsWith("${cookiePath.removeSuffix("/")}/")
}

/**
 * Whether a cookie has not expired by [nowMs].
 *
 * Needed because a jar is not obliged to prune: WebKit's `getAllCookies` keeps handing back a
 * cookie whose `expiresDate` passed while the app was running, since CFNetwork applies expiry when
 * a request asks rather than on a timer. Reporting one of those as present turns "assert the login
 * set what it claims to" into an assertion that passes over a dead session.
 */
internal fun Cookie.isLiveAt(nowMs: Long): Boolean = expiresAtMs?.let { it > nowMs } ?: true
