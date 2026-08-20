package dev.ggoggam.vitre.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tap, rebuilt in JavaScript for the platform that has no interception hook.
 *
 * On Android every exchange is seen from *below* the page, in `shouldInterceptRequest`, which sees
 * everything including the document itself. iOS has no such hook, so the page is asked to report on
 * itself instead: [script] patches `fetch` and `XMLHttpRequest` at document start and posts what
 * they did back to native.
 *
 * The two are not equivalent and the difference is worth stating plainly rather than papering over:
 *
 *  - **Document and subresource loads are invisible here.** Only script-initiated requests report.
 *  - **It observes; it cannot answer.** Nothing in this file can block, rewrite or fake a response.
 *  - **A page that replaces `fetch` after us wins.** Rare, and it shows up as silence rather than
 *    as wrong data.
 *
 * What survives is the part that mattered most: a shop rendering results from `GET /api/search`
 * hands over typed JSON here, which beats parsing `$1,299.00` back out of three nested spans.
 */
internal object ScriptedTap {
    /** The name of the `WKScriptMessageHandler` reports arrive on. */
    const val HANDLER: String = "vitreNet"

    /**
     * Built for one lane's WebView, because the body cap is policy and the fixture rewrite depends
     * on whether this pool serves fixtures at all.
     *
     * The `fetch`/`XHR` URL rewrite is the other job this script does, and it is confined to
     * documents that are *themselves* served from [FixtureScheme]: a fixture shop links to its API
     * by absolute `https://` URL, which would otherwise leave the scheme handler's closed world and
     * go looking for a hostname that does not resolve. It is a safe rewrite precisely because that
     * world is closed — every origin in it is one the application invented.
     */
    fun script(
        maxBodyBytes: Int,
        captureBodies: Boolean,
    ): String =
        """
        (function () {
          if (window.__wvNetTap) { return; }
          window.__wvNetTap = true;

          var MAX_BODY = $maxBodyBytes;
          var CAPTURE = $captureBodies;
          var FIXTURE = '${FixtureScheme.SCHEME}://';
          var HTTPS = 'https://';
          // Read once at document start: a fixture document cannot become a real one, and asking
          // `location` again later would be answered by whatever the page did to its own history.
          var inFixture = location.protocol === '${FixtureScheme.SCHEME}:';

          function post(report) {
            try {
              window.webkit.messageHandlers.$HANDLER.postMessage(JSON.stringify(report));
            } catch (e) {
              // A page with no handler installed is not an error worth breaking a fetch over.
            }
          }

          function retarget(url) {
            try {
              var text = String(url);
              if (inFixture && text.indexOf(HTTPS) === 0) { return FIXTURE + text.slice(HTTPS.length); }
              return text;
            } catch (e) {
              return url;
            }
          }

          function absolute(url) {
            try { return new URL(String(url), location.href).href; } catch (e) { return String(url); }
          }

          function textual(contentType) {
            if (!contentType) { return false; }
            var ct = String(contentType).toLowerCase();
            return ct.indexOf('json') >= 0 || ct.indexOf('text/') === 0 ||
                   ct.indexOf('xml') >= 0 || ct.indexOf('javascript') >= 0;
          }

          function report(method, url, status, contentType, body, started, error, headers) {
            var truncated = false;
            if (body && body.length > MAX_BODY) { body = body.slice(0, MAX_BODY); truncated = true; }
            post({
              method: method || 'GET',
              url: absolute(url),
              status: status || 0,
              contentType: contentType || null,
              body: CAPTURE ? (body || null) : null,
              bodyTruncated: truncated,
              durationMs: Math.max(0, Date.now() - started),
              error: error || null,
              responseHeaders: headers || {}
            });
          }

          var nativeFetch = window.fetch;
          if (typeof nativeFetch === 'function') {
            window.fetch = function (input, init) {
              var started = Date.now();
              var method = (init && init.method) || (input && input.method) || 'GET';
              var requested = (input && typeof input === 'object' && 'url' in input) ? input.url : String(input);
              var moved = retarget(requested);
              var target = input;
              if (moved !== requested) {
                // A Request carries body, headers, mode and credentials, and rebuilding it from the
                // URL alone would silently drop all four. Passing the original as the init argument
                // is what copies them across.
                target = (input && typeof input === 'object' && 'url' in input) ? new Request(moved, input) : moved;
              }
              return nativeFetch.call(this, target, init).then(function (response) {
                var contentType = response.headers ? response.headers.get('content-type') : null;
                var headers = {};
                try {
                  response.headers.forEach(function (value, name) { headers[name] = value; });
                } catch (e) {
                  // Header iteration is not universal; the exchange is still worth reporting.
                }
                if (CAPTURE && textual(contentType)) {
                  // A clone, so the caller still gets an unread body. Reading the original here
                  // would hand the page a stream somebody else had already drained.
                  return response.clone().text().then(function (text) {
                    report(method, moved, response.status, contentType, text, started, null, headers);
                    return response;
                  }, function () {
                    report(method, moved, response.status, contentType, null, started, null, headers);
                    return response;
                  });
                }
                report(method, moved, response.status, contentType, null, started, null, headers);
                return response;
              }, function (failure) {
                report(method, moved, 0, null, null, started, String((failure && failure.message) || failure), null);
                throw failure;
              });
            };
          }

          var open = XMLHttpRequest.prototype.open;
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__wvMethod = method;
            this.__wvUrl = retarget(url);
            var rest = Array.prototype.slice.call(arguments, 2);
            return open.apply(this, [method, this.__wvUrl].concat(rest));
          };
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            var started = Date.now();
            // `once` because one XHR object can be reused (open/send more than once); without it each
            // send stacks another listener and a later response is reported once per prior send, with
            // a stale start time inflating durationMs.
            xhr.addEventListener('loadend', function () {
              var contentType = null;
              try { contentType = xhr.getResponseHeader('content-type'); } catch (e) {}
              var body = null;
              // responseText throws outright for a non-text responseType, which is a question
              // about the response rather than a failure of the request.
              try {
                if (CAPTURE && textual(contentType) && (xhr.responseType === '' || xhr.responseType === 'text')) {
                  body = xhr.responseText;
                }
              } catch (e) {}
              report(
                xhr.__wvMethod, xhr.__wvUrl, xhr.status, contentType, body, started,
                xhr.status === 0 ? 'request failed' : null, null
              );
            }, { once: true });
            return send.apply(this, arguments);
          };
        })();
        """.trimIndent()
}

/**
 * One report from [ScriptedTap.script], as it arrives on the wire.
 *
 * Every field carries a default because the sender is a page — a report that lost a field to a
 * `JSON.stringify` quirk should degrade to a thin exchange rather than take the whole tap down.
 */
@Serializable
internal data class ScriptExchangeReport(
    val method: String = "GET",
    val url: String = "",
    val status: Int = 0,
    val contentType: String? = null,
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    val durationMs: Long = 0,
    val error: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
)

private val TAP_JSON = Json { ignoreUnknownKeys = true }

/**
 * Turns a raw report into a [NetworkExchange], or null if the page sent something unreadable.
 *
 * [ExchangeOutcome.Fetched] rather than [ExchangeOutcome.PassedThrough] even though nothing here
 * intercepted anything: the outcome describes what happened to the *request*, and the request did
 * go to the network. That it was reported from above rather than below is the tap's business.
 */
internal fun scriptExchange(
    raw: String,
    id: Long,
    policy: InterceptionPolicy,
): NetworkExchange? {
    val report = runCatching { TAP_JSON.decodeFromString<ScriptExchangeReport>(raw) }.getOrNull() ?: return null
    if (report.url.isEmpty()) return null
    val captured = report.body?.takeIf { policy.captureBodies }
    val overCap = captured != null && captured.length > policy.maxCapturedBodyBytes
    return NetworkExchange(
        id = id,
        method = report.method.uppercase(),
        url = report.url,
        outcome = if (report.error != null) ExchangeOutcome.Failed else ExchangeOutcome.Fetched,
        status = report.status,
        // The page cannot see what it sent — `fetch` normalises request headers out of reach of a
        // wrapper — so this is empty rather than guessed at.
        requestHeaders = emptyMap(),
        responseHeaders = report.responseHeaders,
        contentType = report.contentType,
        body = captured?.take(policy.maxCapturedBodyBytes),
        bodyTruncated = report.bodyTruncated || overCap,
        durationMs = report.durationMs,
        error = report.error,
    )
}
