package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.WebViewSession
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.describe

/**
 * What a model is told *after* an action, as opposed to before it.
 *
 * [PageToolDocs] is the prompt; this is the reply. Both are read by a model and reasoned from, and
 * both were being written out once per adapter — which is the drift [PageDriver]'s own KDoc says
 * this module exists to stop. "Clicked css `#buy`. If it navigated … take a new `snapshot`" is not
 * MCP's sentence or Koog's; it is the page vocabulary's, and an agent that met Vitre through one
 * adapter should not be told something different by the other.
 *
 * What an adapter still owns is how the reply travels: an MCP `content` block with its own
 * `structured` payload, a Koog tool result the framework encodes. Only the words are shared.
 */
object PageToolReplies {
    /** No WebView has been registered yet, which is a host problem rather than a model one. */
    const val NO_SESSIONS: String =
        "No WebView sessions are registered. The host application registers them; until it does " +
            "there is no page to drive."

    /** The registered WebViews, one per line, saying when `session` may be left out. */
    fun sessions(all: List<WebViewSession>): String {
        if (all.isEmpty()) return NO_SESSIONS
        return all.joinToString("\n") { session ->
            buildString {
                append("- ")
                append(session.id)
                if (session.description.isNotBlank()) append(" — ${session.description}")
                if (all.size == 1) append(" (the only session, so `session` may be omitted)")
            }
        }
    }

    fun navigated(
        url: String,
        title: String,
    ): String = "Loaded $url — \"$title\". Take a `snapshot` to see what is on it."

    fun clicked(locator: Locator): String =
        "Clicked ${locator.describe()}. If it navigated or changed the page, take a new " +
            "`snapshot` — handles from before the click may no longer resolve."

    fun typed(locator: Locator): String = "Typed into ${locator.describe()}."

    fun present(locator: Locator): String = "${locator.describe()} is present."

    /** An expression that evaluated to nothing, said out loud so it is not read as an empty page. */
    const val NO_VALUE: String = "(the expression produced no value)"

    const val POSTED: String = "Posted to the page."

    fun leaseHeld(grant: LeaseGrant): String =
        "Holding session `${grant.sessionId}` as lease `${grant.id}` for up to ${grant.ttlMs}ms. " +
            "Pass `lease: \"${grant.id}\"` on every call that belongs to this sequence, and " +
            "`release_lease` as soon as it is done — other callers are queued behind you until then, " +
            "and the lease expires by itself if you stop."

    fun leaseReleased(id: String): String = "Released `$id`."

    fun leaseNotActive(id: String): String = "Lease `$id` was not active — it had already expired or been released."
}
