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

    /**
     * Captured traffic, as the model reads it.
     *
     * Two things this has to get right, and both are about what the reader concludes rather than
     * about what is in the list:
     *
     *  - **An empty result is not "it did not happen."** The tap's coverage differs by platform, so
     *    the empty case repeats the caveat instead of leaving a bare "0 matches" to be read as
     *    proof of absence. The tool description says it too; this says it at the moment the wrong
     *    conclusion is available.
     *  - **A cut body is labelled where the body is**, not in a footnote. A model that scrolls past
     *    a header and reads truncated JSON as complete will report four results out of forty, and
     *    nothing downstream can tell that it did.
     */
    fun network(read: NetworkRead): String {
        val scope = read.filter?.let { " matching `$it`" }.orEmpty()
        if (read.exchanges.isEmpty()) {
            return buildString {
                append("No captured exchange$scope. ")
                append(
                    if (read.retained == 0) {
                        "Nothing has been captured for this session at all — either the page has " +
                            "made no requests the tap can see, or it made them before capture started. "
                    } else {
                        "${read.retained} exchange${plural(read.retained)} are held, none of them a match. "
                    },
                )
                append(NOT_PROOF_OF_ABSENCE)
            }
        }
        return buildString {
            append("Showing ${read.exchanges.size} of ${read.matched} captured exchange")
            append(plural(read.matched))
            append(scope)
            append(", newest first")
            // Said only when it is true, and it is exactly the case where an older exchange the
            // caller is looking for may have existed and been dropped.
            if (read.retained >= read.capacity) {
                append(" (the buffer is full at ${read.capacity}, so anything older has been dropped)")
            }
            append(".\n")
            read.exchanges.forEach { exchange ->
                append('\n')
                append(exchange.render())
                append('\n')
            }
        }
    }

    private fun NetworkExchangeSummary.render(): String =
        buildString {
            append("${method.uppercase()} ")
            append(if (status > 0) "$status " else "(no response) ")
            append(url)
            val notes = listOfNotNull(contentType, "${durationMs}ms".takeIf { durationMs > 0 }, error)
            if (notes.isNotEmpty()) append(" — ${notes.joinToString(", ")}")
            append('\n')
            append(
                when {
                    body != null && bodyTruncated -> {
                        "body (TRUNCATED — this is the first ${body.length} characters, not the whole " +
                            "response; do not treat it as complete):\n$body"
                    }

                    body != null -> {
                        "body:\n$body"
                    }

                    // Withheld rather than absent: there was a body and the caller asked not to be
                    // shown it. Saying "no body" here would be a lie the caller cannot detect.
                    hasBody -> {
                        "body: not shown (raise `max_body_chars` to see it)"
                    }

                    else -> {
                        "body: none captured (not textual, or capture is off for this session)"
                    }
                },
            )
        }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    /** Repeated wherever a model could read an empty answer as a fact about the world. */
    private const val NOT_PROOF_OF_ABSENCE: String =
        "This is not proof the request was never made: on iOS only the page's own `fetch` and " +
            "`XMLHttpRequest` calls are ever captured — document loads, images and stylesheets are " +
            "invisible — while Android and desktop see everything. Read the page with `snapshot` " +
            "and `extract` instead."
}
