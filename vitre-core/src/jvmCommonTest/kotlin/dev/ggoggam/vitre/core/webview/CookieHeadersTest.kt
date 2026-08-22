package dev.ggoggam.vitre.core.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `Set-Cookie` text Android's jar is driven through, which nothing else can check: the platform
 * takes a string, accepts a malformed one, and reports the result as a cookie that is simply not
 * there afterwards.
 *
 * In `jvmCommonTest` rather than `jvmTest` so it runs on the Android host compilation too — the
 * platform this text is actually built for, and the one `jvmTest` does not cover.
 */
class CookieHeadersTest {
    private val now = 1_700_000_000_000L

    /** `Path` is not optional here even when the caller said nothing — see [toSetCookieHeader]. */
    @Test
    fun `a bare cookie still carries the root path`() {
        assertEquals("session=abc; Path=/", Cookie(name = "session", value = "abc").toSetCookieHeader(now))
    }

    @Test
    fun `every attribute that was set is written`() {
        val cookie =
            Cookie(
                name = "session",
                value = "abc",
                domain = "example.com",
                path = "/app",
                secure = true,
                httpOnly = true,
                sameSite = SameSite.None,
                expiresAtMs = now + 60_000,
            )
        assertEquals(
            "session=abc; Domain=example.com; Path=/app; Max-Age=60; Secure; HttpOnly; SameSite=None",
            cookie.toSetCookieHeader(now),
        )
    }

    /** Null is "unset", so the flag is absent rather than written as a negative. */
    @Test
    fun `an unset attribute is left out entirely`() {
        assertEquals("a=1; Path=/", Cookie(name = "a", value = "1", secure = null, httpOnly = false).toSetCookieHeader(now))
    }

    /** What [CookieStore.clear] relies on: an expiry already past is a deletion, not a long life. */
    @Test
    fun `an expiry in the past becomes Max-Age zero`() {
        val header = Cookie(name = "a", value = "", expiresAtMs = now - 5_000).toSetCookieHeader(now)
        assertTrue(header.contains("; Max-Age=0"), header)
    }

    /**
     * The boundary truncation would get wrong. Rounding down turns a cookie half a second from
     * expiry into `Max-Age=0`, which the jar reads as "delete", so `write` would create nothing and
     * still report success.
     */
    @Test
    fun `an expiry under a second away is still a live cookie`() {
        val header = Cookie(name = "a", value = "1", expiresAtMs = now + 500).toSetCookieHeader(now)
        assertTrue(header.contains("; Max-Age=1"), header)
    }

    @Test
    fun `a value cannot smuggle in an attribute`() {
        val cookie = Cookie(name = "session", value = "abc; Domain=evil.example")
        assertFailsWith<IllegalArgumentException> { cookie.toSetCookieHeader(now) }
    }

    @Test
    fun `a request header parses to names and values with no attributes`() {
        val parsed = parseCookieHeader("session=abc; theme=dark")
        assertEquals(listOf("session" to "abc", "theme" to "dark"), parsed.map { it.name to it.value })
        assertTrue(parsed.all { it.domain == null && it.path == null && it.secure == null && it.httpOnly == null })
    }

    /** Base64 and JWTs are full of `=`, and only the first one separates a cookie. */
    @Test
    fun `only the first equals separates name from value`() {
        assertEquals("a=b==", parseCookieHeader("token=a=b==").single().value)
    }

    @Test
    fun `a pair with no equals is dropped rather than guessed at`() {
        assertEquals(listOf("b"), parseCookieHeader("a; b=2").map { it.name })
    }

    @Test
    fun `an empty header has no cookies`() {
        assertEquals(emptyList(), parseCookieHeader(""))
    }
}
