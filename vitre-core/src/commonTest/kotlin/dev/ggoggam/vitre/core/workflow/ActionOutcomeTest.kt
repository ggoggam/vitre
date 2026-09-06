package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.ScriptOutcomeUnknownException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionOutcomeTest {
    @Test
    fun click_rejection_retains_its_kind_and_stops_the_sequence() =
        runTest {
            val page = FakeWebViewController().apply { nextEvalResult = { "\"The target is disabled.\"" } }
            val task =
                workflow("action", "action") {
                    click("#buy")
                    navigate("https://next.test")
                }
            val failure = assertIs<WorkflowEvent.Failed>(WorkflowEngine(page, EmptyCoroutineContext).run(task).toList().last())
            assertEquals(WorkflowFailureKind.ActionRejected, failure.kind)
            assertEquals(emptyList(), page.navigations)
            assertEquals(1, page.evaluatedScripts.size)
        }

    @Test
    fun a_lost_script_callback_retains_unknown_outcome_through_nested_steps() =
        runTest {
            val page =
                FakeWebViewController().apply {
                    nextEvalResult = {
                        if (it == "!!(true)") "true" else throw ScriptOutcomeUnknownException("The action may already have taken effect")
                    }
                }
            val task =
                workflow("action", "action") {
                    runIf(jsTruthy("true")) { evaluateJs("submitForm()") }
                    navigate("https://next.test")
                }
            val failure = assertIs<WorkflowEvent.Failed>(WorkflowEngine(page, EmptyCoroutineContext).run(task).toList().last())
            assertEquals(WorkflowFailureKind.OutcomeUnknown, failure.kind)
            assertEquals(2, page.evaluatedScripts.size)
            assertEquals(StepPath.root(0).child(StepPath.Branch.Then, 0), failure.path)
            assertEquals(emptyList(), page.navigations)
        }

    @Test
    fun missing_click_acknowledgement_is_unknown_instead_of_success() =
        runTest {
            for (answer in listOf("null", "false", "{}", "not json")) {
                val page = FakeWebViewController().apply { nextEvalResult = { answer } }
                val task = workflow("action", "action") { click("#buy") }
                val failure = assertIs<WorkflowEvent.Failed>(WorkflowEngine(page, EmptyCoroutineContext).run(task).toList().last())
                assertEquals(WorkflowFailureKind.OutcomeUnknown, failure.kind)
            }
        }

    @Test
    fun old_failed_event_construction_keeps_the_general_failure_default() {
        assertEquals(WorkflowFailureKind.Failure, WorkflowEvent.Failed(StepPath.root(0), "error").kind)
    }

    @Test
    fun a_valid_json_acknowledgement_allows_the_next_step() =
        runTest {
            val page = FakeWebViewController().apply { nextEvalResult = { " true\n" } }
            val task =
                workflow("action", "action") {
                    click("#buy")
                    navigate("https://next.test")
                }
            assertIs<WorkflowEvent.Completed>(WorkflowEngine(page, EmptyCoroutineContext).run(task).toList().last())
            assertEquals(listOf("https://next.test"), page.navigations)
        }
}
