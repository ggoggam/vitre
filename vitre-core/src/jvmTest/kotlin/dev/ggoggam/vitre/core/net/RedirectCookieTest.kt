package dev.ggoggam.vitre.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The redirect follower's cookie rule, which nothing else covers and which fails silently.
 *
 * `HttpResourceFetcher` is `jvmCommonMain`, so this one test covers both Android and the desktop.
 * It is here rather than in `commonTest` because it needs a real HTTP server, and there is no
 * multiplatform one worth adding for it.
 */
class RedirectCookieTest {
    /**
     * The shape of an SSO bounce: the redirect carries the cookie that stops the next hop
     * redirecting again.
     *
     * Without cookies kept off the 302, `/gate` never sees `checked=1`, sends the browser back to
     * `/start`, and the two bounce until the hop budget runs out — which the caller sees as a
     * redirect handed back rather than as a page, and the WebView then re-requests. It looks like a
     * site that takes forever to load, because every individual hop succeeds.
     */
    @Test
    fun `a cookie set on a redirect is sent on the hop it redirects to`() {
        val jar = RecordingCookieJar()
        withServer { server, base ->
            server.createContext("/start") { exchange ->
                exchange.responseHeaders.add("Set-Cookie", "checked=1; Path=/")
                exchange.responseHeaders.add("Location", "$base/gate")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/gate") { exchange ->
                if (exchange.requestHeaders.getFirst("Cookie")?.contains("checked=1") == true) {
                    exchange.reply(200, "arrived")
                } else {
                    exchange.responseHeaders.add("Location", "$base/start")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
            }

            val response = HttpResourceFetcher(jar).fetch(get("$base/start"))

            assertEquals(200, response.status, "the redirect chain should converge, not run out of hops")
            assertEquals("arrived", response.body.toString(Charsets.UTF_8))
        }
        assertTrue(jar.stored.any { it.contains("checked=1") }, "the redirect's Set-Cookie should have been kept")
    }

    /** The final response's cookies were always kept; this pins that they still are. */
    @Test
    fun `a cookie set on the final response is kept`() {
        val jar = RecordingCookieJar()
        withServer { server, base ->
            server.createContext("/only") { exchange ->
                exchange.responseHeaders.add("Set-Cookie", "session=abc; Path=/")
                exchange.reply(200, "body")
            }
            val response = HttpResourceFetcher(jar).fetch(get("$base/only"))
            assertEquals(200, response.status)
            // Dropped from the headers handed back, because it goes to the jar instead.
            assertTrue(response.headers.keys.none { it.equals("Set-Cookie", ignoreCase = true) })
        }
        assertTrue(jar.stored.any { it.contains("session=abc") })
    }

    private fun get(url: String) =
        InterceptedRequest(
            url = url,
            method = "GET",
            headers = mapOf("Accept" to "text/html"),
            isForMainFrame = true,
        )

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server, "http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Every cookie is sent back on every request — enough to tell "kept" from "dropped". */
    private class RecordingCookieJar : CookieJar {
        val stored = mutableListOf<String>()

        override fun cookieHeader(url: String): String? = stored.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.substringBefore(';') }

        override fun store(
            url: String,
            setCookie: String,
        ) {
            stored += setCookie
        }
    }
}
