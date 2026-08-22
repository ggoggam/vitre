package dev.ggoggam.vitre.core.webview

/**
 * One cookie, in the only shape all three platforms can honestly describe.
 *
 * Every field but [name] and [value] is nullable, and null means the same thing in both
 * directions: *nothing was said*. On a [CookieStore.write] that is "unset — take the default"
 * (host-only [domain], `/` [path], not [secure], not [httpOnly], the platform's own [sameSite]
 * rule, and a session cookie with no [expiresAtMs]). On a [CookieStore.read] it is "the platform
 * did not tell us", which is not the same claim as `false` and must not be collapsed into one —
 * see [CookieStore.read] for the platform that cannot tell us.
 *
 * The `/` default for [path] is this library's, not the web's: a server that omits `Path` gets RFC
 * 6265's default-path, which is the *directory of the request*, so a session written against
 * `/account/login` would be invisible at `/`. A caller injecting a session wants it site-wide, and
 * one platform quietly disagreeing about which page it applies to is the failure mode this whole
 * type exists to avoid. Pass [path] explicitly for anything narrower.
 *
 * [expiresAtMs] is Unix epoch milliseconds. Null is a session cookie: one that lives until the
 * cookie jar is torn down rather than until a date.
 */
data class Cookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val secure: Boolean? = null,
    val httpOnly: Boolean? = null,
    val sameSite: SameSite? = null,
    val expiresAtMs: Long? = null,
)

/**
 * When a cookie rides along on a request that another site started.
 *
 * Worth carrying rather than leaving to the default, because the default is not the same
 * everywhere and the symptom of getting it wrong is an SSO bounce that lands logged out: an
 * identity provider's callback is a cross-site `POST`, and a session cookie that is not [None]
 * is not sent on it.
 *
 * [None] is expressible on Android only. Apple's `NSHTTPCookie` has property values for Lax and
 * Strict and none for None, so a cookie written on iOS carries WebKit's own default instead, and a
 * cookie *read* there reports null where Android would report [None]. Chromium also rejects
 * `SameSite=None` on a cookie that is not [Cookie.secure], so the two go together.
 */
enum class SameSite {
    Strict,
    Lax,
    None,
}

/**
 * The cookie jar behind a [WebViewController] — the session, rather than the page.
 *
 * This exists because `document.cookie` cannot stand in for it. A `HttpOnly` cookie is invisible to
 * page script by design, and on every site worth automating that is exactly the cookie the session
 * is kept in, so a workflow that logs in once and expects to reuse the session has no way to carry
 * it, save it, or clear it through [WebViewController.evaluateJs]. That is what this is for:
 * arriving already authenticated, resetting state between runs, and asserting that a login actually
 * set what it claims to.
 *
 * ### What it is scoped to
 *
 * Not the WebView, whatever the property it hangs off suggests. It is the jar of whichever store
 * the WebView was given, and both pools give every lane the shared one on purpose —
 * `WKWebsiteDataStore.defaultDataStore()` on iOS, the process-wide `CookieManager` on Android —
 * because a lane that logs in and a lane that then reads the account are frequently the same
 * workflow twice (`docs/PARALLEL-LANES.md`). So in a pool a [write] is visible to every lane, and
 * on Android to the host app's own WebViews as well: that jar belongs to the process rather than to
 * this library. The one arrangement where the jars are genuinely separate is a host that builds its
 * own iOS WebViews with `nonPersistentDataStore()`, which is a decision to keep sessions apart, and
 * this reports each store's own cookies rather than overriding it.
 *
 * That process-wide reach is also why there is no clear-everything call. [clear] is scoped to a
 * URL, and a caller that really wants the whole jar gone can reach for the platform API itself,
 * having decided it is allowed to.
 *
 * ### What it is not ordered against
 *
 * Anything. Cookie calls do not take the WebView's lock: they operate on the jar rather than on the
 * document, and a read that queued behind a slow navigation would be waiting on something it has no
 * relationship with. Nor would taking it help — [WebViewController.exclusively] claims one
 * controller, and on Android the jar behind every controller is the same one, so no lock this
 * library could take would make "write, then navigate" indivisible against a second writer. A
 * caller that needs that guarantee has to be the only writer, and arrange it above this API.
 *
 * The jar also outlives [WebViewController.close]: closing takes this library's bridge back off a
 * WebView, which has nothing to do with whether the device is still logged in.
 */
interface CookieStore {
    /**
     * Every cookie that would be sent with a request to [url] — matched on host, path, scheme and
     * expiry, as a request would match them.
     *
     * **Android reports name and value only.** `CookieManager.getCookie` hands back the `Cookie`
     * request header — `"a=1; b=2"` — which is what a server receives and carries no attributes at
     * all, so [Cookie.domain], [Cookie.path], [Cookie.secure], [Cookie.httpOnly], [Cookie.sameSite]
     * and [Cookie.expiresAtMs] all come back null there. Null is the honest answer; reporting
     * `secure = false` for a cookie we simply cannot see the flag of would be a silent lie, and the
     * silent platform disagreement is the failure this library spends most of its comments
     * avoiding. iOS answers with the full record, minus the `SameSite=None` it cannot express.
     *
     * Anything a caller does with the result should therefore key on name and value, and treat the
     * attributes as diagnostics.
     *
     * @throws IllegalArgumentException if [url] is not a URL the platform can resolve a host from.
     */
    suspend fun read(url: String): List<Cookie>

    /**
     * Writes [cookie] into the jar as if the server for [url] had sent it in a `Set-Cookie`.
     *
     * A [Cookie.domain] the site could not have set itself is refused, on both platforms and for
     * the same reason a browser refuses it: nothing about being in-process makes `evil.example` a
     * host this caller speaks for, and a cookie planted there is one neither [read] nor [clear] for
     * this URL could ever reach again.
     *
     * The write is asked to persist rather than left to the platform's own schedule. Android
     * buffers cookies in memory and writes them back when it sees fit, so a workflow that injects a
     * session and then has the process die comes back logged out for reasons nothing in the log
     * explains; `flush` is requested here to narrow that window, though nothing in that API waits
     * for the disk.
     *
     * **iOS ignores [Cookie.httpOnly] on a write.** `NSHTTPCookie` has no public property key for
     * it, so the cookie is stored without the flag and page script can read it. It is still sent on
     * requests, which is what a caller injecting a session actually needs; what changes is only
     * whether the page can see it. See [SameSite] for the other attribute iOS narrows.
     *
     * @throws IllegalArgumentException if [url] has no host, if [Cookie.domain] does not cover that
     *   host, or if any field contains a character that would terminate it and let the rest be read
     *   as attributes.
     */
    suspend fun write(
        url: String,
        cookie: Cookie,
    )

    /**
     * Removes the cookies [read] would return for [url]. Logging out, or resetting between runs.
     *
     * **Android cannot do this precisely.** Its jar exposes no per-cookie removal, and the header it
     * answers with carries neither paths nor domains, so a deletion has to be spelled as an expiry
     * aimed at a guess: path `/`, and each domain the URL could plausibly have been scoped to.
     * That covers a site session; a cookie scoped to a deeper path than `/` survives, and there is
     * no API that would let us find out that it did. A caller that must be sure is better served by
     * reading back afterwards than by trusting this to have been exhaustive.
     *
     * iOS deletes what it matched, exactly, because it can see whole cookies.
     *
     * @throws IllegalArgumentException if [url] has no host.
     */
    suspend fun clear(url: String)
}
