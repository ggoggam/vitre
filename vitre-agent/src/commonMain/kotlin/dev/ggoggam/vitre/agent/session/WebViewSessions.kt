package dev.ggoggam.vitre.agent.session

import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A WebView an agent can drive, under the name a tool call will ask for it by. */
data class WebViewSession(
    val id: String,
    val controller: WebViewController,
    /** Shown in `list_sessions`, so the agent can tell two WebViews apart. */
    val description: String = "",
)

/**
 * The names by which tool calls find WebViews.
 *
 * A tool call carries no state beyond its arguments, and a WebView is nothing but state, so
 * something has to join the two. This registry is that join, and it lives here rather than in
 * `vitre-core` on purpose: core hands a controller to whoever mounted the composable and has no
 * opinion about naming it, which is right for a library that a single-WebView app embeds without
 * ever seeing this module.
 *
 * It is not MCP's registry, though MCP was the first thing to need it. Every way of letting an agent
 * at a page — MCP tools, Koog tools, whatever comes next — has the same stateless-call-to-stateful-
 * WebView problem, and solving it once per protocol is how two solutions drift.
 *
 * Hosts register from wherever they build the WebView — a Compose `onControllerReady`, an activity,
 * a view model — so registration must not suspend. The map therefore lives in a [MutableStateFlow]:
 * its updates are atomic without a lock, and the flow doubles as something a debug pane can observe.
 */
class WebViewSessions {
    private val state = MutableStateFlow<Map<String, WebViewSession>>(emptyMap())

    /** The live set of sessions, for hosts that want to display it. */
    val sessions: StateFlow<Map<String, WebViewSession>> get() = state.asStateFlow()

    /**
     * Makes [controller] reachable as [id]. Re-registering an id replaces it, which is what a
     * recomposition that rebuilt the WebView needs.
     */
    fun register(
        id: String,
        controller: WebViewController,
        description: String = "",
    ) {
        state.update { it + (id to WebViewSession(id, controller, description)) }
    }

    /** Call when the WebView goes away, so tool calls fail saying so rather than driving a corpse. */
    fun unregister(id: String) {
        state.update { it - id }
    }

    fun all(): List<WebViewSession> = state.value.values.toList()

    /**
     * Finds the session a tool call meant.
     *
     * With [id] given this is a lookup. Without one it resolves only when there is exactly one
     * session — which is not a "current session" by another name: a default that *picks* among
     * several would silently drive the wrong WebView, so with two registered this fails and names
     * them both. The single-session case is the overwhelmingly common one, and making an agent pass
     * a session id it could not have got wrong is friction that buys nothing.
     *
     * @throws NoSuchSessionException with a message written for the model to act on.
     */
    fun resolve(id: String?): WebViewSession {
        val known = state.value
        if (id != null) {
            return known[id] ?: throw NoSuchSessionException(
                if (known.isEmpty()) {
                    "No session named `$id`; no WebView sessions are registered at all."
                } else {
                    "No session named `$id`. Registered sessions: ${known.keys.joinToString(", ")}."
                },
            )
        }
        return when (known.size) {
            0 -> throw NoSuchSessionException(
                "No WebView sessions are registered. The host app has to register one before the " +
                    "page can be driven.",
            )

            1 -> known.values.first()

            else -> throw NoSuchSessionException(
                "There are ${known.size} WebView sessions, so `session` is required. " +
                    "Choose one of: ${known.keys.joinToString(", ")}.",
            )
        }
    }
}

/** No session matched, and the message says which ones would have. */
class NoSuchSessionException(
    message: String,
) : RuntimeException(message)
