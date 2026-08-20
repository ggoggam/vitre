package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.BridgeMessage
import dev.ggoggam.vitre.core.bridge.jsString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * The string half of making `evaluateJs` wait for a promise: wrapping a script so a promise
 * reports itself back over the bridge, and reading that report when it arrives.
 *
 * Neither platform's evaluate call waits on its own. `WebView.evaluateJavascript` hands back
 * whatever the expression evaluated to, and a promise serialises as `{}`; `WKWebView`'s
 * `evaluateJavaScript` is the same story with Foundation types. That is not a corner case:
 * anything built on `fetch` is asynchronous, and for extraction a shop's own JSON API is
 * frequently the good path. So the script is wrapped: a plain value comes back through the
 * evaluate exactly as before, and a promise returns [pendingResult] immediately, then posts its
 * settled value through `window.vitre.postMessage` — where [ScriptResults] is waiting for it.
 *
 * One instance per controller, because the [nonce] is the instance's identity. Bridge messages
 * arrive from whatever the page runs, and a settled-promise report is only credited when it names
 * a nonce that never left native code and this wrapper's own closures. A forged report from page
 * script guessing at the protocol — the cids are a counter, trivially guessable — names the wrong
 * nonce or none, and [ScriptResults] drops it. The nonce also keeps the pending sentinel out of
 * band: no page value can collide with a marker it cannot know.
 *
 * The limit of that claim is the main frame's own JS context: a script running there can shadow
 * `window.vitre.postMessage` before the wrapper calls it and capture the nonce in flight. A
 * hostile main document can already lie in its DOM, which is where extracted data comes from
 * anyway — the boundary the nonce (with [ScriptResults]' frame gate) actually holds is against
 * subframes and protocol guessing. See `docs/ASYNC-BRIDGE.md`.
 *
 * Kept in commonMain because it is string work, and string work is testable without a WebView.
 */
internal class AsyncScript(
    /** Never leaves native code and the wrapped scripts' closures. Fixed only by tests. */
    val nonce: String = Random.nextLong().toULong().toString(16),
) {
    /**
     * What the wrapped script evaluates to when the value is still pending.
     *
     * A sentinel rather than a flag alongside the result, because the platform evaluate gives one
     * string and nothing else. It carries the nonce and command id, so neither a page value nor a
     * stale reply — a script whose promise settled after its caller had given up — can be mistaken
     * for the current script's pending marker.
     *
     * One string does two jobs: it is the literal the wrapper returns, and it is byte-for-byte
     * what the platform hands back for that literal, since both are a JSON-encoded string.
     */
    fun pendingResult(cid: Long): String = jsString("$PENDING_PREFIX$nonce:$cid")

    /**
     * [script], evaluating to its own value when that value is not a promise and to
     * [pendingResult] when it is.
     *
     * The synchronous path is deliberately untouched — the same expression, returned the same way —
     * so the overwhelmingly common case behaves exactly as it did before, encoding included. The
     * platform actual owns the final encoding of what the evaluate returns; the settled value of a
     * promise is `JSON.stringify`'d in the page before it is posted, which is the same encoding by
     * construction.
     */
    fun wrap(
        script: String,
        cid: Long,
    ): String =
        """
        (function () {
          var v = ($script);
          if (!v || typeof v.then !== 'function') { return v; }
          function send(ok, value, error) {
            var native = window.vitre;
            if (!native || typeof native.postMessage !== 'function') { return; }
            var envelope = {
              id: '$RESULT_TYPE#$cid',
              type: '$RESULT_TYPE',
              payload: { cid: $cid, nonce: '$nonce', ok: ok, value: value, error: error }
            };
            // Nothing left to report a failure to: the bridge is how a failure would be reported.
            try { native.postMessage(JSON.stringify(envelope)); } catch (e) { }
          }
          v.then(
            function (resolved) {
              var text;
              try { text = JSON.stringify(resolved); } catch (e) { text = JSON.stringify(String(resolved)); }
              send(true, typeof text === 'undefined' ? 'null' : text, null);
            },
            function (rejected) { send(false, null, String((rejected && rejected.message) || rejected)); }
          );
          return ${pendingResult(cid)};
        })()
        """.trimIndent()

    /**
     * Decodes [raw] as a settled-promise report, or null when it is any other message.
     *
     * Null is a routing decision — the message belongs to the page and goes on to the inbox. A
     * non-null return claims the message for the settle plane even when its payload is unreadable
     * or its nonce is someone's guess; whether it is *credited* is [ScriptResults]' call, which
     * checks [Settled.nonce] against [nonce].
     */
    fun parse(raw: String): Settled? {
        if (!raw.contains(RESULT_TYPE)) return null
        val message = runCatching { JSON.decodeFromString<BridgeMessage>(raw) }.getOrNull() ?: return null
        if (message.type != RESULT_TYPE) return null
        val payload = message.payload as? JsonObject ?: return Settled(cid = null, nonce = null, ok = false, value = null, error = null)
        // Safe casts, not `.jsonPrimitive`: that accessor *throws* for a non-primitive element, and
        // this runs in a platform message callback for every inbound message — including a forged
        // `{"payload":{"cid":{}}}` from a subframe — where a throw is a crash on the WebView thread.
        return Settled(
            cid = (payload["cid"] as? JsonPrimitive)?.longOrNull,
            nonce = (payload["nonce"] as? JsonPrimitive)?.contentOrNull,
            ok = (payload["ok"] as? JsonPrimitive)?.booleanOrNull == true,
            value = (payload["value"] as? JsonPrimitive)?.contentOrNull,
            error = (payload["error"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    /** One settled promise as reported over the bridge; fields are null where the report was not readable. */
    data class Settled(
        val cid: Long?,
        val nonce: String?,
        val ok: Boolean,
        val value: String?,
        val error: String?,
    ) {
        /**
         * The JSON-encoded value the promise resolved to.
         *
         * The wrapper stringifies before sending, so what arrives is the same encoding the
         * platform's evaluate would have produced for a plain value.
         *
         * @throws ScriptFailedException if the promise rejected.
         */
        fun valueOrThrow(): String {
            if (!ok) throw ScriptFailedException(error ?: "script failed")
            return value ?: "null"
        }
    }

    companion object {
        /** Bridge message type carrying one settled promise. */
        const val RESULT_TYPE: String = "script:result"

        private const val PENDING_PREFIX = "__wv_pending:"

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
