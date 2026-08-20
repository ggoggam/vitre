package dev.ggoggam.vitre.mcp.testing

import dev.ggoggam.vitre.mcp.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Speaks to an [McpServer] the way a client would — over JSON text, not Kotlin calls.
 *
 * Going through the wire format is the whole point of these tests. The interesting failures in a
 * protocol server are the ones where the Kotlin is right and the JSON is not: a field spelled
 * differently, an error where a result belonged, a reply to a notification.
 */
class McpTestClient(
    private val server: McpServer,
) {
    private var nextId = 1

    /** Sends a legacy (handshake-era) request — no `_meta`, as a pre-2026 client sends it. */
    suspend fun legacy(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = send(method, params)

    /** Sends a modern request, carrying the per-request `_meta` the current revision requires. */
    suspend fun modern(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        protocolVersion: String = "2026-07-28",
        withCapabilities: Boolean = true,
    ): JsonObject {
        val withMeta =
            buildJsonObject {
                for ((key, value) in params) put(key, value)
                putJsonObject("_meta") {
                    put("io.modelcontextprotocol/protocolVersion", protocolVersion)
                    if (withCapabilities) putJsonObject("io.modelcontextprotocol/clientCapabilities") { }
                    putJsonObject("io.modelcontextprotocol/clientInfo") {
                        put("name", "test-client")
                        put("version", "1.0.0")
                    }
                }
            }
        return send(method, withMeta)
    }

    /** Calls a tool and returns the `CallToolResult` body. */
    suspend fun callTool(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): ToolCallResponse {
        val response =
            legacy(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
        val result = assertNotNull(response["result"], "expected a result, got: $response").jsonObject
        return ToolCallResponse(result)
    }

    /** Sends a notification and asserts the server stayed silent, as the spec requires. */
    suspend fun notify(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        val message =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
        assertNull(
            server.handle(JSON.encodeToString(JsonObject.serializer(), message)),
            "a notification must draw no reply at all, not even an empty one",
        )
    }

    private suspend fun send(
        method: String,
        params: JsonObject,
    ): JsonObject {
        val id = nextId++
        val message =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
        val raw =
            assertNotNull(
                server.handle(JSON.encodeToString(JsonObject.serializer(), message)),
                "request $method drew no reply",
            )
        val response = JSON.parseToJsonElement(raw).jsonObject
        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(id, response["id"]?.jsonPrimitive?.content?.toInt(), "reply id must match the request")
        return response
    }

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}

/** A `tools/call` reply, unpacked the way a client reads one. */
class ToolCallResponse(
    private val result: JsonObject,
) {
    val isError: Boolean get() = (result["isError"] as? JsonPrimitive)?.content?.toBoolean() ?: false

    val text: String
        get() =
            result["content"]
                ?.jsonArray
                ?.joinToString("\n") {
                    it.jsonObject["text"]
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                }.orEmpty()

    val structured: JsonObject? get() = result["structuredContent"] as? JsonObject
}

/** Pulls the `error` object out of a reply, failing if the reply carried a result instead. */
fun JsonObject.errorObject(): JsonObject = assertNotNull(this["error"], "expected an error, got: $this").jsonObject

fun JsonObject.errorCode(): Int = errorObject()["code"]!!.jsonPrimitive.content.toInt()

fun JsonObject.result(): JsonObject = assertNotNull(this["result"], "expected a result, got: $this").jsonObject

fun JsonElement.text(): String = jsonPrimitive.content
