package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.webview.AsyncScript
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.random.Random

// Typed conveniences over the two bridge primitives, as extension functions and nothing else.
//
// WebViewBridge speaks raw strings, which leaves every caller decoding JSON inside a predicate — the
// AwaitMessage step did exactly that, and so did anyone awaiting a typed message programmatically.
// The functions below compose `awaitMessage` and `postToWebView`; they hold no state, register no
// handlers and add no plane. See docs/ASYNC-BRIDGE.md for why a `register<T, R>` handler registry is
// deliberately not what this is.

/** Default bound on a typed wait, matching `WorkflowStep.AwaitMessage`'s own default. */
const val DEFAULT_MESSAGE_TIMEOUT_MS: Long = 10_000L

/**
 * One inbound message, both as it arrived and as it decoded.
 *
 * Both halves are kept because the decode is lossy and the string is not: the parser ignores
 * unknown keys, so any field the page sent that [BridgeMessage] does not declare survives only in
 * [raw]. Store or forward [raw]; read [envelope] to route.
 */
data class ReceivedMessage(
    val raw: String,
    val envelope: BridgeMessage,
)

/**
 * A typed wait ran out of time.
 *
 * Deliberately a plain [RuntimeException] and not a `CancellationException`: a timeout the library
 * imposes on itself is a failure of the page to answer, not the caller giving up, and letting it
 * travel as a cancellation would tear down the caller's coroutine scope and be reported by
 * `WorkflowEngine` as "the collector cancelled us". Same principle the engine applies to every other
 * self-imposed bound.
 */
class BridgeTimeoutException(
    message: String,
) : RuntimeException(message)

/**
 * Suspends until the page posts a message whose decoded `type` is [type], then consumes it.
 *
 * Consuming, main-frame-only and buffered — everything [WebViewBridge.awaitMessage]'s contract says,
 * since this is that call with the decode moved inside.
 *
 * @throws BridgeTimeoutException if no such message arrives within [timeoutMs].
 * @throws IllegalArgumentException if [type] is the reserved settle-plane type — `ScriptResults`
 *   claims every such message before the inbox sees it, so this wait could only ever time out. The
 *   send side ([post]/[request]) refuses it too; failing fast here is the difference between an
 *   error naming the cause and a silent ten-second timeout.
 */
suspend fun WebViewBridge.awaitMessage(
    type: String,
    timeoutMs: Long = DEFAULT_MESSAGE_TIMEOUT_MS,
): ReceivedMessage {
    requireSendableType(type)
    return awaitDecoded(timeoutMs, "message of type \"$type\"") { it.type == type }
}

/**
 * Sends a [BridgeMessage] to the page as `MessageEvent('vitre')` and returns its id.
 *
 * The id is returned rather than merely generated because it is what a reply will name in its
 * `replyTo` — [request] is this function plus the wait for that reply.
 *
 * @throws IllegalArgumentException if [type] is the reserved settle-plane type.
 */
suspend fun WebViewBridge.post(
    type: String,
    payload: JsonElement = JsonNull,
    id: String = newMessageId(),
): String {
    requireSendableType(type)
    postToWebView(JSON.encodeToString(BridgeMessage.serializer(), BridgeMessage(id = id, type = type, payload = payload)))
    return id
}

/**
 * Sends a request to the page and suspends until the page answers it.
 *
 * The answer is any message whose `replyTo` is this request's `id`; its own `id` stays its own, so
 * a reply is still an ordinary message on the `messages` firehose rather than a second copy of the
 * request. The page side is one line — echo the incoming `id` back as `replyTo`.
 *
 * **Post-then-await does not race.** The reply can land while this function is still between the
 * post and the wait — a synchronous page handler makes that the normal case, not the unlucky one —
 * and it still matches, because [WebViewInbox] buffers unread messages and [WebViewBridge.awaitMessage]
 * scans what is already buffered before it sleeps. That buffering is the inbox's entire reason to
 * exist; correlating by `replyTo` is what keeps a *different* reply, buffered from an earlier
 * request, from being taken instead.
 *
 * @throws BridgeTimeoutException if no reply naming this request arrives within [timeoutMs].
 * @throws IllegalArgumentException if [type] is the reserved settle-plane type.
 */
suspend fun WebViewBridge.request(
    type: String,
    payload: JsonElement = JsonNull,
    timeoutMs: Long = DEFAULT_MESSAGE_TIMEOUT_MS,
): ReceivedMessage {
    val id = post(type, payload)
    return awaitDecoded(timeoutMs, "a reply to \"$type\" ($id)") { it.replyTo == id }
}

/**
 * The shared body of every typed wait: bound it, decode candidates, and report expiry as a plain
 * exception. [what] names the thing waited for, for the message a caller will actually read.
 */
private suspend fun WebViewBridge.awaitDecoded(
    timeoutMs: Long,
    what: String,
    predicate: (BridgeMessage) -> Boolean,
): ReceivedMessage =
    try {
        withTimeout(timeoutMs) {
            val raw = awaitMessage { candidate -> decodeOrNull(candidate)?.let(predicate) == true }
            // The predicate already proved this string decodes, so this pass cannot fail — and it
            // is cheaper than smuggling the envelope out of a predicate the inbox calls under a lock.
            ReceivedMessage(raw, JSON.decodeFromString(BridgeMessage.serializer(), raw))
        }
    } catch (_: TimeoutCancellationException) {
        throw BridgeTimeoutException("Timed out after ${timeoutMs}ms waiting for $what")
    }

/** Null for anything the page posted that is not a bridge envelope at all — junk is not a match. */
private fun decodeOrNull(raw: String): BridgeMessage? = runCatching { JSON.decodeFromString(BridgeMessage.serializer(), raw) }.getOrNull()

/**
 * A fresh identity for an outbound message.
 *
 * `kotlin.random.Random` is right here: this is host-side library code choosing an id for its own
 * bookkeeping, not a workflow deciding what a page sees, so nothing needs it to be reproducible.
 * The `msg#` prefix keeps the namespace disjoint from `script:result#<cid>`, so an id on the
 * firehose says which plane produced it at a glance.
 */
@PublishedApi
internal fun newMessageId(): String = "$MESSAGE_ID_PREFIX${Random.nextLong().toULong().toString(16)}"

/**
 * Refuses the one type that cannot survive a round trip.
 *
 * `ScriptResults` claims every `script:result` message before the inbox sees it, so a reply typed
 * that way is consumed by the settle plane and the wait here never ends. Failing at the call is the
 * difference between an error naming the cause and a ten-second timeout naming nothing.
 */
private fun requireSendableType(type: String) {
    require(type != AsyncScript.RESULT_TYPE) {
        "\"${AsyncScript.RESULT_TYPE}\" is reserved for the settle plane: such a message is consumed before the inbox sees it"
    }
}

private const val MESSAGE_ID_PREFIX = "msg#"

/**
 * Shared by the inline typed helpers, so it is `@PublishedApi internal` rather than private — it is
 * reachable from a caller's compiled code but is not public API.
 *
 * `ignoreUnknownKeys` is what makes a payload class a *view* of what the page sent rather than an
 * exhaustive description of it: a page that adds a field keeps working against a class that has not
 * heard of it. Anything dropped that way is still in `ReceivedMessage.raw`.
 */
@PublishedApi
internal val JSON: Json = Json { ignoreUnknownKeys = true }

// ── Typed payloads ───────────────────────────────────────────────────────────────────────────────
//
// The same two primitives with `kotlinx.serialization` doing the encode and the decode, so a caller
// names a class instead of assembling a `JsonElement` by hand and reading fields back out of one.
// These are conveniences over the functions above and add nothing to the protocol: the envelope,
// the id namespace, the reserved-type check and the `replyTo` correlation are all unchanged, and a
// typed call is indistinguishable on the wire from the hand-built equivalent.

/**
 * [post], with [payload] serialized from [T].
 *
 * @throws IllegalArgumentException if [type] is the reserved settle-plane type.
 */
suspend inline fun <reified T> WebViewBridge.post(
    type: String,
    payload: T,
    id: String = newMessageId(),
): String = post(type, JSON.encodeToJsonElement(payload), id)

/**
 * [request], with [payload] serialized from [T] and the reply's payload decoded into [R] — the
 * round trip as one typed call.
 *
 * Returns the reply's **payload**, not its envelope, because the envelope's remaining fields are
 * plumbing this call already resolved: `replyTo` is what it matched on, and `id` names a message
 * the caller never sees. When those are wanted — or when the reply's `type` is what distinguishes
 * an answer from a refusal — use the [ReceivedMessage]-returning overload and decode by hand.
 *
 * @throws BridgeTimeoutException if no reply naming this request arrives within [timeoutMs].
 * @throws IllegalArgumentException if [type] is the reserved settle-plane type.
 * @throws kotlinx.serialization.SerializationException if the reply's payload is not an [R] — which
 *   includes a page that answered with no payload at all when [R] is not nullable.
 */
suspend inline fun <reified T, reified R> WebViewBridge.request(
    type: String,
    payload: T,
    timeoutMs: Long = DEFAULT_MESSAGE_TIMEOUT_MS,
): R = JSON.decodeFromJsonElement(request(type, JSON.encodeToJsonElement(payload), timeoutMs).envelope.payload)

/**
 * [awaitMessage], with the matched message's payload decoded into [R].
 *
 * For the one-way direction — telemetry, a page announcing something — where there is no request to
 * correlate against and the type is the whole routing decision.
 *
 * @throws BridgeTimeoutException if no such message arrives within [timeoutMs].
 * @throws kotlinx.serialization.SerializationException if the payload is not an [R].
 */
suspend inline fun <reified R> WebViewBridge.awaitPayload(
    type: String,
    timeoutMs: Long = DEFAULT_MESSAGE_TIMEOUT_MS,
): R = JSON.decodeFromJsonElement(awaitMessage(type, timeoutMs).envelope.payload)
