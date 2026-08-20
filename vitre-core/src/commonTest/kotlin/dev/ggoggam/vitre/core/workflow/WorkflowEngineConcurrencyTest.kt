package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How the engine behaves at the boundaries it shares with its caller and with the page. */
class WorkflowEngineConcurrencyTest {
    @Test
    fun cancelling_a_run_cancels_it_rather_than_reporting_a_failure() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    // A navigation that never settles, so the run is still in flight when cancelled.
                    onNavigate = { CompletableDeferred<Unit>().await() }
                }
            val workflow =
                Workflow(
                    id = "wf-cancel",
                    name = "cancel-mid-run",
                    steps = listOf(WorkflowStep.Navigate("https://slow.test")),
                )

            val events = mutableListOf<WorkflowEvent>()
            val run = launch { WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList(events) }
            runCurrent()

            run.cancel()
            run.join()

            // The runner re-launches on every replay and on back-navigation, so this happens
            // constantly. Swallowing the cancellation reported a failure that never occurred, and
            // emitted it into a flow that was already being torn down.
            assertTrue(
                events.none { it is WorkflowEvent.Failed },
                "cancellation was reported as a workflow failure: $events",
            )
        }

    @Test
    fun awaiting_a_message_the_page_already_sent_still_matches() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-early-message",
                    name = "early-message",
                    steps =
                        listOf(
                            WorkflowStep.EvaluateJs("startTheThing()"),
                            WorkflowStep.AwaitMessage(type = "pong", into = "reply"),
                        ),
                )

            // The realistic shape of the race: the page posts synchronously from the handler the
            // previous step triggered, long before AwaitMessage begins to listen.
            val reply = """{"id":"1","type":"pong","payload":null}"""
            controller.nextEvalResult = {
                controller.simulatePageMessage(reply)
                "null"
            }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(reply, completed.variables["reply"])
        }

    @Test
    fun awaiting_a_message_that_never_comes_fails_instead_of_hanging() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-no-message",
                    name = "no-message",
                    steps = listOf(WorkflowStep.AwaitMessage(type = "pong", into = "reply", timeoutMs = 2_000)),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // Previously an unbounded `first {}`: a page that never posted left the gallery
            // spinning with no event and no way back.
            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertTrue("pong" in failed.message, "unhelpful message: ${failed.message}")
        }

    @Test
    fun extracting_a_property_reads_back_what_input_wrote() =
        runTest {
            val controller = FakeWebViewController()
            val typed = mutableMapOf<String, String>()
            controller.nextEvalResult = { script ->
                when {
                    // Stands in for `el.value = "Kotlin"`, which sets a property and leaves the
                    // markup's value attribute untouched.
                    "el.value=" in script -> {
                        typed["value"] = "Kotlin"
                        "null"
                    }

                    "getAttribute" in script -> {
                        "\"\""
                    }

                    else -> {
                        "\"${typed["value"].orEmpty()}\""
                    }
                }
            }
            val workflow =
                Workflow(
                    id = "wf-echo",
                    name = "echo",
                    steps =
                        listOf(
                            WorkflowStep.Input(selector = "#q", text = "Kotlin"),
                            WorkflowStep.Extract(
                                selector = "#q",
                                into = "viaAttribute",
                                from = WorkflowStep.Extract.Source.Attribute("value"),
                            ),
                            WorkflowStep.Extract(
                                selector = "#q",
                                into = "viaProperty",
                                from = WorkflowStep.Extract.Source.Property("value"),
                            ),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("", completed.variables["viaAttribute"], "attribute should not track input")
            assertEquals("Kotlin", completed.variables["viaProperty"])
        }

    @Test
    fun extracted_text_arrives_unescaped() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    // What both platforms now return for text containing a newline and a quote.
                    nextEvalResult = { "\"line one\\nsaid \\\"hi\\\"\"" }
                }
            val workflow =
                Workflow(
                    id = "wf-escapes",
                    name = "escapes",
                    steps = listOf(WorkflowStep.Extract(selector = "p", into = "text")),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("line one\nsaid \"hi\"", completed.variables["text"])
        }

    @Test
    fun load_html_and_post_message_reach_the_controller() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-fixture",
                    name = "fixture",
                    steps =
                        listOf(
                            WorkflowStep.LoadHtml("<h1>hi</h1>", baseUrl = "https://fixture.test/"),
                            WorkflowStep.PostMessage("""{"id":"1","type":"ping","payload":null}"""),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(
                listOf<Pair<String, String?>>("<h1>hi</h1>" to "https://fixture.test/"),
                controller.loadedHtml,
            )
            assertTrue(
                controller.evaluatedScripts.single().startsWith("window.dispatchEvent"),
                "PostMessage should reach the page through the bridge: ${controller.evaluatedScripts}",
            )
        }
}
