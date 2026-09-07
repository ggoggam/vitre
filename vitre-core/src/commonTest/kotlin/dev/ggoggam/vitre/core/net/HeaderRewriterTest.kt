package dev.ggoggam.vitre.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The header surgery that makes a lane possible, tested without a WebView.
 *
 * Every case here corresponds to a way a lane fails silently on a device: a header wrongly removed
 * means a page that behaves differently from the one a user would see, and a `connect-src` left in
 * place means a fetch the CORS headers had already allowed still coming back blocked.
 */
class HeaderRewriterTest {
    /**
     * The preset, not the default. Rewriting is what this class does, and a default-constructed
     * policy deliberately does none of it — which the last test here is what holds in place.
     */
    private val policy = InterceptionPolicy.AUTOMATION

    @Test
    fun `leaves the framing headers alone because a lane is a top-level document`() {
        // These answer "who may frame this page", and nobody is framing it. Removing them was what
        // the iframe arrangement needed, and it re-enabled clickjacking against the site to get it.
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers =
                    mapOf(
                        "X-Frame-Options" to "DENY",
                        "Content-Security-Policy" to "frame-ancestors 'none'",
                        "Content-Type" to "text/html",
                    ),
                requestOrigin = null,
                policy = policy,
            )
        assertEquals("DENY", out["X-Frame-Options"])
        assertEquals("frame-ancestors 'none'", out["Content-Security-Policy"])
        assertEquals("text/html", out["Content-Type"])
    }

    @Test
    fun `leaves a script-src alone because the engine never evaluates inside the page's world`() {
        // `evaluateJavascript` (and WebKit's `evaluateJavaScript`) reach the main frame outside
        // the page's CSP. The lane runtime that did need `'unsafe-eval'` was an iframe-era thing.
        val relaxed = HeaderRewriter.relaxCsp("script-src 'self' cdn.example; img-src 'self'", allowAnyConnect = false)
        assertEquals("script-src 'self' cdn.example; img-src 'self'", relaxed)
    }

    @Test
    fun `drops a CSP header that had nothing left in it`() {
        // Not the same as an empty policy: `Content-Security-Policy: ` is a malformed header, and
        // some renderers treat a malformed policy as the most restrictive one.
        assertNull(HeaderRewriter.relaxCsp(" ; "))
    }

    @Test
    fun `widens connect-src because CORS alone does not make a fetch reach the network`() {
        // `connect-src` is the page's own opinion about where it may ask, and no amount of
        // Access-Control-Allow-Origin overrides it.
        val relaxed = HeaderRewriter.relaxCsp("img-src 'self'; connect-src 'self'")
        assertEquals("img-src 'self'; connect-src * data: blob:", relaxed)
    }

    @Test
    fun `widens a connect-src the page only implied through default-src`() {
        val relaxed = HeaderRewriter.relaxCsp("default-src 'self'")
        assertEquals("default-src 'self'; connect-src * data: blob:", relaxed)
    }

    @Test
    fun `does not invent a connect-src for a policy that never restricted one`() {
        // Writing one out where the policy had neither `connect-src` nor `default-src` would
        // *narrow* the page: the directive is unrestricted precisely by being absent.
        assertEquals("img-src 'self'", HeaderRewriter.relaxCsp("img-src 'self'"))
    }

    @Test
    fun `reflects the request origin rather than answering with a wildcard`() {
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers = emptyMap(),
                requestOrigin = "https://shop.example",
                policy = policy,
            )
        // A wildcard is invalid alongside credentials — the pair is rejected outright — so a
        // credentialed cross-origin read only works if the caller's own origin comes back.
        assertEquals("https://shop.example", out["Access-Control-Allow-Origin"])
        assertEquals("true", out["Access-Control-Allow-Credentials"])
        assertEquals("Origin", out["Vary"])
    }

    @Test
    fun `falls back to a wildcard and no credentials when there was no Origin`() {
        val out = HeaderRewriter.rewriteResponseHeaders(emptyMap(), requestOrigin = null, policy = policy)
        assertEquals("*", out["Access-Control-Allow-Origin"])
        assertNull(out["Access-Control-Allow-Credentials"])
    }

    @Test
    fun `replaces the server's own CORS answer instead of sitting beside it`() {
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers = mapOf("Access-Control-Allow-Origin" to "https://only-this-one.example"),
                requestOrigin = "https://shop.example",
                policy = policy,
            )
        assertEquals("https://shop.example", out["Access-Control-Allow-Origin"])
        assertEquals(1, out.keys.count { it.equals("access-control-allow-origin", ignoreCase = true) })
    }

    @Test
    fun `drops Content-Encoding and Content-Length from a body it has already decoded`() {
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers = mapOf("Content-Encoding" to "gzip", "Content-Length" to "4096", "ETag" to "abc"),
                requestOrigin = null,
                policy = policy,
            )
        // Leaving these on decompressed bytes makes the renderer try to gunzip plain text, and it
        // fails as a blank document with nothing logged.
        assertNull(out["Content-Encoding"])
        assertNull(out["Content-Length"])
        assertEquals("abc", out["ETag"])
    }

    @Test
    fun `does not forward Accept-Encoding so the client negotiates and decodes for itself`() {
        val out =
            HeaderRewriter.sanitizeRequestHeaders(
                mapOf("Accept-Encoding" to "gzip, deflate", "User-Agent" to "test", "Host" to "shop.example"),
            )
        assertNull(out["Accept-Encoding"])
        assertNull(out["Host"])
        assertEquals("test", out["User-Agent"])
    }

    @Test
    fun `matches header names case-insensitively because the wire does not care`() {
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers = mapOf("content-encoding" to "gzip", "access-control-allow-origin" to "https://elsewhere.example"),
                requestOrigin = "https://shop.example",
                policy = policy,
            )
        assertNull(out["content-encoding"])
        assertEquals("https://shop.example", out["Access-Control-Allow-Origin"])
        assertEquals(1, out.keys.count { it.equals("access-control-allow-origin", ignoreCase = true) })
    }

    @Test
    fun `rewrites nothing at all under a default policy`() {
        // The whole of the default's promise, in one assertion: a caller who did not ask for
        // interception gets the server's own answer, including a CSP that will block a
        // cross-origin fetch and the absence of any allowance to make one. Relaxing CORS on
        // somebody's behalf hands a page credentialed reads the site wrote its policy to refuse,
        // which is not a thing to do because a constructor was called with no arguments.
        val out =
            HeaderRewriter.rewriteResponseHeaders(
                headers =
                    mapOf(
                        "Content-Security-Policy" to "connect-src 'self'",
                        "Access-Control-Allow-Origin" to "https://only-this-one.example",
                        "Content-Type" to "text/html",
                    ),
                requestOrigin = "https://shop.example",
                policy = InterceptionPolicy(),
            )
        assertEquals("connect-src 'self'", out["Content-Security-Policy"])
        assertEquals("https://only-this-one.example", out["Access-Control-Allow-Origin"])
        assertEquals("text/html", out["Content-Type"])
        assertNull(out["Access-Control-Allow-Credentials"])
        assertNull(out["Access-Control-Allow-Methods"])
    }

    @Test
    fun `leaves the main frame to the browser under a default policy`() {
        // The two halves of removing `interceptMainFrame`. The predicate is the only switch now,
        // and it says nothing about handlers: a fixture answers a document either way, because
        // answering from memory pays none of the costs that made refetching a document a decision.
        val document =
            InterceptedRequest(
                url = "https://shop.example/search?q=keyboard",
                method = "GET",
                headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
                isForMainFrame = true,
            )
        assertFalse(InterceptionPolicy().intercept(document))
        assertTrue(InterceptionPolicy.AUTOMATION.intercept(document))

        // And the recipe named on `intercept`, for a caller who wants its XHR rewritten and its
        // pages fetched by the browser.
        val nativeDocuments = InterceptionPolicy(intercept = { !it.isForMainFrame && isDocumentOrData(it) })
        assertFalse(nativeDocuments.intercept(document))
        assertTrue(nativeDocuments.intercept(document.copy(isForMainFrame = false)))
    }
}
