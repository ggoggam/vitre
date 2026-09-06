package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.workflow.StepPath
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowFailureKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** One process-local submission. Observing it never executes the workflow again. */
class WorkflowJob internal constructor(
    val id: String,
    val workflow: Workflow,
    val state: StateFlow<WorkflowJobState>,
    /** Conflated progress for display, not a complete event log. Retains the latest pool event. */
    val progress: StateFlow<PoolEvent?>,
    private val execution: Job,
) {
    /** Requests cancellation; [await] observes the terminal state after execution has unwound. */
    fun cancel() {
        execution.cancel()
    }

    /** Cancellation of this waiter does not cancel the submitted job. */
    suspend fun await(): WorkflowJobState = state.first { it.isTerminal }
}

sealed class WorkflowJobState {
    val isTerminal: Boolean get() = this is Completed || this is Failed || this is Cancelled

    data object Queued : WorkflowJobState()

    /** Admitted to execution; may still be waiting for a browser lane used by another caller. */
    data object Running : WorkflowJobState()

    data class Completed(
        val result: WorkflowEvent.Completed,
    ) : WorkflowJobState()

    data class Failed(
        val message: String,
        val path: StepPath? = null,
        val cause: Throwable? = null,
        val kind: WorkflowFailureKind = WorkflowFailureKind.Failure,
    ) : WorkflowJobState()

    /** Cancellation does not roll back page actions that already happened. */
    data class Cancelled(
        val reason: WorkflowCancellationReason,
    ) : WorkflowJobState()
}

enum class WorkflowCancellationReason {
    /** Explicit cancellation, cancellation of the host scope, or cancellation from the workflow. */
    CANCELLED,
    DEADLINE_EXCEEDED,
    QUEUE_CLOSED,
}
