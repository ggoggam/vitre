package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.mcp.transport.McpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * One tool as `tools/list` advertises it. The description is what a model reads before calling.
 *
 * [name] has no default, unlike the two fields beside it: a tool whose name did not arrive cannot be
 * called, so a reply missing one is a server this client cannot talk to rather than a tool with an
 * empty name in the list. A missing title or description only costs the reader some prose.
 */
@Serializable
data class McpTool(
    val name: String,
    val title: String = "",
    val description: String = "",
)

/**
 * What a `tools/call` came back with.
 *
 * [isError] is the distinction the whole agent loop turns on: a tool that failed because the
 * selector matched nothing is a *result* the model reads and corrects, not a broken connection.
 * A transport or protocol fault arrives here the same way rather than as an exception, because to
 * the loop above it the difference is academic — both are "that did not work, try something else".
 *
 * Not a wire type: [ToolCallResult] is, and this is that flattened to the one text block every tool
 * in this server returns.
 */
data class McpToolOutput(
    val text: String,
    val isError: Boolean,
)

/**
 * The client half of MCP, in the ~100 lines it actually takes.
 *
 * The sample needs one because the agent loop has to *read* what a tool returned — pick a `ref` out
 * of a snapshot, total up a table — and regex over the raw envelope stops being adequate the moment
 * anything depends on the answer. Everything here is ordinary JSON-RPC: an id, a method, params,
 * and a reply that is either `result` or `error`.
 *
 * Both halves of that exchange are payload classes rather than hand-assembled JSON, for the reason
 * `bridge.request` gives on the other side of the library: the round trip is one call, and
 * `kotlinx.serialization` does the encode and the decode. What it buys here beyond brevity is that
 * `isError` is a `Boolean`. Read out of a `JsonObject` it was a string compared against `"true"`,
 * which is correct only for as long as nobody looks at it twice.
 *
 * The handshake is the legacy one (`initialize`), which is the shape every client built before the
 * `2026-07-28` revision speaks. The server also accepts the modern form, where there is no handshake
 * and each request carries its own `_meta.protocolVersion` — worth knowing about, not worth the
 * extra ceremony in a sample whose point is the tools.
 */
class McpClient(
    private val transport: McpTransport,
) {
    /**
     * One request at a time.
     *
     * The in-process transport returns the reply to the call that sent it, so nothing here has to
     * match ids to replies — but a real transport is a pipe, and a client that let two requests
     * share an id would have no way to tell the answers apart. Serialising is what keeps the ids
     * unique, and a WebView runs one operation at a time anyway.
     */
    private val turnstile = Mutex()
    private var nextId = 1

    /** Agrees a protocol version and returns it, so the caller can show what was negotiated. */
    suspend fun initialize(): String =
        request(
            "initialize",
            InitializeResult.serializer(),
            JSON.encodeToJsonElement(
                InitializeParams(
                    protocolVersion = PROTOCOL_VERSION,
                    capabilities = ClientCapabilities(),
                    clientInfo = ClientInfo(name = "vitre-sample-chat", version = "0.1.0"),
                ),
            ),
        ).protocolVersion ?: PROTOCOL_VERSION

    suspend fun listTools(): List<McpTool> = request("tools/list", ToolsListResult.serializer()).tools

    /**
     * Calls one tool and flattens its reply to text plus a flag.
     *
     * A tool result is a list of content blocks so that a tool can return an image or embedded
     * resource; every tool in this server returns exactly one text block, so joining them is both
     * correct and all the chat pane can render.
     *
     * [arguments] stays a [JsonObject] where everything around it is typed, and that is the honest
     * shape: the whole point of a tool call is that the *model* chose these fields, against a schema
     * it read at runtime. There is no class to name here — a `snapshot`'s arguments and an
     * `extract_rows`'s have nothing in common but being JSON.
     */
    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): McpToolOutput {
        val result =
            try {
                request(
                    "tools/call",
                    ToolCallResult.serializer(),
                    JSON.encodeToJsonElement(ToolCallParams(name = name, arguments = arguments)),
                )
            } catch (failure: McpProtocolException) {
                // An unknown tool or a malformed call lands here rather than in `isError`. The model
                // still has to see it — it is the one who called wrongly — so it is folded into the
                // same shape instead of propagating out of the loop.
                return McpToolOutput(failure.message, isError = true)
            }
        val text = result.content.mapNotNull { it.text }.joinToString("\n")
        return McpToolOutput(text.ifBlank { "(the tool returned no text)" }, result.isError)
    }

    /**
     * Sends one request and decodes its result with [result].
     *
     * An explicit serializer rather than a `reified R`, because every caller is a method on this
     * class that knows its own result type — the type parameter buys nothing that inlining into the
     * three call sites below would not.
     *
     * A result that does not decode is reported as [McpProtocolException] rather than let out as a
     * `SerializationException`, so that the one kind of failure [callTool] folds into `isError` is
     * the only kind that reaches it. A server whose reply this client cannot read is exactly as
     * actionable to the model as one that refused the call.
     */
    private suspend fun <R> request(
        method: String,
        result: DeserializationStrategy<R>,
        params: JsonElement? = null,
    ): R =
        turnstile.withLock {
            val call = JsonRpcCall(jsonrpc = JSONRPC_VERSION, id = nextId++, method = method, params = params)
            val reply =
                transport.exchange(JSON.encodeToString(JsonRpcCall.serializer(), call))
                    ?: throw McpProtocolException("`$method` got no reply, and it was not a notification.")
            val parsed =
                runCatching { JSON.decodeFromString(JsonRpcReply.serializer(), reply) }
                    .getOrElse { throw McpProtocolException("`$method` replied with something that is not a JSON-RPC response.") }
            parsed.error?.let { throw McpProtocolException("$method failed (${it.code}): ${it.message}") }
            val body =
                parsed.result
                    ?: throw McpProtocolException("`$method` replied with neither a result nor an error.")
            runCatching { JSON.decodeFromJsonElement(result, body) }
                .getOrElse { throw McpProtocolException("`$method` replied with a result this client cannot read: ${it.message}") }
        }

    private companion object {
        /**
         * The newest revision that still uses the `initialize` handshake. The server offers three;
         * asking for one it does not know would get a counter-offer rather than a failure, which is
         * the whole point of the legacy handshake.
         */
        const val PROTOCOL_VERSION = "2025-11-25"
        const val JSONRPC_VERSION = "2.0"

        /**
         * `ignoreUnknownKeys` is what makes each class below a *view* of the reply rather than an
         * exhaustive description of it: the server sends a tool's `inputSchema` and may tag a result
         * with `resultType` and `_meta`, none of which this client has any use for, and none of
         * which should be a decode failure.
         */
        val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}

/** The connection or the protocol failed — as opposed to a tool reporting that it could not. */
class McpProtocolException(
    override val message: String,
) : RuntimeException(message)

// ── The wire ─────────────────────────────────────────────────────────────────────────────────────
//
// Private, and deliberately not shared with the server's own types in `vitre-mcp`: a client
// that imported the server's classes would be testing them against themselves. These say what this
// client expects to find, which is the thing worth checking.
//
// Nothing here declares a default it also means to send. `Json` omits a property equal to its
// default, so `jsonrpc` carries no default and is passed at its one construction site — the
// alternative is a `"2.0"` that quietly never reaches the wire.

@Serializable
private data class JsonRpcCall(
    val jsonrpc: String,
    val id: Int,
    val method: String,
    /** Omitted rather than sent as null when a method takes none, which `tools/list` does. */
    val params: JsonElement? = null,
)

/** Exactly one of [result] and [error] is present; JSON-RPC forbids both and forbids neither. */
@Serializable
private data class JsonRpcReply(
    val result: JsonElement? = null,
    val error: JsonRpcErrorBody? = null,
)

@Serializable
private data class JsonRpcErrorBody(
    val code: Int,
    val message: String = "unknown error",
)

/** Empty, and encoding as `{}` is the point: it declares that this client claims no capabilities. */
@Serializable
private class ClientCapabilities

@Serializable
private data class ClientInfo(
    val name: String,
    val version: String,
)

@Serializable
private data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: ClientInfo,
)

/** Null when a server answered the handshake without naming a version, which is not fatal. */
@Serializable
private data class InitializeResult(
    val protocolVersion: String? = null,
)

@Serializable
private data class ToolsListResult(
    val tools: List<McpTool> = emptyList(),
)

@Serializable
private data class ToolCallParams(
    val name: String,
    val arguments: JsonObject,
)

@Serializable
private data class ToolCallResult(
    val content: List<ContentBlock> = emptyList(),
    /**
     * Absent means false, per the spec. This is the field the agent loop branches on, and reading it
     * as a `Boolean` rather than as the string `"true"` is most of the reason this file has types.
     */
    val isError: Boolean = false,
)

/** [text] is null for a block that is not text — an image, an embedded resource — which is skipped. */
@Serializable
private data class ContentBlock(
    val type: String = "text",
    val text: String? = null,
)
