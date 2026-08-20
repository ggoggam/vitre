package dev.ggoggam.vitre.mcp.transport

import dev.ggoggam.vitre.mcp.McpServer

/**
 * How MCP messages reach an [McpServer].
 *
 * ## Why there is only one implementation, and why it is the in-process one
 *
 * `docs/CONCURRENCY.md` left this open and asked for it to be decided before building rather than
 * after, because the candidates differ in security rather than in convenience:
 *
 *  - **In-process.** An agent running inside the app calls the server directly. The tools reach
 *    exactly as far as the app already does, and no new way in exists.
 *  - **A loopback socket.** An agent on a developer's machine drives the app over `adb forward`.
 *    Convenient, and it turns page automation into something *any* process that can reach the port
 *    can use: on Android a loopback listener is reachable by every other app on the device, with no
 *    permission required and nothing in the UI to say it is open. The WebView it exposes is often
 *    signed into the user's accounts, so what leaks is not "automation" but the session.
 *
 * So the module ships the first and not the second. That is a decision about what is *bundled*, not
 * a claim that the second is never right: an app that wants it can implement this interface, and
 * doing so is then a visible, deliberate act in that app's own code, reviewed as such — rather than
 * a capability every consumer of this library acquires by depending on it.
 *
 * A host that does add one owes its users, at minimum: an off-by-default switch, an explicit
 * loopback bind, a per-connection secret the client must present, and a visible indicator while the
 * port is open. Mobile has no stdio, so there is no third option that is private by construction.
 */
interface McpTransport {
    /**
     * Delivers one client message and returns the reply, or null when none is owed.
     *
     * Null is not an empty reply — it means the message was a notification, and sending anything at
     * all in response to one is a protocol violation. Framing (newline-delimited, length-prefixed,
     * an HTTP body) belongs to the implementation; the server has no opinion about it.
     */
    suspend fun exchange(message: String): String?
}

/**
 * The transport for an agent running inside the app: a direct call, with the message already in
 * hand.
 *
 * There is deliberately no plumbing here. An on-device agent has the server object; what it needs is
 * a name for the operation and a type both sides can agree on, not a pipe between two halves of the
 * same process.
 */
class InProcessMcpTransport(
    private val server: McpServer,
) : McpTransport {
    override suspend fun exchange(message: String): String? = server.handle(message)
}
