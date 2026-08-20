package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsEscapingTest {
    @Test
    fun click_selector_is_safely_quoted() =
        runTest {
            val controller = FakeWebViewController()
            val tricky = """button[data-q="it's]"""
            val workflow =
                Workflow(
                    id = "wf-click",
                    name = "click-tricky",
                    steps = listOf(WorkflowStep.Click(tricky)),
                )

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val script = controller.evaluatedScripts.single()
            assertTrue("""button[data-q=\"it's]""" in script, "expected escaped selector in: $script")
            assertTrue(script.startsWith("document.querySelector("))
        }

    @Test
    fun input_text_escapes_backslash_newline_quote() =
        runTest {
            val controller = FakeWebViewController()
            val text = "line1\nline2\\path\"end"
            val workflow =
                Workflow(
                    id = "wf-input",
                    name = "input-escapes",
                    steps = listOf(WorkflowStep.Input(selector = "#field", text = text)),
                )

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val script = controller.evaluatedScripts.single()
            assertTrue("""line1\nline2\\path\"end""" in script, "expected escapes in: $script")
        }

    @Test
    fun jsString_helper_escapes_control_codes_and_separators() {
        val bell = 0x07.toChar().toString()
        assertEquals("\"\\u0007\"", jsString(bell))

        val lineSep = 0x2028.toChar().toString()
        assertEquals("\"\\u2028\"", jsString(lineSep))
    }
}
