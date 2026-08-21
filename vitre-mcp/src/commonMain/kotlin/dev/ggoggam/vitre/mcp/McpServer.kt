package dev.ggoggam.vitre.mcp

import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageToolDocs
import dev.ggoggam.vitre.agent.session.SessionLeases
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.mcp.protocol.Era
import dev.ggoggam.vitre.mcp.protocol.FALLBACK_LEGACY_VERSION
import dev.ggoggam.vitre.mcp.protocol.JsonRpcErrors
import dev.ggoggam.vitre.mcp.protocol.JsonRpcException
import dev.ggoggam.vitre.mcp.protocol.JsonRpcRequest
import dev.ggoggam.vitre.mcp.protocol.MODERN_PROTOCOL_VERSION
import dev.ggoggam.vitre.mcp.protocol.MetaKeys
import dev.ggoggam.vitre.mcp.protocol.SUPPORTED_PROTOCOL_VERSIONS
import dev.ggoggam.vitre.mcp.protocol.ServerInfo
import dev.ggoggam.vitre.mcp.protocol.jsonRpcError
import dev.ggoggam.vitre.mcp.protocol.jsonRpcResult
import dev.ggoggam.vitre.mcp.protocol.obj
import dev.ggoggam.vitre.mcp.protocol.objOrEmpty
import dev.ggoggam.vitre.mcp.protocol.string
import dev.ggoggam.vitre.mcp.tools.WebViewTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the model is told this server is for, once, before it calls anything.
 *
 * Shared with every other adapter — a Koog host puts the same text in its system prompt — so that
 * an agent meeting Vitre through one of them has not been told something different about handles,
 * guessed selectors or leases than an agent meeting it through the other.
 */
private val INSTRUCTIONS = PageToolDocs.INSTRUCTIONS.trim()

/**
 * An MCP server over one or more WebViews.
 *
 * Transport-free by construction: it turns a request into a response and has no idea how either
 * travelled. That is deliberate — see [dev.ggoggam.vitre.mcp.transport.McpTransport] — and it is also
 * what makes the whole protocol layer testable without a socket.
 *
 * **Concurrency is not this class's problem, and must not become it.** Several tool calls in flight
 * at once queue on the lock `vitre-core` already holds every WebView behind. Reimplementing the
 * ordering here would place the app's own UI and its workflows *outside* those guarantees, which is
 * exactly the arrangement the library is built to avoid. What this module does own is the mapping
 * from a stateless call to a stateful WebView: the session registry, and leases.
 */
class McpServer(
    /** The WebViews this server exposes. The host registers them; the server never creates one. */
    val sessions: WebViewSessions,
    /**
     * Scope the lease-holding coroutines live in — the host's, so that tearing it down releases
     * every lease rather than leaving a WebView held by a server nobody is talking to any more.
     */
    scope: CoroutineScope,
    private val name: String = "vitre",
    private val version: String = "0.1.0",
    /**
     * Where step evaluation runs. `Dispatchers.Default` is right in production — selector strings and
     * JSON have no business on the WebView thread — and injectable for the same reason
     * [WorkflowEngine][dev.ggoggam.vitre.core.workflow.WorkflowEngine] makes it injectable: a test on a
     * virtual-time scheduler needs the work to stay on that scheduler, and one that escapes to a real
     * thread pool turns every ordering assertion into a race.
     */
    engineContext: CoroutineContext = Dispatchers.Default,
) {
    private val serverInfo = ServerInfo(name, version)

    /**
     * The page semantics, shared with every other adapter. This server owns the protocol around
     * them and nothing below it — see [WebViewTools].
     */
    val driver: PageDriver = PageDriver(sessions, SessionLeases(scope), engineContext)
    private val tools = WebViewTools(driver)

    /**
     * Handles one message, returning the reply as JSON text, or null if none is owed.
     *
     * Null means the message was a notification. It is not an error and not an empty reply: sending
     * anything at all in response to a notification is a protocol violation, so the distinction has
     * to survive all the way out to the transport.
     */
    suspend fun handle(message: String): String? {
        val parsed =
            try {
                JSON.parseToJsonElement(message)
            } catch (_: Exception) {
                return JSON.encodeToString(
                    JsonObject.serializer(),
                    jsonRpcError(null, JsonRpcErrors.PARSE_ERROR, "Message is not valid JSON"),
                )
            }
        val response = handle(parsed) ?: return null
        return JSON.encodeToString(JsonObject.serializer(), response)
    }

    /** As [handle], on already-parsed JSON. */
    suspend fun handle(message: JsonElement): JsonObject? {
        val request =
            try {
                JsonRpcRequest.parse(message)
            } catch (failure: JsonRpcException) {
                return jsonRpcError(null, failure.code, failure.message)
            }

        // Answered before anything else can fail: a notification gets no reply even when what it
        // asked for was nonsense.
        if (request.isNotification) return null
        val id = request.id ?: return null

        return try {
            val era = eraOf(request)
            jsonRpcResult(id, era.decorate(route(request), serverInfo))
        } catch (failure: JsonRpcException) {
            jsonRpcError(id, failure.code, failure.message, failure.data)
        } catch (cancellation: CancellationException) {
            // The caller gave up on us, or the host scope is going down. Neither is a protocol
            // error, and swallowing it here would break the caller's structured concurrency the way
            // the workflow engine documents at length.
            throw cancellation
        } catch (unexpected: Throwable) {
            jsonRpcError(
                id,
                JsonRpcErrors.INTERNAL_ERROR,
                unexpected.message ?: unexpected::class.simpleName ?: "Internal error",
            )
        }
    }

    /**
     * Which dialect this request speaks.
     *
     * Per request rather than per connection, because the modern protocol is explicitly stateless —
     * a server may not infer a client's version from an earlier message, and a stdio process is not
     * a session even when it looks like one.
     */
    private fun eraOf(request: JsonRpcRequest): Era {
        if (request.method == METHOD_INITIALIZE) return Era.LEGACY
        val declared = request.meta.string(MetaKeys.PROTOCOL_VERSION) ?: return Era.LEGACY
        if (declared !in SUPPORTED_PROTOCOL_VERSIONS) {
            throw JsonRpcException(
                JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol version",
                buildJsonObject {
                    put("supported", buildJsonArray { SUPPORTED_PROTOCOL_VERSIONS.forEach { add(JsonPrimitive(it)) } })
                    put("requested", declared)
                },
            )
        }
        // A modern request must carry its capabilities, and the spec makes rejecting it mandatory
        // rather than optional. Accepting it anyway would work today and leave a client believing it
        // had declared something it had not.
        if (request.meta[MetaKeys.CLIENT_CAPABILITIES] == null) {
            throw JsonRpcException(
                JsonRpcErrors.INVALID_PARAMS,
                "Request _meta is missing the required `${MetaKeys.CLIENT_CAPABILITIES}`",
            )
        }
        return if (declared == MODERN_PROTOCOL_VERSION) Era.MODERN else Era.LEGACY
    }

    private suspend fun route(request: JsonRpcRequest): JsonObject =
        when (request.method) {
            METHOD_INITIALIZE -> {
                initialize(request)
            }

            "server/discover" -> {
                discover()
            }

            "tools/list" -> {
                buildJsonObject {
                    put("tools", buildJsonArray { tools.definitions().forEach { add(it.toJson()) } })
                }
            }

            "tools/call" -> {
                callTool(request)
            }

            "ping" -> {
                JsonObject(emptyMap())
            }

            else -> {
                throw JsonRpcException(JsonRpcErrors.METHOD_NOT_FOUND, "Unknown method `${request.method}`")
            }
        }

    /**
     * The legacy handshake.
     *
     * A legacy client has no way to fall forward — it cannot be told "use a newer version" and act
     * on it — so when it asks for something unknown, the reply names a version this server does
     * support and lets the client decide whether it can live with it. Failing the request outright
     * would leave the user with a connection that dropped and no reason why.
     */
    private fun initialize(request: JsonRpcRequest): JsonObject {
        val requested = request.params.string("protocolVersion")
        val agreed = requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: FALLBACK_LEGACY_VERSION
        return buildJsonObject {
            put("protocolVersion", agreed)
            putJsonObject("capabilities") {
                // listChanged is false and honest: the tool list is a compile-time constant here.
                // Sessions come and go, but they are tool *arguments*, not tools.
                putJsonObject("tools") { put("listChanged", false) }
            }
            put("serverInfo", serverInfo.toJson())
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun discover(): JsonObject =
        buildJsonObject {
            put("supportedVersions", buildJsonArray { SUPPORTED_PROTOCOL_VERSIONS.forEach { add(JsonPrimitive(it)) } })
            putJsonObject("capabilities") { putJsonObject("tools") { } }
            put("instructions", INSTRUCTIONS)
        }

    private suspend fun callTool(request: JsonRpcRequest): JsonObject {
        val name =
            request.params.string("name")
                ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "tools/call is missing `name`")
        // An unknown tool is a protocol error, not a tool failure: the model was given the list and
        // cannot correct its way out of calling something that does not exist.
        if (tools.definitions().none { it.name == name }) {
            throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Unknown tool: $name")
        }
        // Absent `arguments` is legal for a tool that takes none, so it is an empty object rather
        // than an error — `list_sessions` is called that way by every client that omits empty params.
        return tools.call(name, request.params.objOrEmpty("arguments")).toJson()
    }

    private companion object {
        const val METHOD_INITIALIZE = "initialize"
        val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}
