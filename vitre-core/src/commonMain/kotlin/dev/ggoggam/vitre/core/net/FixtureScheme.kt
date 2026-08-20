package dev.ggoggam.vitre.core.net

/**
 * The private URL scheme that [RequestHandler]s answer on inside a `WKWebView`.
 *
 * iOS has no `shouldInterceptRequest`, and that is deliberate rather than an oversight —
 * `WKURLSchemeHandler` refuses to register for `http` or `https` precisely so an application
 * cannot answer for a real origin. It will happily register for a made-up one, which is enough
 * for the job that actually needs it: serving the fixture shops, whose origins are invented in the
 * first place and never resolve.
 *
 * It is **not** a proxy. Nothing here reaches the network, and a URL that no handler claims is a
 * 404 rather than a fetch. Real sites are loaded over real `https` by WebKit, which on iOS is
 * possible because a lane is a top-level document rather than a frame — see
 * `docs/PARALLEL-LANES.md`.
 *
 * The mapping keeps the host and path intact (`https://shop.test/a?b` ↔
 * `vitre-fixture://shop.test/a?b`) for two reasons: relative URLs inside a served document
 * resolve back into the scheme on their own, and each fixture host keeps a distinct origin, so the
 * shops are as cross-origin from each other here as they are on Android.
 */
object FixtureScheme {
    /**
     * Hyphenated and long-winded on purpose. WebKit throws if the scheme is one it handles itself,
     * and a short name like `app` is exactly the sort of thing another library in the same process
     * would also claim — a collision that surfaces as an unrelated `WKWebView` answering our
     * requests.
     */
    const val SCHEME: String = "vitre-fixture"

    private const val PREFIX = "$SCHEME://"
    private const val HTTPS = "https://"

    fun isFixtureUrl(url: String): Boolean = url.startsWith(PREFIX, ignoreCase = true)

    /**
     * `https://shop.test/a` → `vitre-fixture://shop.test/a`. Anything else is returned as it
     * came in, which is what leaves a real site on real `https`.
     */
    fun encode(url: String): String = if (url.startsWith(HTTPS, ignoreCase = true)) PREFIX + url.substring(HTTPS.length) else url

    /**
     * The inverse, and the form a [RequestHandler] is given — handlers match on the `https` URL the
     * workflow asked for, and must not have to know that iOS moved it.
     */
    fun decode(url: String): String = if (isFixtureUrl(url)) HTTPS + url.substring(PREFIX.length) else url
}
