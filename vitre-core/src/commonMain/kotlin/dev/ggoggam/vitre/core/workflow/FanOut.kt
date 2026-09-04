package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What one item of a [WorkflowStep.ForEach] produced — one of these per item, in item order, is
 * what the step stores in its `into` variable.
 *
 * ```
 * val details = completed.decode<List<FanOutResult>>("details")
 * details.filter { it.error == null }.map { it.variables.getValue("price") }
 * ```
 *
 * [item] is the element the body was run for, exactly as it appeared in the array. [variables] is
 * what the body *set* — its own extractions, not the bindings and not the workflow's earlier
 * variables, which the caller already has. [error] is the failure message when the body failed,
 * and null when it ran to the end; a failed item's [variables] hold whatever it had set before it
 * failed, which is often most of them.
 */
@Serializable
data class FanOutResult(
    val index: Int,
    val item: JsonElement,
    val variables: Map<String, String>,
    val error: String?,
)

/**
 * Binds [item] under [name] the way a fan-out body sees it: `name` is the whole element, and for an
 * object element `name.field` is each of its fields.
 *
 * A string field arrives as its text and everything else as its JSON, which is the same rule
 * `Extract` follows for a script result: a string has an obvious rendering and nothing else does.
 * Nested objects therefore bind as one JSON blob under the parent field, not as `name.a.b` — a
 * search result is flat, and a rule deep enough to need a second syntax is one nobody would
 * remember.
 */
internal fun MutableMap<String, String>.bindItem(
    name: String,
    item: JsonElement,
) {
    this[name] = item.asVariable()
    if (item is JsonObject) {
        for ((field, value) in item) {
            this["$name.$field"] = value.asVariable()
        }
    }
}

private fun JsonElement.asVariable(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()
