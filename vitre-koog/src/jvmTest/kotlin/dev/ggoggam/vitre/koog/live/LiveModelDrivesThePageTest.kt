package dev.ggoggam.vitre.koog.live

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageToolDocs
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.koog.vitreWebViewToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A real model, over the network, driving the page through this module's tools.
 *
 * Every other test here supplies the model: the host tests call the tools directly, and the
 * on-device one scripts the tool calls with Koog's mock executor. That is the right cut for them —
 * an LLM in the loop of a unit test is a bill and a flake. But it leaves one claim untested, and it
 * is the claim the module is *for*:
 *
 *  - that the descriptors `vitreWebViewTools` generates are schemas a provider accepts at all
 *    (a bad `@LLMDescription`, an unsupported parameter type, a name that collides — none of these
 *    show up until an API rejects the request, and a mock executor never sends one);
 *  - that [PageToolDocs] actually steers a model. Those strings are prompt, not documentation, and
 *    nothing else in the repo checks that the sentence telling a model to snapshot before it guesses
 *    a selector has that effect on a model.
 *
 * So this is deliberately not part of `mise run test`. It is skipped without `ANTHROPIC_API_KEY`,
 * and run by hand — or by `mise run test:live` — when the tools, their descriptions or the Koog
 * version change.
 *
 * The page underneath is [ScriptedShopPage], not a WebView: the WebView half is what
 * [dev.ggoggam.vitre.koog.KoogAgentOnDeviceTest] runs for real. One of the two fakes the model and
 * one fakes the page, and between them nothing in the stack is fake in both.
 */
class LiveModelDrivesThePageTest {
    private val apiKey: String? = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun a_model_that_has_never_seen_the_page_searches_it_and_reads_the_answer_off_the_results() {
        val key = apiKey ?: return skip()

        val page = ScriptedShopPage()
        val sessions = WebViewSessions().apply { register("main", page, "the shop tab") }
        val driver = PageDriver(sessions, scope)

        val agent =
            AIAgent(
                promptExecutor = PromptExecutorBuilder().anthropic(key).build(),
                llmModel = AnthropicModels.Sonnet_4_6,
                strategy = actOnToolCallsEvenWhenNarrated(),
                systemPrompt = PageToolDocs.INSTRUCTIONS,
                // Lease tools are in, and the page is shared with nobody, so a model that takes one
                // is not wrong — just wasting a call. That it *can* is part of what is under test.
                toolRegistry = vitreWebViewToolRegistry(driver),
                maxIterations = MAX_ITERATIONS,
            )

        val answer =
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    agent.run(
                        "Search this page for a wireless keyboard. Of the wireless keyboards that " +
                            "are in stock, tell me the price of the cheapest one.",
                    )
                }
            }

        // The page, not the prose. The model could produce a plausible-looking sentence without
        // having touched anything, so what is asserted first is the state it left behind: it typed
        // a query and it pressed Search, and until it pressed Search there were no rows to read.
        println("[live] answer: $answer")
        println("[live] scripts: ${page.evaluatedScripts.size}")

        assertTrue(page.searched, "the model never pressed Search — it cannot have seen any results")
        assertContains(page.query.lowercase(), "keyboard", message = "what the model typed into the search box")

        // And then the answer, which is only reachable by reading all three columns: the cheapest
        // keyboard is mechanical and the cheapest wireless one is out of stock.
        //
        // Asserted on the product *and* the price, not on the price alone: a model that shows its
        // comparison quotes every price on the page, so "the answer contains 61.00" is satisfied by
        // a listing that then concludes wrongly. Naming the row is what says it chose.
        val expected = page.cheapestWirelessInStock
        assertContains(answer, expected.price, message = "the agent's answer")
        assertContains(answer, expected.name.substringBefore(' '), message = "the agent's answer")

        // The instruction that earns its place in PageToolDocs.INSTRUCTIONS: look before you act.
        // A model that guessed `#search` on a page it had not snapshotted would have failed the
        // assertions above too, but noisily and for the wrong stated reason — this says why.
        val firstSnapshot = page.evaluatedScripts.indexOfFirst { "walk(document.body,0)" in it }
        val firstAction = page.evaluatedScripts.indexOfFirst { it.trimEnd().endsWith("?.click()") }
        assertTrue(firstSnapshot >= 0, "the model never took a snapshot")
        assertTrue(
            firstAction < 0 || firstSnapshot < firstAction,
            "the model clicked before it looked at the page",
        )
    }

    @Test
    fun the_tool_schemas_are_ones_the_provider_accepts() {
        val key = apiKey ?: return skip()

        val page = ScriptedShopPage()
        val sessions = WebViewSessions().apply { register("main", page, "the shop tab") }
        val driver = PageDriver(sessions, scope)

        // One cheap round trip that sends all thirteen descriptors. A schema the API rejects is a
        // 400 on this call, and this fails in seconds with the provider's own message rather than
        // as a mystery three tool calls into the test above.
        val agent =
            AIAgent(
                promptExecutor = PromptExecutorBuilder().anthropic(key).build(),
                llmModel = AnthropicModels.Haiku_4_5,
                systemPrompt = PageToolDocs.INSTRUCTIONS,
                toolRegistry = vitreWebViewToolRegistry(driver),
                maxIterations = SCHEMA_PROBE_ITERATIONS,
            )

        val answer =
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    agent.run("How many WebViews can you drive right now? Answer with the number alone.")
                }
            }

        println("[live] answer: $answer")
        assertContains(answer, "1", message = "the agent's answer")
        assertTrue(
            page.evaluatedScripts.isEmpty(),
            "list_sessions should not have touched the page: ${page.evaluatedScripts}",
        )
    }

    /**
     * A single-run loop that treats a tool call as a tool call even when the model narrated first.
     *
     * Koog's default `AIAgent(promptExecutor, llmModel, …)` — the shape `docs/KOOG.md` documents —
     * finishes the run on the first assistant message that matches its text edge, and an Anthropic
     * model almost always emits `[Text("I'll search now."), Tool.Call(type, …)]` as *one* message.
     * The call is right there in `parts`, and the run ends anyway with that sentence delivered as
     * the answer. Driving the same prompt and the same tools by hand shows the model doing the whole
     * task, so the stall is in the edge ordering, not in the model, the tools or [PageToolDocs].
     *
     * This orders the branch the other way at both points: tool calls first, text only if there
     * were none. Everything else is the default single-run graph.
     */
    private fun actOnToolCallsEvenWhenNarrated() =
        strategy<String, String>("vitre-page") {
            val request by nodeLLMRequest()
            val runTools by nodeExecuteTools()
            val sendResults by nodeLLMSendToolResults()

            edge(nodeStart forwardTo request)
            edge(request forwardTo runTools onToolCalls { true })
            edge(request forwardTo nodeFinish onTextMessage { true })
            edge(runTools forwardTo sendResults)
            edge(sendResults forwardTo runTools onToolCalls { true })
            edge(sendResults forwardTo nodeFinish onTextMessage { true })
        }

    private fun skip() {
        // A silent pass would let this rot unnoticed; a failure would break `mise run test` for
        // anyone without a key. Printing is the honest middle, and the CI job never runs this task.
        println("SKIPPED ${this::class.simpleName}: set ANTHROPIC_API_KEY to run it.")
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val MAX_ITERATIONS = 60

        // A GraphAIAgent counts node executions, not tool calls, so one round trip is several
        // iterations. Low enough to stay cheap, high enough that hitting it means something broke.
        const val SCHEMA_PROBE_ITERATIONS = 12
    }
}
