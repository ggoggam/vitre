package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * "I act on the element a snapshot showed me, and if it is not there any more I am told so."
 *
 * The second half is the point. Every expression the engine generates resolves a missing element to
 * `null` and carries on — `…?.click()`, `(…?.textContent ?? '')` — which is right for a selector
 * that might legitimately match nothing and wrong for a handle, because a handle is a claim that the
 * element was seen. Without a guard, an agent acting on a stale handle is told it succeeded.
 */
class HandleLocatorTest {
    /** Answers the guard with [status] and everything else with [result]. */
    private fun controllerFor(
        status: String,
        result: String = "null",
    ) = FakeWebViewController().apply {
        nextEvalResult = { script -> if ("isConnected" in script) "\"$status\"" else result }
    }

    @Test
    fun clicking_a_handle_resolves_it_through_the_pages_own_registry() =
        runTest {
            val controller = controllerFor(status = "ok")
            val workflow =
                Workflow(
                    id = "wf-handle-click",
                    name = "click-by-handle",
                    steps = listOf(WorkflowStep.Click(handle("e7"))),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            val click = controller.evaluatedScripts.last()
            assertTrue("__vitre" in click, "handles live in the page, not in Kotlin: $click")
            assertTrue("\"e7\"" in click, click)
            assertTrue(click.endsWith("?.click()"), click)
        }

    @Test
    fun a_handle_from_a_previous_page_fails_and_says_to_snapshot_again() =
        runTest {
            val controller = controllerFor(status = "unknown")
            val workflow =
                Workflow(
                    id = "wf-stale",
                    name = "stale-handle",
                    steps = listOf(WorkflowStep.Click(handle("e7"))),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertTrue("e7" in failed.message, failed.message)
            assertTrue("snapshot" in failed.message.lowercase(), "must say how to recover: ${failed.message}")
            // And it must not have gone ahead and clicked whatever `null?.click()` does.
            assertTrue(
                controller.evaluatedScripts.none { it.endsWith("?.click()") },
                "the click ran anyway: ${controller.evaluatedScripts}",
            )
        }

    @Test
    fun a_handle_whose_element_has_been_removed_is_distinguished_from_one_that_never_existed() =
        runTest {
            val controller = controllerFor(status = "detached")
            val workflow =
                Workflow(
                    id = "wf-detached",
                    name = "detached-handle",
                    steps = listOf(WorkflowStep.Input(handle("e2"), "hello")),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            // "removed" and "never issued" call for the same fix but describe different pages, and
            // an agent that cannot tell them apart cannot tell whether its model of the page is
            // stale or simply wrong.
            assertTrue("removed" in failed.message, failed.message)
        }

    @Test
    fun acting_on_a_handle_before_any_snapshot_says_to_take_one() =
        runTest {
            val controller = controllerFor(status = "no-snapshot")
            val workflow =
                Workflow(
                    id = "wf-nosnap",
                    name = "no-snapshot",
                    steps = listOf(WorkflowStep.Extract(handle("e1"), into = "text")),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertTrue("no snapshot" in failed.message.lowercase(), failed.message)
        }

    @Test
    fun selector_addressed_steps_pay_nothing_for_the_guard() =
        runTest {
            val controller = FakeWebViewController()
            val workflow =
                Workflow(
                    id = "wf-css",
                    name = "css-click",
                    steps = listOf(WorkflowStep.Click("#buy")),
                )

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // One round trip, not two: the guard exists for handles and must not tax every workflow
            // that was written before handles existed.
            assertEquals(1, controller.evaluatedScripts.size, "${controller.evaluatedScripts}")
        }

    @Test
    fun a_resolved_locator_survives_having_an_operator_appended_to_it() {
        // Every caller pastes `first()` into something larger — `X?.click()`, `X!==null` — so the
        // expression has to bind as one unit. This is not hypothetical: `(a?b:null)||null` shipped
        // once, and since `?.` and `!==` bind tighter than `||`, `X?.click()` grouped as
        // `X||(null?.click())`. The click never happened, the step went green, and only running it
        // on a device showed anything wrong. Asserting on the *text* of a script cannot catch that;
        // asserting that nothing binds loosely at the top level can.
        for (locator in listOf(css("#buy"), xpath("//button"), handle("e4"))) {
            val expression = LocatorJs.first(locator)
            var depth = 0
            for ((index, character) in expression.withIndex()) {
                when (character) {
                    '(', '[' -> {
                        depth++
                    }

                    ')', ']' -> {
                        depth--
                    }

                    // Anything below `?.`'s precedence, loose at depth 0, regroups the expression
                    // the moment a caller appends to it.
                    '|', '&', '?', ':' -> {
                        if (depth == 0) {
                            fail("${locator.describe()} binds loosely at index $index: $expression")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun every_locator_in_a_row_extraction_is_vetted_not_just_the_row_one() =
        runTest {
            val controller = controllerFor(status = "unknown")
            val workflow =
                Workflow(
                    id = "wf-rows",
                    name = "rows-with-handle-column",
                    steps =
                        listOf(
                            WorkflowStep.ExtractRows(
                                rows = css("li"),
                                columns = mapOf("title" to WorkflowStep.ExtractRows.Column(handle("e9"))),
                                into = "rows",
                            ),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // A column is where a bad handle hides best: the step still returns a full array of
            // records, every one of them carrying the same wrong value.
            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertTrue("e9" in failed.message, failed.message)
        }
}
