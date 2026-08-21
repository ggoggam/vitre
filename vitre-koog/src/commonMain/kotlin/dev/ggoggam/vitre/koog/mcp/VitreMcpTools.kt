package dev.ggoggam.vitre.koog.mcp

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import dev.ggoggam.vitre.koog.tools.ambientLease
import dev.ggoggam.vitre.mcp.McpServer
import dev.ggoggam.vitre.mcp.transport.InProcessMcpTransport
import dev.ggoggam.vitre.mcp.transport.McpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The other way in: a Vitre MCP server's tools, as Koog tools.
 *
 * `vitreWebViewTools` is the one to reach for — it is typed, it needs no server, and its arguments
 * are checked before a call is made. This exists for the host that already stands one up, which is
 * a real situation rather than a hypothetical: an app that exposes its WebViews over MCP for a
 * desktop client, or one whose own agent chat already speaks the protocol, would otherwise be
 * running two descriptions of the same thirteen tools and maintaining both.
 *
 * What comes back is discovered at runtime from `tools/list`, so a tool added to the server appears
 * here without a line of code — and the arguments are an untyped [JsonObject] checked by the server
 * rather than by the compiler, which is the price of that.
 *
 * ```kotlin
 * val server = McpServer(sessions, scope)
 * val registry = vitreMcpToolRegistry(server) // discovery suspends, so not inside ToolRegistry { }
 * ```
 *
 * There is no socket anywhere in this. [InProcessMcpTransport] is a direct call, which is the only
 * transport the module ships and deliberately so — see [McpTransport] for what a loopback listener
 * would expose on a WebView signed into the user's accounts.
 */
suspend fun vitreMcpTools(transport: McpTransport): List<ToolBase<*, *>> {
    val client = McpBridgeClient(transport)
    val listed =
        client.request("tools/list")["tools"]?.jsonArray
            ?: throw IllegalStateException("`tools/list` returned no `tools` array; this is not a Vitre MCP server.")
    return listed.map { entry -> McpBridgeTool(client, entry.jsonObject.toToolDescriptor()) }
}

/** As [vitreMcpTools], against a server held directly. */
suspend fun vitreMcpTools(server: McpServer): List<ToolBase<*, *>> = vitreMcpTools(InProcessMcpTransport(server))

/** A registry of nothing but the bridged tools. */
suspend fun vitreMcpToolRegistry(transport: McpTransport): ToolRegistry {
    val bridged = vitreMcpTools(transport)
    return ToolRegistry { tools(bridged) }
}

/**
 * As [vitreMcpToolRegistry], against a server held directly.
 *
 * The discovery is a suspending call, so it cannot happen inside `ToolRegistry { }` — which is why
 * a host bridging a server wants this rather than the builder.
 */
suspend fun vitreMcpToolRegistry(server: McpServer): ToolRegistry = vitreMcpToolRegistry(InProcessMcpTransport(server))

/**
 * The server's `instructions` — what it tells a model the tools are for, before it calls any.
 *
 * A Koog host puts this in the agent's system prompt, which is the job MCP's `instructions` field
 * does for an MCP client. Equal to [dev.ggoggam.vitre.agent.PageToolDocs.INSTRUCTIONS] trimmed, but
 * read from the server so that a host which customised them gets what it customised.
 */
suspend fun vitreMcpInstructions(transport: McpTransport): String =
    McpBridgeClient(transport)
        .request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("clientInfo", buildJsonObject { put("name", "vitre-koog") })
                put("capabilities", buildJsonObject { })
            },
        )["instructions"]
        ?.jsonPrimitive
        ?.content
        .orEmpty()

// ── The client ─────────────────────────────────────────────────────────────────────────────────

/**
 * A JSON-RPC client small enough to be honest about what it is.
 *
 * The legacy dialect on purpose: from `2026-07-28` every request must carry its own protocol version
 * *and* its capabilities in `_meta`, and the server rejects one that does not. Nothing here needs
 * what that revision added, and a request without the handshake is read as legacy — so the older
 * spelling is the one with fewer ways to be wrong.
 */
private class McpBridgeClient(
    private val transport: McpTransport,
) {
    // Every bridged tool shares one client, and Koog runs a turn's tool calls concurrently, so
    // `nextId++` on a bare var is a read-modify-write two coroutines can interleave on. The mutex is
    // held only across the increment, not across the exchange, so calls still overlap.
    private val ids = Mutex()
    private var nextId = 0

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val id = ids.withLock { nextId++ }
        val body =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
        val reply =
            transport.exchange(JSON.encodeToString(JsonObject.serializer(), body))
                ?: throw IllegalStateException("`$method` got no reply; a request is not a notification.")
        val parsed = JSON.parseToJsonElement(reply).jsonObject
        parsed["error"]?.jsonObject?.let { error ->
            // A protocol error, not a tool failure — an unknown method or a malformed call, which no
            // model can correct its way out of. Tool failures arrive as results with `isError` set
            // and are handled where the result is read.
            throw IllegalStateException(
                "MCP `$method` failed: ${error["message"]?.jsonPrimitive?.content ?: error}",
            )
        }
        return parsed["result"]?.jsonObject
            ?: throw IllegalStateException("MCP `$method` returned neither a result nor an error.")
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

// ── The tool ───────────────────────────────────────────────────────────────────────────────────

/** One `tools/call`, wearing Koog's tool interface. */
private class McpBridgeTool(
    private val client: McpBridgeClient,
    descriptor: ToolDescriptor,
) : ToolBase<JSONObject, McpBridgeTool.Result>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<Result>(),
        descriptor = descriptor,
    ) {
    /**
     * The text an MCP tool answered with.
     *
     * `@Serializable` because Koog encodes a tool's result through its declared `resultType` on the
     * way into the transcript, which is a separate call from [encodeResultToString] and needs a real
     * serializer. Overriding the string encoder is not enough: without this the page action succeeds
     * and the model is then told the tool failed to serialize, and retries something it already did.
     */
    @Serializable
    data class Result(
        val text: String,
    )

    /**
     * Whether this tool takes an optional `lease`.
     *
     * The run's ambient lease is threaded in through that argument. `release_lease` also has a
     * `lease` — a *required* one, naming what to give back — so requiring optionality is what keeps
     * the feature's own claim from being handed to the model's release call.
     */
    private val takesAmbientLease: Boolean = descriptor.optionalParameters.any { it.name == "lease" }

    override suspend fun execute(
        args: JSONObject,
        metadata: ToolCallMetadata,
    ): Result {
        val arguments = args.toKotlinxJsonObject()
        val result =
            client.request(
                "tools/call",
                buildJsonObject {
                    put("name", descriptor.name)
                    put("arguments", arguments.withAmbientLease(metadata))
                },
            )
        val text =
            result["content"]
                ?.jsonArray
                ?.mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("\n")
                .orEmpty()
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        // Raised as a Koog validation failure rather than returned as text, so that a page failure
        // reaches the model the same way through this adapter as through the typed one: as the
        // message alone, and as something `interceptToolValidationFailed` can see. MCP's `isError`
        // and Koog's ToolException are the same idea — a result the model reads and corrects — in
        // two vocabularies, and a host should not have to know which one produced its transcript.
        if (isError) throw ToolException.ValidationFailure(text)
        return Result(text)
    }

    /**
     * Adds the run's lease to a call that did not name one.
     *
     * The typed tools read the ambient lease straight off the metadata; over the bridge the only
     * channel to the server is the arguments, so it has to be written in. Without this a host that
     * installs `VitrePageLease` and registers bridged tools gets a feature holding the WebView and
     * calls that never quote it — every one of them queuing on the lock the feature holds until the
     * TTL runs out.
     */
    private fun JsonObject.withAmbientLease(metadata: ToolCallMetadata): JsonObject {
        if (!takesAmbientLease || "lease" in this) return this
        val lease = ambientLease(this["session"]?.jsonPrimitive?.content, metadata) ?: return this
        return JsonObject(this + ("lease" to JsonPrimitive(lease)))
    }

    override fun encodeResultToString(
        result: Result,
        serializer: JSONSerializer,
    ): String = result.text
}

// ── Schema translation ─────────────────────────────────────────────────────────────────────────

/**
 * One `tools/list` entry as a Koog [ToolDescriptor].
 *
 * Handles the subset of JSON Schema the Vitre server actually emits — objects of strings, integers,
 * booleans, arrays, and the one nested `additionalProperties` map that `extract_rows` uses for its
 * columns. Anything else throws rather than guessing, because a parameter silently translated to
 * the wrong type is a tool the model cannot call and cannot be told why.
 */
private fun JsonObject.toToolDescriptor(): ToolDescriptor {
    val name =
        this["name"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("A `tools/list` entry has no `name`.")
    val schema = this["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    val parameters =
        schema["properties"]?.jsonObject.orEmpty().map { (property, definition) ->
            definition.jsonObject.toParameter(property)
        }
    return ToolDescriptor(
        name = name,
        // `title` is a display string for a human picking tools in a client; `description` is the
        // prompt. Only the second one belongs in a descriptor the model reads.
        description = this["description"]?.jsonPrimitive?.content.orEmpty(),
        requiredParameters = parameters.filter { it.name in required },
        optionalParameters = parameters.filter { it.name !in required },
    )
}

private fun JsonObject.toParameter(name: String): ToolParameterDescriptor =
    ToolParameterDescriptor(
        name = name,
        description = this["description"]?.jsonPrimitive?.content.orEmpty(),
        type = toParameterType(name),
    )

private fun JsonObject.toParameterType(path: String): ToolParameterType =
    when (val type = this["type"]?.jsonPrimitive?.content) {
        "string" -> {
            this["enum"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.let { ToolParameterType.Enum(it.toTypedArray()) }
                ?: ToolParameterType.String
        }

        "integer" -> {
            ToolParameterType.Integer
        }

        "number" -> {
            ToolParameterType.Float
        }

        "boolean" -> {
            ToolParameterType.Boolean
        }

        "array" -> {
            ToolParameterType.List(
                itemsType =
                    this["items"]?.jsonObject?.toParameterType("$path[]")
                        ?: throw IllegalStateException("Array parameter `$path` does not say what it holds."),
            )
        }

        "object" -> {
            val nestedRequired = this["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val additional = this["additionalProperties"]?.jsonObject
            ToolParameterType.Object(
                properties =
                    this["properties"]?.jsonObject.orEmpty().map { (property, definition) ->
                        definition.jsonObject.toParameter(property)
                    },
                requiredProperties = nestedRequired,
                additionalProperties = additional != null,
                additionalPropertiesType = additional?.toParameterType("$path.*"),
            )
        }

        else -> {
            throw IllegalStateException(
                "Parameter `$path` has an unsupported JSON Schema type `$type`. The Koog bridge " +
                    "translates the subset the Vitre server emits; a new one needs a case here.",
            )
        }
    }
