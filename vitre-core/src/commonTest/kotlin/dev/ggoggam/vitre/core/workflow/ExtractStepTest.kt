package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtractStepTest {
    @Test
    fun extract_textContent_stores_unquoted_value_in_variables() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { "\"Example Domain\"" } // page returns JSON-quoted string
                }
            val workflow =
                Workflow(
                    id = "wf-extract",
                    name = "extract-h1",
                    steps = listOf(WorkflowStep.Extract(selector = "h1", into = "title")),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("Example Domain", completed.variables["title"])
            assertEquals(1, controller.evaluatedScripts.size)
            val script = controller.evaluatedScripts.single()
            assertTrue("h1" in script, "script should reference selector: $script")
            assertTrue("textContent" in script, "script should read textContent: $script")
        }

    @Test
    fun extract_attribute_uses_getAttribute() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { "\"/foo\"" }
                }
            val workflow =
                Workflow(
                    id = "wf-attr",
                    name = "extract-href",
                    steps =
                        listOf(
                            WorkflowStep.Extract(
                                selector = "a",
                                into = "link",
                                from = WorkflowStep.Extract.Source.Attribute("href"),
                            ),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("/foo", completed.variables["link"])
            assertTrue("getAttribute" in controller.evaluatedScripts.single())
        }
}
