package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.PageLoadException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigateStepTest {
    @Test
    fun later_steps_wait_for_the_page_to_finish_loading() =
        runTest {
            val pageLoaded = CompletableDeferred<Unit>()
            val controller =
                FakeWebViewController().apply {
                    onNavigate = { pageLoaded.await() }
                    nextEvalResult = { "\"loaded\"" }
                }
            val workflow =
                Workflow(
                    id = "wf-nav",
                    name = "navigate-then-evaluate",
                    steps =
                        listOf(
                            WorkflowStep.Navigate("https://example.com"),
                            WorkflowStep.EvaluateJs("document.title", into = "title"),
                        ),
                )

            val events = mutableListOf<WorkflowEvent>()
            val run = launch { WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList(events) }
            runCurrent()

            // Mid-navigation: the JS context of the outgoing page is still being torn down, so
            // nothing may have been submitted to it yet.
            assertEquals(listOf("https://example.com"), controller.navigations)
            assertEquals(emptyList(), controller.evaluatedScripts)
            assertEquals(2, events.size)
            assertIs<WorkflowEvent.LaneLeased>(events[0])
            assertIs<WorkflowEvent.StepStarted>(events[1]).also { assertEquals(StepPath.root(0), it.path) }

            pageLoaded.complete(Unit)
            run.join()

            assertEquals(listOf("document.title"), controller.evaluatedScripts)
            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(mapOf("title" to "loaded"), completed.variables)
        }

    @Test
    fun a_failed_page_load_fails_the_workflow() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    onNavigate = { throw PageLoadException("net::ERR_NAME_NOT_RESOLVED") }
                }
            val workflow =
                Workflow(
                    id = "wf-nav-fail",
                    name = "navigate-to-nowhere",
                    steps =
                        listOf(
                            WorkflowStep.Navigate("https://not-a-real-host.test"),
                            WorkflowStep.EvaluateJs("neverRuns()"),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(StepPath.root(0), failed.path)
            assertEquals("net::ERR_NAME_NOT_RESOLVED", failed.message)
            assertTrue(controller.evaluatedScripts.isEmpty(), "workflow continued past a dead page")
        }
}
