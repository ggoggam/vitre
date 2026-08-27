package dev.ggoggam.vitre.koog

import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.kotlinx.KotlinxSerializer
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.net.ExchangeOutcome
import dev.ggoggam.vitre.core.net.NetworkExchange
import dev.ggoggam.vitre.core.net.NetworkLog
import dev.ggoggam.vitre.koog.testing.FakePageController
import dev.ggoggam.vitre.koog.tools.ClickTool
import dev.ggoggam.vitre.koog.tools.EvaluateTool
import dev.ggoggam.vitre.koog.tools.ExtractRowsTool
import dev.ggoggam.vitre.koog.tools.ReadNetworkTool
import dev.ggoggam.vitre.koog.tools.SnapshotTool
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_METADATA_KEY
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_SESSION_METADATA_KEY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "An agent driving this page through Koog gets the same page, and the same warnings, as over MCP." */
class VitreToolsTest {
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
        val driver = PageDriver(sessions, scope, engineContext = EmptyCoroutineContext)

        init {
            sessions.register("main", page, "the sample gallery's WebView")
        }
    }

    @Test
    fun the_registry_carries_the_whole_vocabulary() =
        runTest {
            val tools = vitreWebViewTools(Fixture(this).driver)
            val names = tools.map { it.name }

            assertTrue("snapshot" in names, "$names")
            assertTrue("click" in names && "type" in names && "extract" in names, "$names")
            assertTrue("extract_rows" in names && "evaluate" in names, "$names")
            assertTrue("read_network" in names, "$names")
            assertTrue("acquire_lease" in names && "release_lease" in names, "$names")
            // Names match the MCP server's exactly, so a system prompt written against one adapter
            // works against the other. A drift here is a prompt that silently stops matching, and
            // `VitreMcpBridgeTest` is what proves the two lists are the same rather than merely
            // the same length.
            assertEquals(names.distinct(), names, "a duplicate tool name: $names")
        }

    @Test
    fun the_lease_tools_come_out_when_the_feature_holds_the_lease_instead() =
        runTest {
            val names = vitreWebViewTools(Fixture(this).driver, includeLeaseTools = false).map { it.name }

            // With VitrePageLease installed the run already holds the page. A model that then calls
            // `acquire_lease` queues behind itself and only recovers when its own lease expires.
            assertFalse("acquire_lease" in names, "$names")
            assertFalse("release_lease" in names, "$names")
        }

    @Test
    fun the_element_arguments_tell_the_model_to_look_before_it_guesses() =
        runTest {
            val click = ClickTool(Fixture(this).driver)
            val ref = click.descriptor.optionalParameters.single { it.name == "ref" }

            // The same sentence the MCP schema carries, because both read it out of `vitre-agent`.
            // If this assertion and the MCP one ever disagree, the two adapters have started giving
            // a model different advice about the one mistake it most reliably makes.
            assertTrue("snapshot" in ref.description, ref.description)
            assertTrue("timeout_ms" in click.descriptor.optionalParameters.map { it.name })
        }

    @Test
    fun snapshot_reaches_the_model_as_an_outline_and_the_host_as_counts() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { snapshotJson }
            val tool = SnapshotTool(fixture.driver)

            val result = tool.execute(SnapshotTool.Args(), ToolCallMetadata.EMPTY)

            assertEquals(2, result.nodes)
            assertEquals("Shop", result.title)
            // The model is sent the outline alone: same information as the JSON at about a third of
            // the tokens, and it lands in a context window somebody pays for.
            val forModel = tool.encodeResultToString(result, JSON_SERIALIZER)
            assertTrue("[ref=e1]" in forModel, forModel)
            assertFalse(forModel.trimStart().startsWith("{"), forModel)
        }

    @Test
    fun clicking_something_that_is_not_there_fails_where_the_model_can_read_it() =
        runTest {
            val fixture = Fixture(this)
            // A page where nothing matches: `document.querySelector(...)!==null` is false forever.
            fixture.page.respond = { "false" }

            val failure =
                assertFailsWith<ToolException.ValidationFailure> {
                    ClickTool(fixture.driver).execute(
                        ClickTool.Args(css = "#checkout", timeoutMs = 200),
                        ToolCallMetadata.EMPTY,
                    )
                }

            // A ToolException is the one exception Koog turns into a result the LLM sees, rather
            // than a failed run. Any other type would end the agent over a missing button.
            assertTrue("#checkout" in failure.message, failure.message)
        }

    @Test
    fun addressing_an_element_two_ways_at_once_is_refused_rather_than_guessed() =
        runTest {
            val fixture = Fixture(this)

            val failure =
                assertFailsWith<ToolException.ValidationFailure> {
                    ClickTool(fixture.driver).execute(
                        ClickTool.Args(ref = "e2", css = "#go"),
                        ToolCallMetadata.EMPTY,
                    )
                }

            assertTrue("exactly one" in failure.message, failure.message)
        }

    @Test
    fun a_column_that_searches_from_the_document_root_is_caught_before_it_runs() =
        runTest {
            val fixture = Fixture(this)

            val failure =
                assertFailsWith<ToolException.ValidationFailure> {
                    ExtractRowsTool(fixture.driver).execute(
                        ExtractRowsTool.Args(
                            rowsCss = ".product",
                            columns = mapOf("price" to ExtractRowsTool.Column(xpath = "//span[@class='price']")),
                        ),
                        ToolCallMetadata.EMPTY,
                    )
                }

            // The quiet one: a leading `//` searches the whole document from any context node, so
            // every record would repeat the first row's price and the result would look fine.
            assertTrue(".//" in failure.message, failure.message)
            assertFalse(fixture.page.evaluatedScripts.any { "querySelectorAll" in it }, "it should not have run")
        }

    @Test
    fun read_network_hands_back_the_captured_json_verbatim() =
        runTest {
            val fixture = Fixture(this)
            val log = NetworkLog()
            fixture.sessions.register("main", fixture.page, "the sample gallery's WebView", network = log)
            log.record(
                NetworkExchange(
                    id = 1,
                    method = "GET",
                    url = "https://shop.test/api/search?q=keyboard",
                    outcome = ExchangeOutcome.Fetched,
                    status = 200,
                    requestHeaders = emptyMap(),
                    responseHeaders = emptyMap(),
                    contentType = "application/json",
                    body = """{"items":[{"sku":"K1","priceCents":6999}]}""",
                    bodyTruncated = false,
                    durationMs = 34,
                ),
            )
            val tool = ReadNetworkTool(fixture.driver)

            val result = tool.execute(ReadNetworkTool.Args(urlContains = "search"), ToolCallMetadata.EMPTY)
            val forModel = tool.encodeResultToString(result, JSON_SERIALIZER)

            // Verbatim, like every other page tool's reply. JSON that arrives quoted and
            // backslash-escaped costs tokens twice and reads as a string literal rather than data.
            assertTrue("""{"items":[{"sku":"K1","priceCents":6999}]}""" in forModel, forModel)
            assertFalse("\\\"" in forModel, forModel)
        }

    @Test
    fun read_network_takes_no_lease_because_it_reads_a_buffer_and_not_the_page() =
        runTest {
            val fixture = Fixture(this)
            val descriptor = ReadNetworkTool(fixture.driver).descriptor
            val arguments = (descriptor.requiredParameters + descriptor.optionalParameters).map { it.name }

            // The one page tool with no `lease`, and the MCP schema has to agree — which
            // `VitreMcpBridgeTest` checks by comparing the two argument sets outright.
            assertFalse("lease" in arguments, "$arguments")
            assertTrue("url_contains" in arguments && "max_body_chars" in arguments, "$arguments")
        }

    @Test
    fun read_network_says_what_it_cannot_see_before_a_model_reads_silence_as_absence() =
        runTest {
            // The description is the prompt, and this tool is the one that succeeds while returning
            // nothing. On iOS an empty answer is the *expected* result for a document load that
            // certainly happened, so the platform gap has to be in front of the model before it
            // calls, not only in the repo's docs.
            val description = ReadNetworkTool(Fixture(this).driver).descriptor.description

            assertTrue("iOS" in description, description)
            assertTrue("Android" in description, description)
            assertTrue("did not happen" in description, description)
        }

    @Test
    fun a_lease_the_feature_holds_is_used_without_the_model_naming_it() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { "\"ok\"" }
            val grant = fixture.driver.acquireLease()
            val tool = EvaluateTool(fixture.driver)

            // A call with no metadata at all: another caller of a page somebody is holding, which
            // queues rather than being turned away. It must still be waiting when the leased one
            // has been and gone — that ordering is the whole assertion, and asserting only on the
            // leased call's result would pass just as well with the metadata lookup deleted, since
            // virtual time would advance past the lease's own TTL and let it through.
            val unleased = launch { tool.execute(EvaluateTool.Args(script = "theirs()"), ToolCallMetadata.EMPTY) }
            runCurrent()

            // No `lease` in the arguments; the feature contributes it as call metadata instead.
            tool.execute(
                EvaluateTool.Args(script = "mine()"),
                ToolCallMetadata.of(
                    VITRE_LEASE_METADATA_KEY to grant.id,
                    VITRE_LEASE_SESSION_METADATA_KEY to grant.sessionId,
                ),
            )
            runCurrent()

            assertEquals(
                listOf("mine()"),
                fixture.page.evaluatedScripts,
                "the ambient lease was not picked up: the call queued behind the claim instead of running under it",
            )

            fixture.driver.releaseLease(grant.id)
            unleased.join()
            assertEquals(listOf("mine()", "theirs()"), fixture.page.evaluatedScripts)
        }

    @Test
    fun an_ambient_lease_is_left_off_a_call_aimed_at_a_different_webview() =
        runTest {
            val fixture = Fixture(this)
            val second = FakePageController()
            fixture.sessions.register("other", second, "a second tab")
            fixture.page.respond = { "\"ok\"" }
            second.respond = { "\"ok\"" }
            val grant = fixture.driver.acquireLease("main")

            // The run holds "main". A call the model aimed at "other" must not quote that claim:
            // the registry checks the pair, so it would fail with "held on session `main`, not
            // `other`" — for a lease the model never asked for and has no argument to decline.
            EvaluateTool(fixture.driver).execute(
                EvaluateTool.Args(script = "elsewhere()", session = "other"),
                ToolCallMetadata.of(
                    VITRE_LEASE_METADATA_KEY to grant.id,
                    VITRE_LEASE_SESSION_METADATA_KEY to grant.sessionId,
                ),
            )

            assertEquals(listOf("elsewhere()"), second.evaluatedScripts)
            fixture.driver.releaseLease(grant.id)
        }

    @Test
    fun a_lease_the_model_names_beats_the_ambient_one() =
        runTest {
            val fixture = Fixture(this)
            val grant = fixture.driver.acquireLease()

            val failure =
                assertFailsWith<ToolException.ValidationFailure> {
                    SnapshotTool(fixture.driver).execute(
                        SnapshotTool.Args(lease = "lease_deadbeef"),
                        ToolCallMetadata.of(VITRE_LEASE_METADATA_KEY to grant.id),
                    )
                }

            // Caller-supplied metadata outranks a feature's, so an explicit lease is honoured — and
            // an explicit *wrong* one fails rather than quietly falling back to the ambient claim,
            // which would run the call under a claim the model did not ask for.
            assertTrue("lease_deadbeef" in failure.message, failure.message)
            fixture.driver.releaseLease(grant.id)
        }

    @Test
    fun the_columns_argument_survives_the_trip_through_a_schema() =
        runTest {
            val columns = ExtractRowsTool(Fixture(this).driver).descriptor.requiredParameters.single { it.name == "columns" }

            // `extract_rows` is the one tool whose arguments are not flat, and a map-of-objects that
            // degrades to a bare string on the way into the descriptor is a tool the model can see
            // and cannot call.
            val type = columns.type
            assertTrue(type is ToolParameterType.Object, "columns became ${type::class.simpleName}")
        }

    private companion object {
        /** The tools under test encode their own results, so nothing here has to serialize. */
        val JSON_SERIALIZER = KotlinxSerializer()
    }
}
