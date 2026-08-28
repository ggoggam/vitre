package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowEngineHappyPathTest {
    @Test
    fun navigate_steps_emit_started_and_completed_in_order() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-1",
                    name = "two-navigates",
                    steps =
                        listOf(
                            WorkflowStep.Navigate("https://example.com"),
                            WorkflowStep.Navigate("https://example.org"),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(
                listOf("https://example.com", "https://example.org"),
                controller.navigations,
            )
            assertEquals(5, events.size)
            assertIs<WorkflowEvent.StepStarted>(events[0]).also { assertEquals(StepPath.root(0), it.path) }
            assertIs<WorkflowEvent.StepCompleted>(events[1]).also { assertEquals(StepPath.root(0), it.path) }
            assertIs<WorkflowEvent.StepStarted>(events[2]).also { assertEquals(StepPath.root(1), it.path) }
            assertIs<WorkflowEvent.StepCompleted>(events[3]).also { assertEquals(StepPath.root(1), it.path) }
            val completed = assertIs<WorkflowEvent.Completed>(events[4])
            assertEquals(emptyMap(), completed.variables)
        }
}
