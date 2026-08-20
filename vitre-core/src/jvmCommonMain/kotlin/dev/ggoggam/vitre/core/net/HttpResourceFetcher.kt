package dev.ggoggam.vitre.core.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a resource the way an intercepting WebView needs it fetched, on either JVM platform.
 *
 * This is the half of interception that has nothing to do with the platform: open a connection,
 * follow the redirects the platform's own follower gets wrong, read the bytes, and hand back an
 * [InterceptedResponse]. Android turns that into a `WebResourceResponse` and desktop turns it into
 * a `CefResourceHandler`; neither has an opinion about anything below.
 *
 * It lives in `jvmCommonMain` because the two platforms had no business having two copies. The
 * redirect rule below in particular is the sort of thing that gets fixed once and then silently
 * regresses on whichever platform the fix was not applied to.
 *
 * Called on a WebView/CEF background thread, once per resource, blocking that resource's load.
 */
internal class HttpResourceFetcher(
    /** Where the session lives. The platforms keep their cookies in different places. */
    private val cookies: CookieJar,
) {
    /**
     * Follows redirects by hand rather than leaving it to [HttpURLConnection].
     *
     * The built-in follower refuses to change protocol, and `http://` → `https://` is the single
     * most common redirect on the web — so the automatic one silently stops at the 301 and the
     * lane renders an empty document.
     *
     * @throws IOException on a network failure, and [RuntimeException] for a malformed URL or an
     *   unsupported protocol. Both are the caller's cue to fall back to the platform rather than
     *   to render an error page: the browser may well handle what we could not.
     */
    fun fetch(request: InterceptedRequest): InterceptedResponse {
        var target = request.url
        var hops = 0
        while (true) {
            val connection = open(target, request)
            val status = connection.responseCode
            if (status in REDIRECT_CODES && hops < MAX_REDIRECTS) {
                // A redirect's `Set-Cookie` is usually the whole point of the redirect, so it is
                // kept rather than dropped with the body. An SSO bounce — `developer.android.com`
                // → `accounts.google.com` → back — carries the "already asked, stop redirecting"
                // cookie on the 302 itself, and a follower that keeps only the *final* response's
                // cookies never converges: the site redirects, the redirect is answered without
                // the cookie it just set, and the site redirects again. Chromium then re-requests
                // whatever this hands back once the hop budget runs out, so the loop is unbounded.
                // It presents as a page that takes forever rather than as an error, because every
                // individual hop succeeds. `developer.android.com` went from a 120s navigation
                // timeout to a 3s load.
                storeCookies(connection, target)
                // Drained, not disconnected. `disconnect()` tears the socket down and takes it out
                // of the keep-alive pool, so a page of eighty subresources pays for eighty TLS
                // handshakes — which on a real site is the difference between a lane that loads
                // and a lane that times out. Consuming the body is what returns it to the pool.
                connection.drain()
                val location = connection.getHeaderField("Location") ?: throw IOException("redirect with no Location from $target")
                target = URL(URL(target), location).toString()
                hops++
                continue
            }
            return read(connection, status, target)
        }
    }

    private fun open(
        url: String,
        request: InterceptedRequest,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        for ((name, value) in HeaderRewriter.sanitizeRequestHeaders(request.headers)) {
            connection.setRequestProperty(name, value)
        }
        // The page's session lives in the browser's cookie jar, not in ours. Without this a shop
        // sees an anonymous client and answers with a login wall — which looks exactly like the
        // selector having changed.
        cookies.cookieHeader(url)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    /**
     * Files every `Set-Cookie` on a response against the URL that was *requested* for it.
     *
     * Against the requested URL rather than the final one, because a redirect chain crosses origins
     * — the cookie `accounts.google.com` sets belongs to `accounts.google.com`, not to whatever the
     * chain eventually lands on.
     */
    private fun storeCookies(
        connection: HttpURLConnection,
        url: String,
    ) {
        for ((name, values) in connection.headerFields) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
            values.forEach { cookies.store(url, it) }
        }
    }

    private fun read(
        connection: HttpURLConnection,
        status: Int,
        finalUrl: String,
    ): InterceptedResponse {
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in connection.headerFields) {
            // The status line arrives as a null-keyed entry, which is not a header and breaks
            // anything that assumes otherwise.
            if (name == null || values.isEmpty()) continue
            if (name.equals("Set-Cookie", ignoreCase = true)) {
                values.forEach { cookies.store(finalUrl, it) }
                continue
            }
            headers[name] = values.joinToString(", ")
        }
        val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        val contentType = connection.contentType ?: "application/octet-stream"
        return InterceptedResponse(
            status = status,
            reason = connection.responseMessage?.takeIf { it.isNotBlank() } ?: reasonFor(status),
            contentType = contentType.substringBefore(';').trim(),
            charset = charsetOf(contentType),
            headers = headers,
            body = body,
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Where an intercepted request gets its `Cookie` header, and where a `Set-Cookie` goes.
 *
 * An interface rather than a direct call because the two platforms keep the session somewhere
 * different — Android in `android.webkit.CookieManager`, desktop in CEF's own jar — and because
 * getting this wrong is invisible: the request still succeeds, it just comes back logged out.
 */
internal interface CookieJar {
    /** The `Cookie` header to send with a request to [url], or null if there is nothing to send. */
    fun cookieHeader(url: String): String?

    /** Records one `Set-Cookie` value the response for [url] carried. */
    fun store(
        url: String,
        setCookie: String,
    )
}

/** Reads and discards whatever is left, which is what hands the socket back to the pool. */
internal fun HttpURLConnection.drain() {
    runCatching { inputStream?.use { it.readBytes() } }
    runCatching { errorStream?.use { it.readBytes() } }
}

internal fun String.isTextualContentType(): Boolean {
    val type = lowercase()
    return type.startsWith("text/") ||
        type.contains("json") ||
        type.contains("xml") ||
        type.contains("javascript") ||
        type.contains("ecmascript") ||
        type.contains("x-www-form-urlencoded")
}

internal fun charsetOf(contentType: String): String =
    contentType
        .split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"', ' ')
        ?: "utf-8"

internal fun reasonFor(status: Int): String =
    when (status) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status $status"
    }
