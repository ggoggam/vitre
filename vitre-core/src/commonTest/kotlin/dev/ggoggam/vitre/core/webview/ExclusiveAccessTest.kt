package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * "My multi-step sequence runs against the page I left, not the one somebody else changed."
 *
 * Ordering already makes each operation indivisible. What it cannot do is make a *sequence* one, and
 * a sequence is what every interesting piece of automation is: wait, then read; snapshot, then click
 * what the snapshot showed. The gap between two of a caller's steps is exactly where another
 * caller's step lands.
 */
class ExclusiveAccessTest {
    @Test
    fun a_second_caller_waits_until_the_sequence_finishes() =
        runTest {
            val controller = FakeWebViewController()
            val finish = CompletableDeferred<Unit>()

            val sequence =
                launch {
                    controller.exclusively {
                        // Reentrancy, first of all: the lock is not reentrant by nature, so a caller
                        // that took it and then used the WebView would otherwise deadlock on itself.
                        controller.evaluateJs("mine-first()")
                        finish.await()
                        controller.evaluateJs("mine-second()")
                    }
                }
            runCurrent()

            val other = launch { controller.evaluateJs("theirs()") }
            runCurrent()

            assertEquals(
                listOf("mine-first()"),
                controller.evaluatedScripts,
                "another caller got in between the two halves of the sequence",
            )

            finish.complete(Unit)
            sequence.join()
            other.join()

            assertEquals(listOf("mine-first()", "mine-second()", "theirs()"), controller.evaluatedScripts)
        }

    @Test
    fun a_claim_can_be_used_from_a_coroutine_that_did_not_take_it() =
        runTest {
            val controller = FakeWebViewController()
            val granted = CompletableDeferred<ExclusiveAccess>()
            val finish = CompletableDeferred<Unit>()

            val holder =
                launch {
                    controller.exclusively { access ->
                        granted.complete(access)
                        finish.await()
                    }
                }
            val access = granted.await()

            // This is the MCP shape: the claim is held by a parked coroutine, and each tool call
            // arrives on one of its own and has to be let in rather than queued behind the holder.
            val toolCall = launch { access.use { controller.evaluateJs("under-claim()") } }
            runCurrent()

            assertEquals(listOf("under-claim()"), controller.evaluatedScripts)

            finish.complete(Unit)
            holder.join()
            toolCall.join()
        }

    @Test
    fun a_workflow_run_inside_a_claim_does_not_deadlock_against_it() =
        runTest {
            val controller = FakeWebViewController()

            // The engine runs on Dispatchers.Default and reaches it through `flowOn`, so the claim
            // has to survive a context switch it never asked for. If it does not, this hangs rather
            // than failing, which is the honest signal — a deadlock is what the bug would be.
            controller.exclusively {
                val events =
                    WorkflowEngine(controller)
                        .run(
                            Workflow(
                                id = "wf-in-claim",
                                name = "in-claim",
                                steps = listOf(WorkflowStep.EvaluateJs("1+1")),
                            ),
                        ).toList()
                assertIs<WorkflowEvent.Completed>(events.last())
            }

            assertEquals(listOf("1+1"), controller.evaluatedScripts)
        }
}
