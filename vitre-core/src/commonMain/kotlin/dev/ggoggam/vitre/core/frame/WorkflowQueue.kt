package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Executes submitted workflows once, independently of their observers.
 *
 * [capacity] bounds all outstanding jobs, including running jobs. [parallelism] bounds top-level
 * workflow executions. Admission is immediate and throws [WorkflowQueueFullException] when full;
 * it never creates an unbounded collection of waiting submitters. A completed job frees its slot.
 *
 * Children in a workflow's fan-out borrow lanes directly and do not need a top-level job permit,
 * so a parent waiting for its children does not exhaust their execution capacity.
 *
 * The host owns [scope] and must [close] this queue when done, or cancel the scope. Closing stops
 * admission and cancels queued and running jobs; it does not dispose [pool]. Retain returned
 * [WorkflowJob] handles in the host for reconnection and observation. The queue does not retain
 * completed jobs, persist state, retry failures, or resume page actions after process death.
 */
class WorkflowQueue(
    scope: CoroutineScope,
    private val pool: FramePool,
    capacity: Int = 64,
    parallelism: Int = pool.laneCount,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val executions = CoroutineScope(scope.coroutineContext + owner)
    private val admitted = Semaphore(capacity)
    private val running = Semaphore(parallelism)

    /**
     * Submits a new execution with a unique ID, even when [workflow] was submitted before.
     * [timeoutMs] is a total deadline from submission, including waiting for an execution slot
     * and a browser lane. A null timeout has no queue-imposed deadline. Deadlines cancel
     * cooperatively and the terminal state is published after execution cleanup finishes.
     *
     * Observing [WorkflowJob.state], [WorkflowJob.progress], or [WorkflowJob.await] has no effect
     * on execution ownership. The host scope, [close], and [WorkflowJob.cancel] control it.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun submit(
        workflow: Workflow,
        timeoutMs: Long? = null,
    ): WorkflowJob {
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive" }
        if (!owner.isActive) throw WorkflowQueueClosedException()
        val id = Uuid.random().toString()
        if (!admitted.tryAcquire()) throw WorkflowQueueFullException()
        val state = MutableStateFlow<WorkflowJobState>(WorkflowJobState.Queued)
        val progress = MutableStateFlow<PoolEvent?>(null)
        var outcome: WorkflowJobState? = null
        val execution =
            executions.launch(start = CoroutineStart.LAZY) {
                try {
                    running.withPermit {
                        state.value = WorkflowJobState.Running
                        pool.run(listOf(workflow), context).collect { event ->
                            progress.value = event
                            when (val detail = event.event) {
                                is WorkflowEvent.Completed -> outcome = WorkflowJobState.Completed(detail)
                                is WorkflowEvent.Failed -> outcome = WorkflowJobState.Failed(detail.message, detail.path)
                                else -> Unit
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    outcome = WorkflowJobState.Failed(failure.message ?: "workflow failed", cause = failure)
                }
            }
        execution.invokeOnCompletion { failure ->
            admitted.release()
            state.value =
                when (failure) {
                    is DeadlineExceeded -> WorkflowJobState.Cancelled(WorkflowCancellationReason.DEADLINE_EXCEEDED)
                    is QueueClosed -> WorkflowJobState.Cancelled(WorkflowCancellationReason.QUEUE_CLOSED)
                    is CancellationException -> WorkflowJobState.Cancelled(WorkflowCancellationReason.CANCELLED)
                    null -> outcome ?: WorkflowJobState.Failed("workflow ended without an outcome")
                    else -> WorkflowJobState.Failed(failure.message ?: "workflow failed", cause = failure)
                }
        }
        if (timeoutMs != null) {
            // Start the timer now, even if the host dispatcher has not started execution yet.
            val deadline =
                executions.launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(timeoutMs)
                    execution.cancel(DeadlineExceeded())
                }
            execution.invokeOnCompletion { deadline.cancel() }
        }
        execution.start()
        return WorkflowJob(id, workflow, state.asStateFlow(), progress.asStateFlow(), execution)
    }

    /** Stops admission and requests cancellation; [closeAndJoin] also awaits cleanup. */
    fun close() {
        owner.cancel(QueueClosed())
    }

    suspend fun closeAndJoin() {
        close()
        owner.join()
    }

    private class DeadlineExceeded : CancellationException("workflow deadline exceeded")

    private class QueueClosed : CancellationException("workflow queue is closed")
}

class WorkflowQueueFullException : IllegalStateException("workflow queue capacity is exhausted")

class WorkflowQueueClosedException : IllegalStateException("workflow queue is closed")
