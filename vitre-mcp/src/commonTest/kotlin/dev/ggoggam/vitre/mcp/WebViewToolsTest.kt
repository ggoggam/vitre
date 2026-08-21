package dev.ggoggam.vitre.mcp

import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.mcp.testing.FakePageController
import dev.ggoggam.vitre.mcp.testing.McpTestClient
import dev.ggoggam.vitre.mcp.testing.result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "An agent that has never seen this page can look at it and act on what it sees." */
class WebViewToolsTest {
    private val snapshotJson =
        """
        {"url":"https://shop.test/","title":"Shop","truncated":false,"nodes":[
          {"ref":"e1","role":"searchbox","name":"Search","tag":"input","depth":0,"value":""},
          {"ref":"e2","role":"button","name":"Go","tag":"button","depth":0}
        ]}
        """.trimIndent()

    private class Fixture(
        scope: CoroutineScope,
    ) {
        val page = FakePageController()
        val sessions = WebViewSessions()
        val server = McpServer(sessions, scope, engineContext = EmptyCoroutineContext)
        val client = McpTestClient(server)

        init {
            sessions.register("main", page, "the sample gallery's WebView")
        }
    }

    @Test
    fun the_tool_list_leads_with_looking_at_the_page() =
        runTest {
            val fixture = Fixture(this)

            val tools =
                fixture.client
                    .legacy("tools/list")
                    .result()["tools"]!!
                    .jsonArray
                    .map { it.jsonObject }

            val names = tools.map { it["name"]!!.jsonPrimitive.content }
            assertTrue("snapshot" in names, "$names")
            assertTrue("click" in names && "type" in names && "extract" in names, "$names")
            assertTrue("acquire_lease" in names && "release_lease" in names, "$names")

            // Every tool must carry a schema object; a client that validates arguments against a
            // missing or null schema rejects the tool outright.
            for (tool in tools) {
                val schema = tool["inputSchema"]!!.jsonObject
                assertEquals("object", schema["type"]!!.jsonPrimitive.content, "bad schema on ${tool["name"]}")
            }

            // The description is the prompt, not documentation — it is the only thing the model
            // reads before deciding how to address an element.
            val click = tools.single { it["name"]!!.jsonPrimitive.content == "click" }
            val refDescription =
                click["inputSchema"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["ref"]!!
                    .jsonObject["description"]!!
                    .jsonPrimitive.content
            assertTrue("snapshot" in refDescription, "the ref argument must point back at snapshot: $refDescription")
        }

    @Test
    fun snapshot_returns_the_page_as_addressable_elements() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { snapshotJson }

            val result = fixture.client.callTool("snapshot")

            assertFalse(result.isError, result.text)
            assertTrue("[ref=e1]" in result.text, result.text)
            assertTrue("searchbox \"Search\"" in result.text, result.text)
            // Rendered as an outline rather than as the JSON it came from: same information, about a
            // third of the tokens, and the result lands in a context window somebody pays for.
            assertFalse(result.text.trimStart().startsWith("{"), "should not be raw JSON: ${result.text}")
        }

    @Test
    fun a_single_session_needs_no_session_argument_but_two_do() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { snapshotJson }

            assertFalse(fixture.client.callTool("snapshot").isError)

            fixture.sessions.register("second", FakePageController(), "another WebView")
            val ambiguous = fixture.client.callTool("snapshot")

            // Picking one would be a "current session" by another name, and the moment there are two
            // WebViews that is a silent wrong-page bug rather than a convenience.
            assertTrue(ambiguous.isError)
            assertTrue("main" in ambiguous.text && "second" in ambiguous.text, ambiguous.text)
        }

    @Test
    fun clicking_something_that_is_not_there_fails_instead_of_reporting_success() =
        runTest {
            val fixture = Fixture(this)
            // A page where nothing matches: `document.querySelector(...)!==null` is false forever.
            fixture.page.respond = { "false" }

            val result =
                fixture.client.callTool(
                    "click",
                    buildJsonObject {
                        put("css", "#checkout")
                        put("timeout_ms", 200)
                    },
                )

            // The generated click is `…?.click()`, which succeeds against nothing. An agent told it
            // pressed checkout, when it did not, proceeds from a state that does not exist — and
            // every later step is then wrong for a reason it cannot see.
            assertTrue(result.isError, "a click that landed on nothing was reported as success")
            assertTrue("#checkout" in result.text, result.text)
        }

    @Test
    fun a_failed_tool_call_is_a_result_the_model_can_read_not_a_protocol_error() =
        runTest {
            val fixture = Fixture(this)

            val response =
                fixture.client.legacy(
                    "tools/call",
                    buildJsonObject {
                        put("name", "click")
                        put("arguments", buildJsonObject { put("css", "#nope") })
                    },
                )

            // Protocol errors are shown to the model at best half the time and are not framed as
            // something to correct. Anything the model could fix by trying differently has to come
            // back as a result with isError, or the recovery path is closed.
            assertTrue("result" in response, "expected an isError result, got: $response")
        }

    @Test
    fun addressing_an_element_two_ways_at_once_is_refused_rather_than_guessed() =
        runTest {
            val fixture = Fixture(this)

            val result =
                fixture.client.callTool(
                    "click",
                    buildJsonObject {
                        put("ref", "e2")
                        put("css", "#go")
                    },
                )

            assertTrue(result.isError)
            // Preferring one silently would act on an element the agent did not choose, and it would
            // never find out which.
            assertTrue("exactly one" in result.text, result.text)
        }

    @Test
    fun addressing_nothing_at_all_points_the_agent_at_snapshot() =
        runTest {
            val fixture = Fixture(this)

            val result = fixture.client.callTool("click", buildJsonObject { })

            assertTrue(result.isError)
            assertTrue("snapshot" in result.text, "the recovery has to be named: ${result.text}")
        }

    @Test
    fun an_xpath_column_that_would_read_the_first_row_for_every_row_is_refused() =
        runTest {
            val fixture = Fixture(this)

            val result =
                fixture.client.callTool(
                    "extract_rows",
                    buildJsonObject {
                        put("rows_css", "li")
                        putJsonObject("columns") {
                            putJsonObject("title") { put("xpath", "//h2") }
                        }
                    },
                )

            // `//h2` searches from the document root whatever the context node is, so every record
            // gets the first row's title. The result looks entirely plausible, which is what makes
            // it worth refusing rather than returning.
            assertTrue(result.isError)
            assertTrue(".//" in result.text, result.text)
        }

    @Test
    fun extract_reads_a_typed_value_through_the_property_not_the_attribute() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { script -> if ("getAttribute" in script) "\"\"" else "\"Kotlin\"" }

            val result =
                fixture.client.callTool(
                    "extract",
                    buildJsonObject {
                        put("css", "#q")
                        put("from", "property")
                        put("name", "value")
                    },
                )

            assertEquals("Kotlin", result.text)
        }

    @Test
    fun listing_sessions_says_which_one_can_be_omitted() =
        runTest {
            val fixture = Fixture(this)

            val result = fixture.client.callTool("list_sessions")

            assertTrue("main" in result.text, result.text)
            assertTrue("the sample gallery's WebView" in result.text, result.text)
            assertEquals(1, result.structured!!["sessions"]!!.jsonArray.size)
        }

    @Test
    fun a_tool_that_does_not_exist_is_a_protocol_error() =
        runTest {
            val fixture = Fixture(this)

            val response =
                fixture.client.legacy(
                    "tools/call",
                    buildJsonObject {
                        put("name", "format_hard_drive")
                        put("arguments", buildJsonObject { })
                    },
                )

            // The opposite case to the one above: the model was given the list, so calling something
            // absent from it is not a mistake it can correct by adjusting arguments.
            assertTrue("error" in response, "$response")
        }
}
