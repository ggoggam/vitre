package dev.ggoggam.vitre.koog

import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.kotlinx.toKoogJSONObject
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.koog.mcp.vitreMcpInstructions
import dev.ggoggam.vitre.koog.mcp.vitreMcpTools
import dev.ggoggam.vitre.koog.testing.FakePageController
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_METADATA_KEY
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_SESSION_METADATA_KEY
import dev.ggoggam.vitre.mcp.McpServer
import dev.ggoggam.vitre.mcp.transport.InProcessMcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** "A host that already runs the MCP server does not describe the same toolset twice." */
@OptIn(InternalAgentToolsApi::class)
class VitreMcpBridgeTest {
    private class Fixture(
        scope: CoroutineScope,
    ) {
        val page = FakePageController()
        val sessions = WebViewSessions()
        val server = McpServer(sessions, scope, engineContext = EmptyCoroutineContext)
        val transport = InProcessMcpTransport(server)

        // The server's own driver, not a second one over the same sessions. Two lease registries
        // corrupt nothing but issue ids the other has never heard of, so a sequence started over one
        // adapter cannot be continued from the other — which is the wiring PageDriver's KDoc warns
        // hosts off, and no fixture of ours should be modelling it.
        val driver = server.driver

        init {
            sessions.register("main", page, "the sample gallery's WebView")
        }
    }

    private fun script(js: String) = buildJsonObject { put("script", js) }.toKoogJSONObject()

    @Test
    fun the_bridge_and_the_typed_tools_offer_the_same_vocabulary() =
        runTest {
            val fixture = Fixture(this)

            val bridged = vitreMcpTools(fixture.transport).map { it.name }.sorted()
            val typed = vitreWebViewTools(fixture.driver).map { it.name }.sorted()

            // The point of shipping both is that they are two routes to one thing. A name in one and
            // not the other means a host's choice of adapter has quietly changed what its agent can
            // do, which is the failure this assertion exists to catch early.
            assertEquals(typed, bridged)
        }

    @Test
    fun the_two_adapters_describe_every_argument_with_the_same_words() =
        runTest {
            val fixture = Fixture(this)
            val bridged = vitreMcpTools(fixture.transport).associateBy { it.name }
            val typed = vitreWebViewTools(fixture.driver).associateBy { it.name }

            // This is the assertion the drift argument actually needs, and it has to live here —
            // in the one module that can see both. An assertion inside each adapter that its own
            // `ref` mentions `snapshot` proves nothing: they read the same constant, so they pass
            // and fail together and neither notices the day one of them stops reading it.
            typed.forEach { (name, tool) ->
                val other = bridged.getValue(name).descriptor
                val mine = tool.descriptor
                assertEquals(other.description, mine.description, "`$name`'s tool description")

                fun args(d: ToolDescriptor) = (d.requiredParameters + d.optionalParameters).associate { it.name to it.description }
                assertEquals(args(other), args(mine), "`$name`'s arguments")
            }
        }

    @Test
    fun the_schemas_survive_the_translation_with_their_prompts_intact() =
        runTest {
            val tools = vitreMcpTools(Fixture(this).transport)

            val click = tools.single { it.name == "click" }
            val ref = click.descriptor.optionalParameters.single { it.name == "ref" }
            assertEquals(ToolParameterType.String, ref.type)
            // A parameter translated without its description is a parameter the model fills in by
            // guessing — which for `ref` is the exact mistake the description exists to prevent.
            assertTrue("snapshot" in ref.description, ref.description)

            val timeout = click.descriptor.optionalParameters.single { it.name == "timeout_ms" }
            assertEquals(ToolParameterType.Integer, timeout.type)
        }

    @Test
    fun the_one_nested_argument_does_not_flatten_on_the_way_through() =
        runTest {
            val tools = vitreMcpTools(Fixture(this).transport)

            val columns =
                tools
                    .single { it.name == "extract_rows" }
                    .descriptor.requiredParameters
                    .single { it.name == "columns" }

            // `columns` is a map of objects, and a translator that only understands flat schemas
            // would render it as a string. The tool would still be listed, and every call to it
            // would fail.
            val type = columns.type
            assertTrue(type is ToolParameterType.Object, "columns became ${type::class.simpleName}")
            assertEquals(true, type.additionalProperties)
            assertTrue(type.additionalPropertiesType is ToolParameterType.Object)
        }

    @Test
    fun a_bridged_call_drives_the_real_page() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { "\"Shop\"" }
            val navigate = vitreMcpTools(fixture.transport).single { it.name == "navigate" }

            val result =
                navigate.executeUnsafe(
                    buildJsonObject { put("url", "https://shop.test/") }.toKoogJSONObject(),
                )

            assertTrue("https://shop.test/" in navigate.encodeResultToStringUnsafe(result, SERIALIZER))
            assertEquals(listOf("https://shop.test/"), fixture.page.navigations)
        }

    @Test
    fun a_page_failure_arrives_as_something_the_model_can_correct() =
        runTest {
            val fixture = Fixture(this)
            // A page where nothing matches, so the wait before the click times out.
            fixture.page.respond = { "false" }
            val click = vitreMcpTools(fixture.transport).single { it.name == "click" }

            val failure =
                assertFailsWith<ToolException.ValidationFailure> {
                    click.executeUnsafe(
                        buildJsonObject {
                            put("css", "#checkout")
                            put("timeout_ms", 200)
                        }.toKoogJSONObject(),
                    )
                }

            // MCP spells this `isError` on a result; Koog spells it ToolException. Both mean "read
            // this and try differently", and the bridge translates rather than letting an isError
            // result reach the model as if the click had worked.
            assertTrue("#checkout" in failure.message, failure.message)
        }

    @Test
    fun a_bridged_call_runs_under_the_lease_the_feature_holds() =
        runTest {
            val fixture = Fixture(this)
            fixture.page.respond = { "\"ok\"" }
            val grant = fixture.driver.acquireLease()
            val evaluate = vitreMcpTools(fixture.transport).single { it.name == "evaluate" }

            // A bridged tool has no metadata channel to the server — the only thing that reaches it
            // is the arguments — so the ambient lease has to be written into them. Without that, a
            // host that installs VitrePageLease and registers bridged tools gets a feature holding
            // the page and calls that never quote it: every one queues on the lock the feature is
            // holding until the TTL runs out.
            val unleased = launch { evaluate.executeUnsafe(script("theirs()"), ToolCallMetadata.EMPTY) }
            runCurrent()

            evaluate.executeUnsafe(
                script("mine()"),
                ToolCallMetadata.of(
                    VITRE_LEASE_METADATA_KEY to grant.id,
                    VITRE_LEASE_SESSION_METADATA_KEY to grant.sessionId,
                ),
            )
            runCurrent()

            assertEquals(
                listOf("mine()"),
                fixture.page.evaluatedScripts,
                "the bridged call did not carry the run's lease, so it queued behind it",
            )

            fixture.driver.releaseLease(grant.id)
            unleased.join()
        }

    @Test
    fun release_lease_is_never_handed_the_runs_own_claim() =
        runTest {
            val fixture = Fixture(this)
            val grant = fixture.driver.acquireLease()
            val release = vitreMcpTools(fixture.transport).single { it.name == "release_lease" }

            // `release_lease`'s `lease` is required and names what the *model* wants back. Filling
            // it in from the ambient metadata would have the feature hand the model its own claim to
            // give away, so the injection is limited to tools where `lease` is optional.
            val answer =
                release.encodeResultToStringUnsafe(
                    release.executeUnsafe(
                        buildJsonObject { put("lease", "lease_notmine") }.toKoogJSONObject(),
                        ToolCallMetadata.of(
                            VITRE_LEASE_METADATA_KEY to grant.id,
                            VITRE_LEASE_SESSION_METADATA_KEY to grant.sessionId,
                        ),
                    ),
                    SERIALIZER,
                )

            assertTrue("lease_notmine" in answer, answer)
            assertTrue(fixture.driver.isLeaseActive(grant.id), "the run's own lease was released for it")
            fixture.driver.releaseLease(grant.id)
        }

    @Test
    fun the_server_hands_over_its_instructions_for_the_system_prompt() =
        runTest {
            val instructions = vitreMcpInstructions(Fixture(this).transport)

            // MCP has a field for "what these tools are for"; a Koog agent has a system prompt. Same
            // job, and a host bridging one to the other should not have to retype it.
            assertTrue("snapshot" in instructions, instructions)
            assertTrue("acquire_lease" in instructions, instructions)
        }

    private companion object {
        val SERIALIZER =
            ai.koog.serialization.kotlinx
                .KotlinxSerializer()
    }
}
