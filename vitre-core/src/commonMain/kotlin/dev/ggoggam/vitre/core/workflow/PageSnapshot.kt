package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What is on the page right now, as an agent needs to see it.
 *
 * Every other read in this library is [WorkflowStep.Extract], which requires already knowing a
 * selector. That is the right shape for a workflow written in advance against a page its author has
 * open in a browser, and the wrong shape for an agent, which arrives knowing nothing and must ask
 * the opposite question: *what is here?*
 *
 * Raw HTML would answer it and is not usable — a real page is tens of thousands of tokens, almost
 * all of them markup the agent has no use for. So a snapshot keeps the elements worth acting on or
 * reading (see [SnapshotNode.role]), each with a [SnapshotNode.ref] the agent can act on directly,
 * and drops everything else.
 */
@Serializable
data class PageSnapshot(
    val url: String = "",
    val title: String = "",
    val nodes: List<SnapshotNode> = emptyList(),
    /** The walk hit its node budget and stopped. Later parts of the page are missing. */
    val truncated: Boolean = false,
) {
    /**
     * The snapshot as indented text, which is how it should reach a model.
     *
     * ```
     * heading "Search results"
     *   link "Wireless keyboard" [ref=e4]
     *   text "£39.99"
     *   button "Add to cart" [ref=e6]
     * ```
     *
     * JSON costs roughly three times as many tokens to say the same thing, and the extra characters
     * are punctuation and repeated key names — nothing a model reads. Indentation carries the
     * nesting that the keys were spelling out.
     */
    fun render(): String =
        buildString {
            append(title.ifBlank { "(untitled)" })
            append(" — ")
            append(url)
            if (nodes.isEmpty()) {
                append("\n(no elements matched; the page may still be loading)")
            }
            for (node in nodes) {
                append('\n')
                repeat(node.depth) { append("  ") }
                append(node.render())
            }
            if (truncated) append("\n… truncated; raise maxNodes or narrow the page first")
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Decodes what the page-side script returned. */
        fun decode(json: String): PageSnapshot = JSON.decodeFromString(json)
    }
}

/**
 * One element the page is showing.
 *
 * [ref] is the handle: pass it to [handle] and any element-addressing step will act on this exact
 * element, with no selector invented in between. It stays valid until the document navigates away
 * or the element is removed, and fails loudly rather than quietly rebinding — see [Locator.Handle].
 */
@Serializable
data class SnapshotNode(
    val ref: String,
    /**
     * What the element is for: an ARIA role where the page declares one, otherwise the role its tag
     * implies (`link`, `button`, `textbox`, `checkbox`, `heading`, `image`, `option`, `combobox`),
     * or `text` for an element that only carries prose.
     *
     * Elements with no semantics of their own but a click handler or a tab stop are reported as
     * `button`, because clicking is what there is to do with them. That over-reports slightly and
     * is the right way round: a control the page built out of a `div` is invisible to a role-only
     * walk, and invisible controls are how an agent gets stuck.
     */
    val role: String,
    /** The accessible name — `aria-label`, an associated `<label>`, `alt`, or the visible text. */
    val name: String = "",
    val tag: String = "",
    /** Nesting depth among *included* elements, for indenting the rendered form. */
    val depth: Int = 0,
    /** The live `value` property of a form control — what the user would see typed in it. */
    val value: String? = null,
    val checked: Boolean? = null,
    val disabled: Boolean? = null,
    val href: String? = null,
) {
    internal fun render(): String =
        buildString {
            append(role)
            if (name.isNotBlank()) append(" \"$name\"")
            value?.takeIf { it.isNotEmpty() }?.let { append(" value=\"$it\"") }
            checked?.let { append(if (it) " checked" else " unchecked") }
            if (disabled == true) append(" disabled")
            href?.let { append(" -> $it") }
            // Text carries no ref-worthy action, but it is still the thing an Extract would target,
            // so every node gets one and the agent decides what is worth addressing.
            append(" [ref=$ref]")
        }
}
