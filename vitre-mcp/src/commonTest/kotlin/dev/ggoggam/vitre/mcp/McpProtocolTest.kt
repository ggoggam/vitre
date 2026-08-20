package dev.ggoggam.vitre.mcp

import dev.ggoggam.vitre.mcp.session.WebViewSessions
import dev.ggoggam.vitre.mcp.testing.McpTestClient
import dev.ggoggam.vitre.mcp.testing.errorCode
import dev.ggoggam.vitre.mcp.testing.errorObject
import dev.ggoggam.vitre.mcp.testing.result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "A client can connect to this server" — for both kinds of client there now are.
 *
 * The `2026-07-28` revision removed the `initialize` handshake in favour of per-request metadata, so
 * "an MCP server" is no longer one thing. A server that implements only the new shape cannot talk to
 * any client built before it; one that implements only the old shape is a legacy server to every
 * client built after. The spec's compatibility matrix says the dual-era server is the one that works
 * in every combination, and these tests pin both halves.
 */
class McpProtocolTest {
    private fun client(scope: CoroutineScope) = McpTestClient(McpServer(WebViewSessions(), scope, engineContext = EmptyCoroutineContext))

    @Test
    fun a_legacy_client_gets_the_handshake_it_expects() =
        runTest {
            val response =
                client(this).legacy(
                    "initialize",
                    buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                        put("capabilities", buildJsonObject { })
                    },
                )

            val result = response.result()
            assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
            assertTrue("tools" in result["capabilities"]!!.jsonObject, "the server must declare the tools capability")
            assertTrue(
                result["instructions"]!!.jsonPrimitive.content.contains("snapshot"),
                "instructions are the only chance to tell the model to look before it acts",
            )
            // A legacy client has never seen `resultType` and the field did not exist in its
            // revision, so sending it is a guess about that client's tolerance.
            assertNull(result["resultType"], "resultType belongs to the modern era only")
        }

    @Test
    fun a_legacy_client_asking_for_an_unknown_version_is_offered_one_that_works() =
        runTest {
            val result =
                client(this)
                    .legacy(
                        "initialize",
                        buildJsonObject { put("protocolVersion", "1999-01-01") },
                    ).result()

            // A legacy client has no fall-forward mechanism: told only "no", it drops the connection
            // and the user sees nothing useful. Naming a version we do speak gives it something to
            // act on.
            assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
        }

    @Test
    fun a_modern_client_discovers_the_server_without_a_handshake() =
        runTest {
            val result = client(this).modern("server/discover").result()

            assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
            val supported = result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("2026-07-28" in supported, "$supported")
            assertTrue("2025-06-18" in supported, "the old revisions are still spoken: $supported")
            assertEquals(
                "vitre",
                result["_meta"]!!
                    .jsonObject["io.modelcontextprotocol/serverInfo"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun a_version_this_server_does_not_speak_is_refused_with_the_ones_it_does() =
        runTest {
            val response = client(this).modern("tools/list", protocolVersion = "1900-01-01")

            // -32022 is spec-defined precisely so the client can retry rather than give up, and the
            // retry is only possible if the error carries the list.
            assertEquals(-32022, response.errorCode())
            val supported =
                response
                    .errorObject()["data"]!!
                    .jsonObject["supported"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertTrue("2026-07-28" in supported, "$supported")
        }

    @Test
    fun a_modern_request_without_its_declared_capabilities_is_rejected() =
        runTest {
            val response = client(this).modern("tools/list", withCapabilities = false)

            // The server never reads them today, and accepting the request anyway would leave a
            // client believing it had declared something it had not — which breaks the moment the
            // server does start reading them.
            assertEquals(-32602, response.errorCode())
        }

    @Test
    fun a_notification_draws_no_reply() =
        runTest {
            // The assertion is inside notify(): replying to a notification is a protocol violation,
            // and `notifications/initialized` is the one every legacy client sends.
            client(this).notify("notifications/initialized")
        }

    @Test
    fun an_unknown_method_is_a_protocol_error_rather_than_silence() =
        runTest {
            assertEquals(-32601, client(this).legacy("resources/list").errorCode())
        }

    @Test
    fun a_malformed_message_is_answered_rather_than_dropped() =
        runTest {
            val server = McpServer(WebViewSessions(), this, engineContext = EmptyCoroutineContext)

            val reply = server.handle("{not json")

            assertTrue(reply != null && "-32700" in reply, "a parse error still owes the client a reply: $reply")
        }
}
