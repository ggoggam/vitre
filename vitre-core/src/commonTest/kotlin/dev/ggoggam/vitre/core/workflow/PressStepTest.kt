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
 * "I press Enter in the search box and the page's own key handler runs."
 *
 * Nothing in the vocabulary could do this before. `Input` fires `input` and `change`, so a box whose
 * page listens for `keydown` — every type-ahead, every arrow-key menu, every Enter-to-search — saw
 * the text appear and no keystroke arrive.
 */
class PressStepTest {
    private suspend fun run(
        status: String = "ok",
        step: WorkflowStep,
    ): Pair<FakeWebViewController, List<WorkflowEvent>> {
        val controller = FakeWebViewController().apply { nextEvalResult = { "\"$status\"" } }
        val workflow = Workflow(id = "wf-press", name = "press", steps = listOf(step))
        return controller to WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()
    }

    @Test
    fun a_press_dispatches_keydown_then_keypress_then_keyup() =
        runTest {
            val (controller, events) = run(step = WorkflowStep.Input.Press("#q", "Enter"))

            assertIs<WorkflowEvent.Completed>(events.last())
            val script = controller.evaluatedScripts.single()
            val down = script.indexOf("'keydown'")
            val press = script.indexOf("'keypress'")
            val up = script.indexOf("'keyup'")
            assertTrue(down >= 0 && press > down && up > press, "expected keydown→keypress→keyup in: $script")
            assertContains(script, "KeyboardEvent")
            // Focus first: a page's handler is normally bound to the field, and a key event
            // dispatched at an unfocused element is one a real keystroke could never produce.
            assertContains(script, "focus")
        }

    @Test
    fun enter_carries_the_key_the_code_and_the_legacy_key_code() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.Press("#q", "Enter"))

            val script = controller.evaluatedScripts.single()
            assertContains(script, "key:\"Enter\"")
            assertContains(script, "code:\"Enter\"")
            // `keyCode`/`which` are deprecated and still what a lot of shipped page code reads.
            assertContains(script, "keyCode:13")
            assertContains(script, "which:13")
        }

    @Test
    fun an_arrow_key_carries_its_own_code_rather_than_the_key_name() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.Press("#menu", "ArrowDown"))

            val script = controller.evaluatedScripts.single()
            assertContains(script, "key:\"ArrowDown\"")
            assertContains(script, "code:\"ArrowDown\"")
            assertContains(script, "keyCode:40")
        }

    @Test
    fun a_single_character_gets_the_physical_key_it_sits_on() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.Press("#q", "a"))

            val script = controller.evaluatedScripts.single()
            assertContains(script, "key:\"a\"")
            // `code` names the physical key, which is the same one whether or not shift is down —
            // so a lowercase `a` and an uppercase `A` are both `KeyA`.
            assertContains(script, "code:\"KeyA\"")
            assertContains(script, "keyCode:65")
        }

    @Test
    fun a_key_that_produces_no_character_skips_keypress_the_way_a_browser_does() =
        runTest {
            val (controller, _) = run(step = WorkflowStep.Input.Press("#dialog", "Escape"))

            val script = controller.evaluatedScripts.single()
            assertContains(script, "'keydown'")
            assertContains(script, "'keyup'")
            assertFalse("keypress" in script, "browsers fire keypress only for character keys: $script")
        }

    @Test
    fun a_key_nobody_can_press_fails_at_the_step_rather_than_dispatching_nonsense() =
        runTest {
            val (controller, events) = run(step = WorkflowStep.Input.Press("#q", "Enterr"))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "Enterr")
            assertTrue(controller.evaluatedScripts.isEmpty(), "nothing should reach the page")
        }

    @Test
    fun pressing_at_a_selector_that_matches_nothing_fails() =
        runTest {
            val (_, events) = run(status = "missing", step = WorkflowStep.Input.Press("#gone", "Enter"))

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "#gone")
        }

    @Test
    fun the_dsl_appends_the_same_step_the_constructor_builds() {
        val built = workflow("wf", "dsl") { press("#q", "Enter") }

        assertEquals(listOf<WorkflowStep>(WorkflowStep.Input.Press(css("#q"), "Enter")), built.steps)
    }
}
