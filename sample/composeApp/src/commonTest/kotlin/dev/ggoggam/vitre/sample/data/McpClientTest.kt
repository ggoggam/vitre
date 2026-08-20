package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.mcp.McpServer
import dev.ggoggam.vitre.mcp.session.WebViewSessions
import dev.ggoggam.vitre.mcp.transport.InProcessMcpTransport
import dev.ggoggam.vitre.mcp.transport.McpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two halves, and both are needed.
 *
 * The scripted transport pins what the payload classes put on the wire and what they make of a
 * reply, including the shapes a server is allowed to send but this one never does. The live server
 * pins that those classes describe *this* server: a client tested only against replies the test
 * wrote itself agrees with nothing but itself.
 */
class McpClientTest {
    /** Answers with whatever the test scripted, and keeps what it was asked. */
    private class ScriptedTransport(
        private val reply: (String) -> String?,
    ) : McpTransport {
        val sent = mutableListOf<String>()

        override suspend fun exchange(message: String): String? {
            sent += message
            return reply(message)
        }
    }

    private fun ok(result: String) = ScriptedTransport { """{"jsonrpc":"2.0","id":1,"result":$result}""" }

    // ── What goes on the wire ──────────────────────────────────────────────────────────────────

    /**
     * The typed call is the hand-built envelope it replaced, byte for byte. Serialization is what
     * builds the JSON now; it is not allowed to have changed the JSON.
     */
    @Test
    fun a_typed_call_is_byte_identical_to_the_envelope_it_replaces() =
        runTest {
            val transport = ok("""{"content":[{"type":"text","text":"Loaded."}],"isError":false}""")

            McpClient(transport).callTool("navigate", buildJsonObject { put("url", "https://example.test/") })

            assertEquals(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":""" +
                    """{"name":"navigate","arguments":{"url":"https://example.test/"}}}""",
                transport.sent.single(),
            )
        }

    /** A method that takes none omits `params` rather than sending null, as it did before. */
    @Test
    fun a_call_with_no_params_omits_them() =
        runTest {
            val transport = ok("""{"tools":[]}""")

            McpClient(transport).listTools()

            assertEquals("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", transport.sent.single())
        }

    @Test
    fun the_handshake_declares_a_version_a_client_and_no_capabilities() =
        runTest {
            val transport = ok("""{"protocolVersion":"2025-11-25"}""")

            assertEquals("2025-11-25", McpClient(transport).initialize())
            assertEquals(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
                    """"capabilities":{},"clientInfo":{"name":"vitre-sample-chat","version":"0.1.0"}}}""",
                transport.sent.single(),
            )
        }

    /** Ids are what tells two answers apart on a transport that is a pipe. */
    @Test
    fun ids_advance_across_calls() =
        runTest {
            val transport = ok("""{"tools":[]}""")
            val client = McpClient(transport)

            client.listTools()
            client.listTools()

            assertTrue(transport.sent[0].contains("\"id\":1"))
            assertTrue(transport.sent[1].contains("\"id\":2"))
        }

    // ── What comes back ────────────────────────────────────────────────────────────────────────

    /**
     * The reason this file has payload classes. Read out of a `JsonObject`, `isError` was a string
     * compared against `"true"`; a spec-conformant server that omits the field when false, or sends
     * it as the boolean it is, has to arrive as `false` either way.
     */
    @Test
    fun is_error_is_a_boolean_however_the_server_spells_it() =
        runTest {
            val failed = ok("""{"content":[{"type":"text","text":"No such element."}],"isError":true}""")
            assertTrue(McpClient(failed).callTool("click", JsonObject(emptyMap())).isError)

            val omitted = ok("""{"content":[{"type":"text","text":"Clicked."}]}""")
            assertFalse(McpClient(omitted).callTool("click", JsonObject(emptyMap())).isError)
        }

    /** A tool may answer with an image or a resource; a text pane can only render the text blocks. */
    @Test
    fun non_text_content_blocks_are_skipped() =
        runTest {
            val transport =
                ok(
                    """{"content":[{"type":"text","text":"first"},{"type":"image","data":"…"},""" +
                        """{"type":"text","text":"second"}]}""",
                )

            assertEquals("first\nsecond", McpClient(transport).callTool("snapshot", JsonObject(emptyMap())).text)
        }

    /** Unknown keys are the server saying more than this client asked; not a failure. */
    @Test
    fun fields_this_client_does_not_declare_are_ignored() =
        runTest {
            val transport =
                ok(
                    """{"resultType":"complete","tools":[{"name":"snapshot","title":"Snapshot the page",""" +
                        """"description":"…","inputSchema":{"type":"object"}}],"_meta":{"x":1}}""",
                )

            assertEquals(listOf("snapshot"), McpClient(transport).listTools().map { it.name })
        }

    /**
     * A protocol error is not a tool failure, but the model is the one who called wrongly, so it
     * reaches the loop as a readable result rather than as an exception thrown through it.
     */
    @Test
    fun a_json_rpc_error_becomes_a_readable_tool_failure() =
        runTest {
            val transport =
                ScriptedTransport {
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: fly"}}"""
                }

            val output = McpClient(transport).callTool("fly", JsonObject(emptyMap()))

            assertTrue(output.isError)
            assertEquals("tools/call failed (-32602): Unknown tool: fly", output.text)
        }

    /** A reply this client cannot decode is a protocol fault, not a `SerializationException`. */
    @Test
    fun a_result_that_does_not_decode_is_reported_as_a_tool_failure() =
        runTest {
            val transport = ok("""{"content":"not a list of blocks"}""")

            val output = McpClient(transport).callTool("snapshot", JsonObject(emptyMap()))

            assertTrue(output.isError)
            assertTrue(
                output.text.startsWith("`tools/call` replied with a result this client cannot read"),
                "was: ${output.text}",
            )
        }

    // ── Against the real server ────────────────────────────────────────────────────────────────

    /**
     * No WebView and no session: `initialize` and `tools/list` are answered before either is
     * consulted, which is enough to prove the payload classes match what the server actually emits.
     */
    @Test
    fun the_payload_classes_describe_the_server_this_client_talks_to() =
        runTest {
            val server = McpServer(WebViewSessions(), backgroundScope, name = "test-server")
            val client = McpClient(InProcessMcpTransport(server))

            assertEquals("2025-11-25", client.initialize())

            val tools = client.listTools()
            assertTrue(tools.any { it.name == "snapshot" }, "was: ${tools.map { it.name }}")
            assertTrue(
                tools.all { it.title.isNotBlank() && it.description.isNotBlank() },
                "every advertised tool carries the prose a model reads before calling it",
            )
        }

    /** The failure an agent meets first, end to end: a tool that could not do what was asked. */
    @Test
    fun a_real_tool_failure_arrives_as_a_result_and_not_an_exception() =
        runTest {
            val server = McpServer(WebViewSessions(), backgroundScope, name = "test-server")
            val client = McpClient(InProcessMcpTransport(server))

            // No session is registered, so the tool cannot resolve one — a failure the model reads.
            val output = client.callTool("snapshot", JsonObject(emptyMap()))

            assertTrue(output.isError, "was: ${output.text}")
            assertTrue(output.text.isNotBlank())
        }
}
