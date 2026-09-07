package dev.ggoggam.vitre.agent.session

import dev.ggoggam.vitre.core.net.NetworkLog
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
    /**
     * Retained network traffic for this WebView, if the host wired any. Null is the default and
     * means `read_network` is refused for this session rather than answered with an empty list.
     *
     * It is a *separate* registration from [controller] because the two come from different places
     * and neither implies the other. A `WebViewController` knows nothing about the network — the
     * tap lives on the pool that built the WebView, on the platform's own interception hook or on a
     * script injected into the page — so there is no property to reach it through, and a host that
     * registers a bare controller genuinely has no traffic to offer.
     *
     * Where a tap can be got from today, which is not everywhere:
     *
     *  - **A pool, on all three platforms.** `AndroidWebViewPool.tap`, `IosWebViewPool.tap`,
     *    `KcefWebViewPool.tap`, or `FramePool.tap`, each wrapped in
     *    [dev.ggoggam.vitre.core.net.retainIn].
     *  - **A single Android WebView**, by handing `AndroidWebViewController` an
     *    `AndroidNetworkInterceptor`, which is itself a tap.
     *  - **Nowhere else yet.** A single `WKWebView` or CEF browser has no tap plumbing of its own —
     *    the scripted tap and the CEF interceptor are installed by their pools — and neither does
     *    the `VitreWebView` composable on any platform.
     */
    val network: NetworkLog? = null,
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
     *
     * [network] is what `read_network` reads. A pool's tap covers every lane in that pool and a
     * [dev.ggoggam.vitre.core.net.NetworkExchange] does not say which lane it came from, so
     * registering one pool's log under several lane sessions gives each of them the *pool's* whole
     * traffic. That is honest but coarse; a host that needs traffic attributed per lane wants a
     * pool per lane.
     */
    fun register(
        id: String,
        controller: WebViewController,
        description: String = "",
        network: NetworkLog? = null,
    ) {
        state.update { it + (id to WebViewSession(id, controller, description, network)) }
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
