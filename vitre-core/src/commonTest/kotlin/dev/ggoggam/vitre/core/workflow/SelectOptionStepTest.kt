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
 * "I pick the option I can see, and if it is not there I am told what is."
 *
 * The old step assigned `el.value = 'Large'` and a `<select>` silently discards a value no option
 * carries — leaving the control blank and the step green. That is worse than a mismatch, because
 * `PageSnapshot` renders options by accessible *name*: an agent that reads `option "Large"` out of a
 * snapshot and passes it back was, until now, guaranteed to hit the failing path.
 */
class SelectOptionStepTest {
    private suspend fun run(
        status: String = "ok",
        step: WorkflowStep,
    ): Pair<FakeWebViewController, List<WorkflowEvent>> {
        val controller = FakeWebViewController().apply { nextEvalResult = { "\"$status\"" } }
        val workflow = Workflow(id = "wf-select", name = "select", steps = listOf(step))
        return controller to WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()
    }

    @Test
    fun an_option_is_matched_on_its_value_and_then_on_its_visible_label() =
        runTest {
            val (controller, events) = run(step = WorkflowStep.Input.SelectOption("#size", "Large"))

            assertIs<WorkflowEvent.Completed>(events.last())
            val script = controller.evaluatedScripts.single()
            assertContains(script, "options")
            assertContains(script, ".value===")
            assertContains(script, ".label")
            assertContains(script, "\"Large\"")
            // A `change` is what a controlled <select> listens for — React reads the native event
            // for selects rather than going through its value tracker.
            assertContains(script, "change")
        }

    @Test
    fun the_selection_is_made_by_index_so_two_options_sharing_a_value_stay_distinct() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.SelectOption("#size", "Large"))

            assertContains(controller.evaluatedScripts.single(), "selectedIndex")
        }

    @Test
    fun an_option_that_does_not_exist_fails_and_lists_the_ones_that_do() =
        runTest {
            val (_, events) =
                run(
                    status = "no-option Small | Medium | Large",
                    step = WorkflowStep.Input.SelectOption("#size", "Enormous"),
                )

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "Enormous")
            assertContains(failed.message, "Small | Medium | Large")
        }

    @Test
    fun pointing_the_step_at_something_that_is_not_a_select_fails_loudly() =
        runTest {
            val (_, events) = run(status = "not-select", step = WorkflowStep.Input.SelectOption("#q", "Large"))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "select")
            assertContains(failed.message, "#q")
        }

    @Test
    fun the_dsl_appends_the_same_step_the_constructor_builds() {
        val built = workflow("wf", "dsl") { selectOption("#size", "Large") }

        assertEquals(listOf<WorkflowStep>(WorkflowStep.Input.SelectOption(css("#size"), "Large")), built.steps)
    }
}
