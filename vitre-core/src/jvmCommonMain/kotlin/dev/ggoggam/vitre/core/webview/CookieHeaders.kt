package dev.ggoggam.vitre.core.webview

/**
 * The `Set-Cookie` value for [this], as a server would have sent it.
 *
 * Android's jar is written through `CookieManager.setCookie(url, value)`, which takes exactly this
 * — the platform has no structured cookie type to hand it — and the desktop's interception jar
 * stores `Set-Cookie` strings too (`HttpResourceFetcher`'s `CookieJar`), so this lives in the
 * source set both share rather than in the leaf that needed it first.
 *
 * Two departures from writing out only what the caller set:
 *
 * `Path` is always written. Omitting it does not mean "the whole site", it means RFC 6265's
 * default-path — the directory of the request — so a session written through
 * `https://shop.example/account/login` would apply to `/account` and be missing from the site root.
 * [Cookie] documents null as `/`; this is where that promise is kept, and where it stops Android
 * from disagreeing with iOS about which pages a workflow's session covers.
 *
 * Expiry is written as `Max-Age` rather than `Expires`. `Expires` is an RFC 1123 date, which means
 * a formatter, a locale and a time zone — three things that have to be right in exactly one way and
 * fail as a cookie the browser quietly discards. `Max-Age` is an integer, both platforms' parsers
 * prefer it over `Expires` anyway, and a time already past becomes `Max-Age=0`, which is a
 * deletion — precisely what [CookieStore.clear] wants. It rounds *up*, because the arithmetic that
 * feels natural here truncates, and truncation turns an expiry 500ms out into the deletion of a
 * cookie the caller had just asked to create.
 *
 * [nowMs] is passed in rather than read here so the conversion is testable.
 */
internal fun Cookie.toSetCookieHeader(nowMs: Long): String {
    requireWellFormed()
    return buildString {
        append(name)
        append('=')
        append(value)
        domain?.let { append("; Domain=").append(it) }
        append("; Path=").append(path ?: "/")
        expiresAtMs?.let { append("; Max-Age=").append(secondsUntil(it, nowMs)) }
        if (secure == true) append("; Secure")
        if (httpOnly == true) append("; HttpOnly")
        sameSite?.let { append("; SameSite=").append(it.name) }
    }
}

/** Rounds up, so that only an expiry that has genuinely passed reads as a deletion. */
private fun secondsUntil(
    expiresAtMs: Long,
    nowMs: Long,
): Long {
    val remaining = expiresAtMs - nowMs
    if (remaining <= 0) return 0
    return (remaining + 999) / 1000
}

/**
 * The cookies in a `Cookie` *request* header — `"a=1; b=2"` — which is all Android's jar returns.
 *
 * Attributes are absent from that header by definition, so every cookie here carries name and value
 * and nulls for the rest. See [CookieStore.read] for why they are not defaulted to `false`.
 *
 * A pair with no `=` is dropped rather than read as a valueless cookie: Chromium does emit bare
 * names in some legacy cases, and guessing which side of the missing `=` was meant is how a value
 * ends up stored as a name.
 */
internal fun parseCookieHeader(header: String): List<Cookie> =
    header
        .split(';')
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = pair.substring(0, separator).trim()
            if (name.isEmpty()) return@mapNotNull null
            Cookie(name = name, value = pair.substring(separator + 1).trim())
        }
