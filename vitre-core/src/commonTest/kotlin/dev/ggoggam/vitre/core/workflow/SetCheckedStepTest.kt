package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * "I tick a checkbox and it is actually ticked."
 *
 * `Input` used to accept a checkbox and write its *value attribute*, leaving `checked` false and
 * firing no `change` — a step that reported success having done nothing a user could see. The
 * desired state is a `Boolean` here rather than the old step's string on purpose:
 * `Input(box, "false")` would be a non-empty string, and every reasonable way to interpret one is
 * either truthy (so `"false"` ticks the box) or a lookup table nobody can guess from the call site.
 */
class SetCheckedStepTest {
    private suspend fun run(
        status: String = "ok",
        step: WorkflowStep,
    ): Pair<FakeWebViewController, List<WorkflowEvent>> {
        val controller = FakeWebViewController().apply { nextEvalResult = { "\"$status\"" } }
        val workflow = Workflow(id = "wf-check", name = "check", steps = listOf(step))
        return controller to WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()
    }

    @Test
    fun ticking_a_box_clicks_it_rather_than_assigning_checked() =
        runTest {
            val (controller, events) = run(step = WorkflowStep.Input.SetChecked("#terms", true))

            assertIs<WorkflowEvent.Completed>(events.last())
            val script = controller.evaluatedScripts.single()
            // `el.checked = true` moves the DOM and fires nothing, so a framework listening for
            // `change` never learns. `click()` runs the browser's own activation behaviour, which
            // flips `checked` *and* fires input/change the way a finger on the box would.
            assertContains(script, ".click()")
            assertContains(script, "el.checked")
            assertContains(script, "true")
        }

    @Test
    fun a_box_already_in_the_wanted_state_is_left_alone() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.SetChecked("#terms", false))

            // Clicking unconditionally would toggle a box that was already right — the single most
            // likely way for a re-run of a workflow to undo itself.
            assertContains(controller.evaluatedScripts.single(), "!==")
        }

    @Test
    fun a_page_that_cancels_the_click_fails_the_step() =
        runTest {
            val (_, events) = run(status = "unchanged", step = WorkflowStep.Input.SetChecked("#terms", true))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "#terms")
        }

    @Test
    fun an_aria_checkbox_is_refused_with_the_action_that_does_work() =
        runTest {
            val (_, events) = run(status = "not-checkable", step = WorkflowStep.Input.SetChecked("#toggle", true))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "Click")
        }

    @Test
    fun unchecking_a_radio_says_why_it_cannot_be_done() =
        runTest {
            val (_, events) = run(status = "radio-uncheck", step = WorkflowStep.Input.SetChecked("#ship-fast", false))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "radio")
        }

    @Test
    fun a_disabled_box_fails_instead_of_swallowing_the_click() =
        runTest {
            val (_, events) = run(status = "disabled", step = WorkflowStep.Input.SetChecked("#terms", true))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "disabled")
        }

    @Test
    fun the_dsl_appends_the_same_step_the_constructor_builds() {
        val built = workflow("wf", "dsl") { setChecked("#terms", true) }

        assertEquals(listOf<WorkflowStep>(WorkflowStep.Input.SetChecked(css("#terms"), true)), built.steps)
    }
}
