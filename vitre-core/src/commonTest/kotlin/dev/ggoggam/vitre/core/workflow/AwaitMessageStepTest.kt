package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AwaitMessageStepTest {
    @Test
    fun suspends_until_matching_message_then_records_payload() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-await",
                    name = "await-ready",
                    steps = listOf(WorkflowStep.AwaitMessage(type = "ready", into = "ack")),
                )

            val collected = mutableListOf<WorkflowEvent>()
            val job =
                launch {
                    WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList(collected)
                }

            // Unrelated message — engine must ignore it.
            controller.simulatePageMessage("""{"id":"1","type":"loading","payload":null}""")
            // Matching message — engine must proceed.
            val matchingRaw = """{"id":"2","type":"ready","payload":{"ok":true}}"""
            controller.simulatePageMessage(matchingRaw)

            job.join()
            val completed = assertIs<WorkflowEvent.Completed>(collected.last())
            assertEquals(matchingRaw, completed.variables["ack"])
        }

    @Test
    fun ignores_malformed_json_in_bridge_stream() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-await-skip",
                    name = "skip-junk",
                    steps = listOf(WorkflowStep.AwaitMessage(type = "ready", into = "ack")),
                )

            val collected = mutableListOf<WorkflowEvent>()
            val job =
                launch {
                    WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList(collected)
                }

            controller.simulatePageMessage("not-json-at-all")
            controller.simulatePageMessage("""{"id":"3","type":"ready","payload":null}""")

            job.join()
            assertIs<WorkflowEvent.Completed>(collected.last())
            assertTrue("\"ready\"" in (collected.last() as WorkflowEvent.Completed).variables["ack"]!!)
        }

    @Test
    fun a_subframe_forgery_does_not_satisfy_the_await_step() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-await-frame",
                    name = "await-ready-main-frame",
                    steps = listOf(WorkflowStep.AwaitMessage(type = "ready", into = "ack")),
                )

            val collected = mutableListOf<WorkflowEvent>()
            val job =
                launch {
                    WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList(collected)
                }

            // An iframe on the page — an embedded ad, say — posts exactly what the step wants.
            // Crediting it would both finish the step early and consume the answer the document
            // being driven was about to give.
            controller.simulatePageMessage("""{"id":"1","type":"ready","payload":{"from":"iframe"}}""", fromMainFrame = false)
            val matchingRaw = """{"id":"2","type":"ready","payload":{"from":"main"}}"""
            controller.simulatePageMessage(matchingRaw)

            job.join()
            val completed = assertIs<WorkflowEvent.Completed>(collected.last())
            assertEquals(matchingRaw, completed.variables["ack"], "the step took the iframe's message")
        }
}
