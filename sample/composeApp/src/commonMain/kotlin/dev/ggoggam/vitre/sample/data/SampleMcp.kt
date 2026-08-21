package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.mcp.McpServer
import dev.ggoggam.vitre.mcp.transport.InProcessMcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject

/**
 * How a host app exposes its WebView to an agent — the whole of it.
 *
 * There are two moving parts and no more. A [WebViewSessions] registry, which the UI puts its
 * controller into when the WebView is mounted and takes it out of when it goes away, and an
 * [McpServer] reading from it. The server holds no WebView of its own and creates none: it can only
 * drive what the app has explicitly handed it, which is what keeps "the app decides what an agent
 * can touch" true rather than aspirational.
 *
 * The transport is the in-process one, meaning an agent living inside this app. Nothing here opens a
 * port — see [dev.ggoggam.vitre.mcp.transport.McpTransport] for why that is a deliberate omission
 * rather than an unfinished edge.
 */
object SampleMcp {
    /**
     * One WebView is on screen at a time in this gallery, so one name.
     *
     * Shared by every screen that mounts one, rather than a name per screen, because with two
     * sessions registered every tool call would have to name which — see
     * [WebViewSessions.resolve], which refuses to guess. A host with genuinely concurrent WebViews
     * gives each its own id and the agent picks from `list_sessions`.
     */
    const val SESSION_ID: String = "gallery"

    val sessions = WebViewSessions()

    /**
     * Scoped to the application rather than to a composition. Leases are held by coroutines living
     * here, so a scope that died with a screen would drop a claim on a WebView the moment the user
     * rotated the phone.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val server: McpServer by lazy { McpServer(sessions, scope, name = "vitre-sample") }

    val transport: InProcessMcpTransport by lazy { InProcessMcpTransport(server) }

    /**
     * The client the sample's own agent talks through.
     *
     * Going through JSON rather than calling the tools in Kotlin is the point: everything between
     * the message and the WebView — parsing, session lookup, the step the tool maps onto, the
     * rendering of the result — is what a unit test on a fake page cannot check.
     */
    val client: McpClient by lazy { McpClient(transport) }

    /**
     * The two calls an agent opens with, over the wire, against whatever page is on screen.
     *
     * This is the runner's one-shot panel; the chat screen runs the same tools in a loop.
     */
    suspend fun snapshotThroughMcp(): String {
        val tools = client.listTools()
        val snapshot = client.callTool("snapshot", JsonObject(emptyMap()))
        return "${tools.size} tools available\n\n${snapshot.text}"
    }
}
