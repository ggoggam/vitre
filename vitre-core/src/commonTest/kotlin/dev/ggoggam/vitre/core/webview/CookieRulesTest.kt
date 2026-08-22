package dev.ggoggam.vitre.core.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules both stores share. They are here rather than in a platform test because the failures
 * they describe are the ones each implementation reached independently and got differently wrong.
 */
class CookieRulesTest {
    @Test
    fun `a value cannot terminate its own field`() {
        assertFailsWith<IllegalArgumentException> { Cookie(name = "session", value = "abc; Domain=evil.example").requireWellFormed() }
    }

    @Test
    fun `a name cannot smuggle in a value`() {
        assertFailsWith<IllegalArgumentException> { Cookie(name = "a=1; b", value = "2").requireWellFormed() }
    }

    @Test
    fun `a domain cannot smuggle in an attribute`() {
        assertFailsWith<IllegalArgumentException> { Cookie(name = "a", value = "1", domain = "example.com; Secure").requireWellFormed() }
    }

    @Test
    fun `an empty name is rejected`() {
        assertFailsWith<IllegalArgumentException> { Cookie(name = "", value = "1").requireWellFormed() }
    }

    /** The token is what this API carries; it must not end up in a message that reaches a log. */
    @Test
    fun `a rejection does not quote the value back`() {
        val thrown = assertFailsWith<IllegalArgumentException> { Cookie(name = "session", value = "secret;").requireWellFormed() }
        assertFalse(thrown.message.orEmpty().contains("secret"), thrown.message.orEmpty())
    }

    @Test
    fun `a site may set a cookie on itself and on its parent`() {
        requireDomainCovers("shop.example.com", "shop.example.com")
        requireDomainCovers("example.com", "shop.example.com")
        requireDomainCovers(".example.com", "shop.example.com")
    }

    @Test
    fun `a site may not set a cookie on an unrelated host`() {
        assertFailsWith<IllegalArgumentException> { requireDomainCovers("evil.example", "shop.example") }
        // A suffix that is not a domain boundary is not a parent, whatever the string says.
        assertFailsWith<IllegalArgumentException> { requireDomainCovers("ample.com", "example.com") }
    }

    /**
     * The distinction a jar records with the leading dot, and the one whose loss makes a parent's
     * session look like a subdomain's — and makes clearing the subdomain delete the parent's.
     */
    @Test
    fun `a host-only cookie is not sent to a subdomain and a domain cookie is`() {
        assertTrue(storedDomainMatches("example.com", "example.com"))
        assertFalse(storedDomainMatches("example.com", "cdn.example.com"))
        assertTrue(storedDomainMatches(".example.com", "cdn.example.com"))
        assertFalse(storedDomainMatches(".example.com", "notexample.com"))
    }

    @Test
    fun `a path scope covers what is under it and nothing that merely starts the same`() {
        assertTrue(cookiePathMatches(null, "/anything"))
        assertTrue(cookiePathMatches("/", "/anything"))
        assertTrue(cookiePathMatches("/app", "/app"))
        assertTrue(cookiePathMatches("/app", "/app/settings"))
        assertFalse(cookiePathMatches("/app", "/apples"))
    }

    /** A jar that has not pruned yet still holds it; a request would not send it. */
    @Test
    fun `an expired cookie is not live and a session cookie always is`() {
        assertEquals(false, Cookie(name = "a", value = "1", expiresAtMs = 500).isLiveAt(1_000))
        assertEquals(true, Cookie(name = "a", value = "1", expiresAtMs = 1_500).isLiveAt(1_000))
        assertEquals(true, Cookie(name = "a", value = "1").isLiveAt(1_000))
    }
}
