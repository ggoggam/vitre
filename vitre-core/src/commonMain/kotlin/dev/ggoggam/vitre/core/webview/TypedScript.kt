package dev.ggoggam.vitre.core.webview

import kotlinx.serialization.json.Json

// A typed read over `evaluateJs`, as one extension function and nothing else.
//
// The same shape as the typed bridge helpers next door, for the same reason: `evaluateJs` returns
// the script's result JSON-encoded — that is its documented contract, and the one thing the two
// platforms were made to agree on — so every caller who wants a value out of a page ends up writing
// the decode. Written by hand it tends to come out as a string comparison, and `== "true"` is
// correct right up until the expression returns `1`, `"true"` or nothing at all.

/**
 * Evaluates [script] and decodes its settled result into [R].
 *
 * [WebViewController.evaluateJs] with the decode moved inside, so everything that call's contract
 * says still holds: the script is an expression, a promise is awaited rather than returned, and the
 * result is ordered against every other operation on the WebView.
 *
 * ```kotlin
 * val ready: Boolean = controller.evaluate("document.readyState==='complete'")
 * val count: Int = controller.evaluate("document.querySelectorAll('li').length")
 * val items: List<Product> = controller.evaluate("Array.from(…).map(…)")
 * ```
 *
 * A page that answers `null` decodes into a nullable [R] and fails on a non-nullable one, which is
 * the distinction worth having: `evaluate<String>("…?.textContent")` on an element that is not there
 * should not quietly produce `"null"`.
 *
 * This is a convenience over [WebViewController.evaluateJs], not a replacement for it. Keep the raw
 * form when the string *is* the answer — `WorkflowEngine` stores script results in variables typed
 * as strings, and a caller forwarding a result it never reads has nothing to gain by parsing it.
 *
 * @throws ScriptTimeoutException if the result does not arrive in time.
 * @throws ScriptFailedException if the script's promise rejected.
 * @throws kotlinx.serialization.SerializationException if the result is not an [R].
 */
suspend inline fun <reified R> WebViewController.evaluate(script: String): R = SCRIPT_JSON.decodeFromString(evaluateJs(script))

/**
 * Shared by the inline decoder, so it is `@PublishedApi internal` rather than private — reachable
 * from a caller's compiled code but not public API.
 *
 * `ignoreUnknownKeys` matches every other reader in this library: a class describing what a script
 * returns is a view of it, and a page that starts including one more field should not turn every
 * existing call into a failure.
 */
@PublishedApi
internal val SCRIPT_JSON: Json = Json { ignoreUnknownKeys = true }
