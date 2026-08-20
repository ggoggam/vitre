package dev.ggoggam.vitre.mcp.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The protocol revisions this server speaks, newest first.
 *
 * More than one is not hedging. `2026-07-28` removed the `initialize` handshake in favour of
 * per-request metadata, so a server that implements only it cannot talk to any client built against
 * an earlier revision, and a server that implements only the older ones is a legacy server to every
 * new client. The spec's own compatibility matrix calls the both-eras server the case that works
 * everywhere, and the cost here is one branch — see [Era].
 */
internal val SUPPORTED_PROTOCOL_VERSIONS: List<String> =
    listOf(
        "2026-07-28",
        "2025-11-25",
        "2025-06-18",
    )

/** The revision from which requests carry their own version and there is no handshake. */
internal const val MODERN_PROTOCOL_VERSION: String = "2026-07-28"

/** The version offered to a legacy client that asked for something we do not implement. */
internal const val FALLBACK_LEGACY_VERSION: String = "2025-06-18"

internal object MetaKeys {
    const val PROTOCOL_VERSION: String = "io.modelcontextprotocol/protocolVersion"
    const val CLIENT_INFO: String = "io.modelcontextprotocol/clientInfo"
    const val CLIENT_CAPABILITIES: String = "io.modelcontextprotocol/clientCapabilities"
    const val SERVER_INFO: String = "io.modelcontextprotocol/serverInfo"
}

/**
 * Which dialect a single request is speaking.
 *
 * Decided per request rather than per connection, because the modern protocol is explicitly
 * stateless — a server may not infer anything from an earlier message on the same transport — and
 * because a stdio process is not a session even when it looks like one.
 */
internal enum class Era {
    /** `initialize` handshake, no `resultType` on results. */
    LEGACY,

    /** Per-request `_meta`, every result tagged with a `resultType`. */
    MODERN,
    ;

    /**
     * Adds the fields the era requires to a result body.
     *
     * `resultType` is mandatory from `2026-07-28` and unknown before it. Sending it to a legacy
     * client would probably be ignored, and "probably ignored" is not a property to build a
     * compatibility story on when the alternative is one `if`.
     */
    fun decorate(
        result: JsonObject,
        serverInfo: ServerInfo,
    ): JsonObject =
        buildJsonObject {
            if (this@Era == MODERN) put("resultType", "complete")
            for ((key, value) in result) put(key, value)
            if (this@Era == MODERN) {
                putJsonObject("_meta") {
                    put(MetaKeys.SERVER_INFO, serverInfo.toJson())
                }
            }
        }
}

internal data class ServerInfo(
    val name: String,
    val version: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("version", version)
        }
}

/**
 * One tool as `tools/list` reports it.
 *
 * [description] is not documentation, it is the prompt: it is the only thing the model reads before
 * deciding whether to call this tool and with what. Descriptions here therefore say what the tool
 * does *to the page* and what it gives back, and name the tool to reach for instead when this is the
 * wrong one — the misuse worth pre-empting is an agent guessing a CSS selector it has never seen.
 */
internal data class ToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("title", title)
            put("description", description)
            put("inputSchema", inputSchema)
        }
}

/**
 * The outcome of a `tools/call`.
 *
 * [isError] is the load-bearing field. A tool that fails because the element was not found has not
 * broken the protocol; it has produced a result the model can act on, and returning a JSON-RPC error
 * instead would strip the explanation out of the model's reach and leave it retrying blind.
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    /** Machine-readable form, when the tool has one — a snapshot's nodes, a session list. */
    val structured: JsonObject? = null,
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
            structured?.let { put("structuredContent", it) }
            put("isError", isError)
        }

    companion object {
        /** A tool failure the model should read and correct, rather than a protocol fault. */
        fun failure(message: String): ToolResult = ToolResult(message, isError = true)
    }
}

/** Builds a JSON Schema object for a tool's parameters. */
internal fun toolSchema(
    required: List<String> = emptyList(),
    properties: JsonObjectBuilderScope,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties(this) }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

internal typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit

/** One string property of a tool's input schema. */
internal fun kotlinx.serialization.json.JsonObjectBuilder.stringProp(
    name: String,
    description: String,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.intProp(
    name: String,
    description: String,
) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}

/** The three ways to name an element, shared by every element-addressing tool. */
internal fun kotlinx.serialization.json.JsonObjectBuilder.locatorProps(prefix: String = "") {
    stringProp(
        "${prefix}ref",
        "Handle from a previous `snapshot` of the current page, e.g. \"e7\". Prefer this: it names " +
            "an element you have actually seen, and fails loudly if the page has changed under you.",
    )
    stringProp(
        "${prefix}css",
        "CSS selector. Use only when you know the page's markup — do not guess one; take a " +
            "`snapshot` and use the `ref` it gives you instead.",
    )
    stringProp(
        "${prefix}xpath",
        "XPath expression, for what CSS cannot reach: matching on visible text " +
            "(//button[normalize-space()='Add to cart']), walking up with ancestor::, or " +
            "selecting an attribute as a node.",
    )
}
