package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "I type into a field and the page — not just the DOM — receives what I typed."
 *
 * The bug this pins down was invisible from inside a workflow. `el.value = 'x'` writes the DOM
 * property and nothing else, so on a React-controlled field the component's state stayed empty and
 * its `onChange` never ran, while a later `Extract` with `Source.Property("value")` read back the
 * `x` this step had just written. The workflow reported success on a form the app never received.
 * The assertions here are on the *generated script*, because that is the only place the difference
 * is visible without a browser.
 */
class InputStepTest {
    private fun workflowOf(vararg steps: WorkflowStep) = Workflow(id = "wf-input", name = "input", steps = steps.toList())

    /** Runs [steps] against a controller whose page answers every script with [status]. */
    private suspend fun run(
        status: String = "ok",
        vararg steps: WorkflowStep,
    ): Pair<FakeWebViewController, List<WorkflowEvent>> {
        val controller = FakeWebViewController().apply { nextEvalResult = { "\"$status\"" } }
        val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflowOf(*steps)).toList()
        return controller to events
    }

    @Test
    fun typing_goes_through_the_native_value_setter_so_a_framework_sees_it() =
        runTest {
            val (controller, events) = run(steps = arrayOf(WorkflowStep.Input("#q", "hello")))

            assertIs<WorkflowEvent.Completed>(events.last())
            val script = controller.evaluatedScripts.single()
            // The whole point: React installs its own `value` setter on the element, which swallows
            // a plain assignment. Calling the *prototype's* setter is what its value-tracker sees.
            assertContains(script, "Object.getOwnPropertyDescriptor")
            assertContains(script, ".set.call(")
            assertContains(script, "HTMLInputElement.prototype")
            assertContains(script, "HTMLTextAreaElement.prototype")
            assertContains(script, "input")
            assertContains(script, "change")
        }

    @Test
    fun typing_into_a_contenteditable_replaces_its_text_rather_than_appending() =
        runTest {
            val (controller, _) = run(steps = arrayOf(WorkflowStep.Input("#editor", "typed")))

            val script = controller.evaluatedScripts.single()
            // Without the select-all, `insertText` inserts at the caret: "edit me" became
            // "typededit me" in the browser this was measured in.
            assertContains(script, "selectNodeContents")
            assertContains(script, "execCommand")
            assertContains(script, "insertText")
            // …and the fallback, for a WebView that has retired execCommand.
            assertContains(script, "textContent")
        }

    @Test
    fun typing_into_a_select_matches_an_option_by_value_and_then_by_label() =
        runTest {
            val (controller, _) = run(steps = arrayOf(WorkflowStep.Input("#size", "Large")))

            val script = controller.evaluatedScripts.single()
            // A snapshot renders options by accessible *name*, so an agent reading one naturally
            // passes "Large" where the option's value is "l". Matching value-then-label is what
            // makes the snapshot and this step agree about what identifies an option.
            assertContains(script, "options")
            assertContains(script, ".value===")
            assertContains(script, ".label")
        }

    @Test
    fun a_selector_that_matches_nothing_fails_the_step_instead_of_passing_quietly() =
        runTest {
            val (_, events) = run(status = "missing", steps = arrayOf(WorkflowStep.Input("#gone", "hello")))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "#gone")
        }

    @Test
    fun typing_into_a_checkbox_fails_and_names_the_step_that_does_work() =
        runTest {
            val (_, events) = run(status = "checkable", steps = arrayOf(WorkflowStep.Input("#terms", "true")))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "SetChecked")
        }

    @Test
    fun typing_into_a_plain_div_fails_instead_of_hanging_a_stray_value_property_on_it() =
        runTest {
            val (_, events) = run(status = "not-fillable", steps = arrayOf(WorkflowStep.Input("div#card", "hello")))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "div#card")
            assertContains(failed.message, "contenteditable")
        }

    @Test
    fun typing_into_a_disabled_field_fails_rather_than_reporting_a_value_nobody_stored() =
        runTest {
            val (_, events) = run(status = "disabled", steps = arrayOf(WorkflowStep.Input("#q", "hello")))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "disabled")
        }

    @Test
    fun the_two_argument_form_still_builds_a_fill_step() {
        // The compatibility promise: `Input(selector, text)` and `Input(locator, text)` were the
        // only spellings before this family was split, and every existing caller still uses them.
        assertEquals(WorkflowStep.Input.Fill(css("#q"), "hello"), WorkflowStep.Input("#q", "hello"))
        assertEquals(WorkflowStep.Input.Fill(css("#q"), "hello"), WorkflowStep.Input(css("#q"), text = "hello"))
    }

    @Test
    fun the_dsl_appends_the_same_step_the_constructor_builds() {
        val built = workflow("wf", "dsl") { input("#q", "hello") }

        assertEquals(listOf<WorkflowStep>(WorkflowStep.Input.Fill(css("#q"), "hello")), built.steps)
    }

    @Test
    fun a_fill_is_one_round_trip_so_the_page_cannot_change_underneath_it() =
        runTest {
            val (controller, _) = run(steps = arrayOf(WorkflowStep.Input("#q", "hello")))

            assertEquals(1, controller.evaluatedScripts.size)
            // …and it does not read the value back, which is the read that used to conceal the bug.
            assertFalse(controller.evaluatedScripts.single().contains("textContent??"))
            assertTrue(controller.evaluatedScripts.single().startsWith("(function()"))
        }
}
