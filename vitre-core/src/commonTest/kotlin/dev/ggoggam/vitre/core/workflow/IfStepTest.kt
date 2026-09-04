package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.frame.LaneSource
import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The branch a run takes, and the paths it reports taking it.
 *
 * `WorkflowEngineHappyPathTest` covers the un-nested stream; everything here is about what nesting
 * changed.
 */
class IfStepTest {
    @Test
    fun then_branch_runs_when_the_condition_holds() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "true" } }
            val workflow =
                workflow("wf-if", "banner") {
                    runIf(exists("#banner"), otherwise = { navigate("https://else.test") }) {
                        navigate("https://then.test")
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://then.test"), controller.navigations)
            assertIs<WorkflowEvent.Completed>(events.last())
        }

    @Test
    fun else_branch_runs_when_the_condition_does_not_hold() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "false" } }
            val workflow =
                workflow("wf-if", "banner") {
                    runIf(exists("#banner"), otherwise = { navigate("https://else.test") }) {
                        navigate("https://then.test")
                    }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://else.test"), controller.navigations)
        }

    @Test
    fun a_missing_else_branch_is_a_no_op_rather_than_a_failure() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "false" } }
            val workflow =
                workflow("wf-if", "optional banner") {
                    runIf(exists("#banner")) { click("#banner .close") }
                    navigate("https://after.test")
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(listOf("https://after.test"), controller.navigations)
        }

    /**
     * The reason the events carry a path at all. A flat index would report this as "step 0", which
     * is the `If` — the one step in the workflow that did not fail.
     */
    @Test
    fun a_failure_inside_a_branch_reports_the_nested_path() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { script ->
                        if (script.contains("boom")) throw IllegalStateException("boom") else "true"
                    }
                }
            val workflow =
                workflow("wf-if", "nested failure") {
                    runIf(exists("#banner")) {
                        navigate("https://ok.test")
                        evaluateJs("boom()")
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals("boom", failed.message)
            assertEquals(StepPath.root(0).child(StepPath.Branch.Then, 1), failed.path)
            assertEquals("0.then.1", failed.path.toString())
        }

    /** A composite step brackets its children, so the stream has the shape the steps do. */
    @Test
    fun the_if_completes_after_the_branch_it_took() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "true" } }
            val workflow =
                workflow("wf-if", "bracketing") {
                    runIf(exists("#banner")) { navigate("https://inner.test") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val outer = StepPath.root(0)
            val inner = outer.child(StepPath.Branch.Then, 0)
            assertEquals(
                listOf<WorkflowEvent>(
                    WorkflowEvent.LaneLeased(LaneSource.SOLO_LANE_ID),
                    WorkflowEvent.StepStarted(outer, workflow.steps[0]),
                    WorkflowEvent.StepStarted(inner, WorkflowStep.Navigate("https://inner.test")),
                    WorkflowEvent.StepCompleted(inner),
                    WorkflowEvent.StepCompleted(outer),
                    WorkflowEvent.Completed(emptyMap()),
                ),
                events,
            )
        }

    @Test
    fun ifs_nest() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "true" } }
            val workflow =
                workflow("wf-if", "nested") {
                    runIf(exists("#a")) {
                        runIf(exists("#b"), otherwise = { navigate("https://inner-else.test") }) {
                            navigate("https://inner-then.test")
                        }
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://inner-then.test"), controller.navigations)
            val deepest = events.filterIsInstance<WorkflowEvent.StepStarted>().maxBy { it.path.depth }
            assertEquals("0.then.0.then.0", deepest.path.toString())
            assertIs<WorkflowEvent.Completed>(events.last())
        }

    /** A branch shares the run's variables — it is a part of the workflow, not a sub-workflow. */
    @Test
    fun a_branch_writes_into_the_same_variables() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { script -> if (script.endsWith("!==null")) "true" else "\"42\"" }
                }
            val workflow =
                workflow("wf-if", "variables") {
                    runIf(exists("#total")) { extract("#total", into = "total") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(mapOf("total" to "42"), assertIs<WorkflowEvent.Completed>(events.last()).variables)
        }

    /**
     * A condition reading a variable an earlier step extracted is the thing build-time `if` cannot
     * do, and the reason `runIf` exists at all.
     */
    @Test
    fun a_condition_can_read_a_variable_an_earlier_step_extracted() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "\"expired\"" } }
            val workflow =
                workflow("wf-if", "session") {
                    extract("#status", into = "status")
                    runIf(variableEquals("status", "expired")) { navigate("https://refresh.test") }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://refresh.test"), controller.navigations)
        }

    @Test
    fun a_condition_on_an_unset_variable_fails_the_step_and_names_what_was_set() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "\"ok\"" } }
            val workflow =
                workflow("wf-if", "typo") {
                    extract("#status", into = "status")
                    runIf(variableEquals("statuss", "ok")) { navigate("https://never.test") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertContains(failed.message, "No variable `statuss`")
            assertContains(failed.message, "status")
            assertEquals(StepPath.root(1), failed.path)
            assertTrue(controller.navigations.isEmpty())
        }

    @Test
    fun an_uncompilable_regex_fails_the_step_rather_than_taking_the_else_branch() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "\"ok\"" } }
            val workflow =
                workflow("wf-if", "bad regex") {
                    extract("#status", into = "status")
                    runIf(variableMatches("status", "["), otherwise = { navigate("https://else.test") }) {
                        navigate("https://then.test")
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertContains(assertIs<WorkflowEvent.Failed>(events.last()).message, "Not a valid regex")
            assertTrue(controller.navigations.isEmpty())
        }

    /** Truthiness is the page's to decide, so `0` is false without this side reimplementing JS. */
    @Test
    fun js_truthy_wraps_the_script_so_the_page_answers_with_a_boolean() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { script -> if (script == "!!(0)") "false" else "true" }
                }
            val workflow =
                workflow("wf-if", "truthy") {
                    runIf(jsTruthy("0"), otherwise = { navigate("https://falsy.test") }) {
                        navigate("https://truthy.test")
                    }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://falsy.test"), controller.navigations)
            assertContains(controller.evaluatedScripts, "!!(0)")
        }

    @Test
    fun compound_conditions_short_circuit() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "false" } }
            val workflow =
                workflow("wf-if", "short circuit") {
                    runIf(exists("#first") and jsTruthy("secondNeverRuns()")) { navigate("https://never.test") }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertTrue(controller.evaluatedScripts.none { it.contains("secondNeverRuns") })
        }

    /**
     * The one place a missing element is an answer. Every other step vets a handle up front and
     * fails on a stale one, because acting on nothing looks like acting successfully.
     */
    @Test
    fun a_stale_handle_in_a_condition_is_false_rather_than_a_failure() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "false" } }
            val workflow =
                workflow("wf-if", "stale handle") {
                    runIf(exists(handle("e7")), otherwise = { navigate("https://gone.test") }) {
                        click(handle("e7"))
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(listOf("https://gone.test"), controller.navigations)
        }
}
