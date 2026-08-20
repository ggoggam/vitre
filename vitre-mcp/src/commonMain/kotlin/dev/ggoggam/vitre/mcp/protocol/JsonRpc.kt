package dev.ggoggam.vitre.mcp.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The JSON-RPC 2.0 envelope every MCP message travels in. */
internal const val JSONRPC_VERSION: String = "2.0"

/**
 * The error codes this server emits.
 *
 * MCP reserves `-32020`..`-32099` for the specification and forbids implementations from inventing
 * codes in it, so everything here is either a standard JSON-RPC code or one the spec defines by
 * name. The distinction that matters in practice is not the number: a *protocol* error says the
 * request was malformed and the model cannot fix it, whereas a failed tool call comes back as an
 * ordinary result with `isError` set, because that one the model can read and retry. Reporting a
 * missing element as `-32603` would hide it from the only party able to do anything about it.
 */
internal object JsonRpcErrors {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603

    /** Spec-defined: the request named a protocol version this server does not implement. */
    const val UNSUPPORTED_PROTOCOL_VERSION: Int = -32022
}

/**
 * One parsed incoming message.
 *
 * [id] is absent for notifications, and that absence is the whole of the difference: a notification
 * gets no reply, not even an error, so it has to be recognised before anything else can go wrong.
 */
internal data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject,
) {
    val isNotification: Boolean get() = id == null

    /** The `_meta` a modern client attaches to every request. Empty for legacy clients. */
    val meta: JsonObject get() = params["_meta"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())

    companion object {
        /**
         * @throws JsonRpcException if [element] is not a well-formed JSON-RPC request object.
         */
        fun parse(element: JsonElement): JsonRpcRequest {
            val obj =
                element as? JsonObject
                    ?: throw JsonRpcException(JsonRpcErrors.INVALID_REQUEST, "Request must be a JSON object")
            val method =
                (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw JsonRpcException(JsonRpcErrors.INVALID_REQUEST, "Request is missing a string `method`")
            // A null id is a notification in JSON-RPC 2.0's own reading and forbidden outright by
            // MCP, so both spellings collapse onto "expects no reply" rather than onto an id of null.
            val id = obj["id"]?.takeUnless { it is JsonNull }
            val params = obj["params"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
            return JsonRpcRequest(id, method, params)
        }
    }
}

/** Thrown anywhere a request cannot be answered with a result. Carries the wire code. */
internal class JsonRpcException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null,
) : RuntimeException(message)

internal fun jsonRpcResult(
    id: JsonElement,
    result: JsonObject,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        put("id", id)
        put("result", result)
    }

internal fun jsonRpcError(
    id: JsonElement?,
    code: Int,
    message: String,
    data: JsonElement? = null,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        // An id of null is the correct wire form when the request was too malformed to read one.
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }

/** Reads a required string member, failing the way a malformed request should. */
internal fun JsonObject.requireString(name: String): String =
    string(name) ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Missing required string parameter `$name`")

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name]?.let { it as? JsonObject }

internal fun JsonObject.objOrEmpty(name: String): JsonObject = obj(name) ?: JsonObject(emptyMap())

internal fun JsonElement.asObject(): JsonObject = jsonObject
