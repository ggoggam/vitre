package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowEngineFailureTest {
    @Test
    fun failing_step_emits_failed_with_index_and_stops() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    // EvaluateJs throws so the second step blows up.
                    nextEvalResult = { throw IllegalStateException("boom") }
                }
            val workflow =
                Workflow(
                    id = "wf-fail",
                    name = "fail-on-second",
                    steps =
                        listOf(
                            WorkflowStep.Navigate("about:blank"),
                            WorkflowStep.EvaluateJs("doesNotMatter()"),
                            WorkflowStep.Navigate("https://never-reached.test"),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(1, failed.stepIndex)
            assertEquals("boom", failed.message)
            // Third step should not have run.
            assertEquals(listOf("about:blank"), controller.navigations)
            // No Completed event before failure.
            assertEquals(0, events.count { it is WorkflowEvent.Completed })
        }
}
