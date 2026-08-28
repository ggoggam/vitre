package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.ScriptTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaitForStepTest {
    @Test
    fun proceeds_once_selector_resolves_true() =
        runTest {
            var calls = 0
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = {
                        calls++
                        if (calls < 3) "false" else "true"
                    }
                }
            val workflow =
                Workflow(
                    id = "wf-wait",
                    name = "wait-then-done",
                    steps = listOf(WorkflowStep.WaitFor("#ready", timeoutMs = 5_000)),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(3, calls)
        }

    @Test
    fun emits_failed_when_timeout_elapses() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { "false" } // never finds the element
                }
            val workflow =
                Workflow(
                    id = "wf-timeout",
                    name = "timeout",
                    steps = listOf(WorkflowStep.WaitFor("#never", timeoutMs = 200)),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(StepPath.root(0), failed.path)
            assertTrue("Timeout" in failed.message, "unexpected failure message: ${failed.message}")
        }

    /**
     * The document a poll was submitted against can be replaced while that poll is in flight — a
     * click that navigates is the ordinary way this happens — and both platforms drop the pending
     * callback rather than reporting an error, which surfaces as [ScriptTimeoutException]. That is
     * the page behaving normally, not the wait failing: the element being waited for is on the
     * document now loading.
     */
    @Test
    fun keeps_polling_when_a_poll_is_dropped_by_a_navigation() =
        runTest {
            var calls = 0
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = {
                        calls++
                        when {
                            // The old document is still committed and has no match.
                            calls == 1 -> "false"

                            // The new document commits with this poll in flight, so its callback
                            // is dropped and the evaluation times out instead of answering.
                            calls == 2 -> throw ScriptTimeoutException("Script did not return within 15000ms")

                            // The awaited page is now live.
                            else -> "true"
                        }
                    }
                }
            val workflow =
                Workflow(
                    id = "wf-nav-race",
                    name = "wait-across-navigation",
                    steps = listOf(WorkflowStep.WaitFor("#results", timeoutMs = 25_000)),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(3, calls)
        }
}
