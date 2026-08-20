package dev.ggoggam.vitre.core.net

/**
 * The part of interception that is pure string work, kept out of the platform actual so it can be
 * tested without a WebView.
 *
 * Every function here takes and returns plain maps. Header names are compared case-insensitively
 * throughout, because the wire does not care and the platform is inconsistent about it.
 */
object HeaderRewriter {
    /**
     * Headers that describe *this hop* rather than the payload, plus the two that describe an
     * encoding we have already undone.
     *
     * `Content-Encoding` and `Content-Length` matter more than they look. The interceptor hands the
     * WebView bytes it has already decompressed; leaving a `Content-Encoding: gzip` on them makes
     * the renderer try to gunzip plain text and fail with an empty document — a blank lane with
     * nothing in the log to explain it.
     */
    private val DROPPED_RESPONSE_HEADERS =
        setOf(
            "connection",
            "content-encoding",
            "content-length",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

    /**
     * Request headers not to forward.
     *
     * `Accept-Encoding` is the subtle one: an HTTP client only decompresses automatically when it
     * chose the encoding itself. Forwarding the WebView's `Accept-Encoding: gzip` therefore gets
     * back compressed bytes the client will hand over as-is, and the caller sees binary where it
     * expected HTML. Dropping it means the client negotiates — and decodes — on its own terms.
     */
    private val DROPPED_REQUEST_HEADERS =
        setOf(
            "accept-encoding",
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

    private val CSP_HEADERS = setOf("content-security-policy", "content-security-policy-report-only")

    fun sanitizeRequestHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() !in DROPPED_REQUEST_HEADERS }

    /**
     * Applies [policy] to a response's headers.
     *
     * [requestOrigin] is the `Origin` the frame sent, and it is reflected rather than answered with
     * `*` because the two are not interchangeable: `Access-Control-Allow-Origin: *` is invalid
     * alongside `Allow-Credentials: true`, and a browser rejects the pair outright. Reflecting the
     * caller's own origin is what makes a credentialed cross-origin read succeed.
     */
    fun rewriteResponseHeaders(
        headers: Map<String, String>,
        requestOrigin: String?,
        policy: InterceptionPolicy,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(headers.size + CORS_HEADER_HEADROOM)
        for ((name, value) in headers) {
            val lower = name.lowercase()
            when {
                lower in DROPPED_RESPONSE_HEADERS -> {
                    continue
                }

                lower in CSP_HEADERS && policy.permissiveCors -> {
                    relaxCsp(value = value, allowAnyConnect = policy.permissiveCors)?.let { out[name] = it }
                }

                // A server that already answered CORS gets overwritten below rather than merged:
                // two Access-Control-Allow-Origin values is a protocol error, not a wider policy.
                policy.permissiveCors && lower.startsWith("access-control-") -> {
                    continue
                }

                else -> {
                    out[name] = value
                }
            }
        }
        if (policy.permissiveCors) out.putAll(corsHeaders(requestOrigin))
        return out
    }

    /**
     * The headers that make a cross-origin read succeed, including a credentialed one.
     *
     * `Vary: Origin` is not decoration — the response body is now origin-dependent, and a cache
     * that does not know it will hand one origin's allowance to another.
     */
    fun corsHeaders(requestOrigin: String?): Map<String, String> =
        buildMap {
            put("Access-Control-Allow-Origin", requestOrigin ?: "*")
            if (requestOrigin != null) {
                put("Access-Control-Allow-Credentials", "true")
                put("Vary", "Origin")
            }
            put("Access-Control-Allow-Methods", "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS")
            put("Access-Control-Allow-Headers", "*")
            put("Access-Control-Expose-Headers", "*")
            put("Access-Control-Max-Age", "600")
        }

    /**
     * Rewrites a CSP as narrowly as the policy allows, and leaves every other directive alone.
     *
     * Dropping the whole header would be easier and worse: `img-src`, `connect-src` and `sandbox`
     * are load bearing for the site's own correctness, and a page whose CSP vanished can behave
     * differently from the one a user sees.
     *
     * One edit, and only when [allowAnyConnect]: **`connect-src` is widened**. Relaxing CORS is not
     * enough on its own — CORS is the *server's* opinion about who may read a response, and
     * `connect-src` is the *page's* opinion about where it may ask at all. A lane on a site with
     * `connect-src 'self'` reports a fetch as blocked no matter how permissive the headers coming
     * back are, and the two failures are indistinguishable from the error message.
     *
     * `frame-ancestors` is deliberately left alone. It answers the question "who may frame this
     * page", a lane is a top-level document, and nobody is asking.
     *
     * Returns null when nothing is left, since an empty `Content-Security-Policy` is not the same
     * as no header — a malformed policy is treated as the most restrictive one by some renderers.
     */
    fun relaxCsp(
        value: String,
        allowAnyConnect: Boolean = true,
    ): String? {
        val kept =
            value
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { allowAnyConnect && it.directive().equals("connect-src", ignoreCase = true) }
                .toMutableList()
        if (allowAnyConnect && value.mentionsConnectSrc()) kept += ANY_CONNECT
        return kept.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Only replaced when the policy actually constrained connections. Adding `connect-src` to a
     * policy that had neither it nor a `default-src` would *narrow* the page — the directive is
     * unrestricted by its absence, and writing one out makes it restricted to what we listed.
     */
    private fun String.mentionsConnectSrc(): Boolean =
        split(';').any { directive ->
            val name = directive.trim().substringBefore(' ')
            name.equals("connect-src", ignoreCase = true) || name.equals("default-src", ignoreCase = true)
        }

    private fun String.directive(): String = substringBefore(' ')

    /** `data:` and `blob:` are spelled out because `*` does not cover non-network schemes. */
    private const val ANY_CONNECT = "connect-src * data: blob:"

    private const val CORS_HEADER_HEADROOM = 8
}
