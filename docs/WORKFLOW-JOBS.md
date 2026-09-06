# Submitted workflow jobs

`FramePool.run()` returns a cold flow: collecting it starts execution, collecting again starts
another execution, and cancelling the collector cancels that run. `WorkflowQueue` is the API for
a host that needs a submitted task to continue while a UI reconnects or an agent observes it later.

```kotlin
import dev.ggoggam.vitre.core.frame.WorkflowJobState
import dev.ggoggam.vitre.core.frame.WorkflowQueue
import dev.ggoggam.vitre.core.frame.WorkflowQueueFullException

val jobs = WorkflowQueue(
    scope = hostScope,
    pool = pool,
    capacity = 32,
    parallelism = pool.laneCount,
)

val job = try {
    jobs.submit(checkAvailability, timeoutMs = 60_000)
} catch (full: WorkflowQueueFullException) {
    showBusy()
    return
}

// Keep this handle in the host's session or view model.
sessionJobs[job.id] = job

// Any number of collectors may observe this StateFlow. They never start another execution.
uiScope.launch { job.state.collect { render(it) } }
agentScope.launch {
    when (val result = job.await()) {
        is WorkflowJobState.Completed -> use(result.result.variables)
        is WorkflowJobState.Failed -> report(result.message, result.path)
        is WorkflowJobState.Cancelled -> report(result.reason)
        else -> Unit // await only returns terminal states
    }
}
```

`capacity` counts all outstanding jobs, both queued and running. Submission fails immediately when
that limit is reached. Completed, failed, and cancelled jobs free their admission slot after
execution cleanup finishes. The host decides whether to try submitting again after rejection.

`parallelism` limits top-level workflow executions. A running workflow may still wait for a lane
that another caller owns. Fan-out children borrow lanes directly; they do not consume a top-level
job permit. One job can therefore fan out and finish with `parallelism = 1` and a one-lane pool.

Each handle starts in `Queued`, moves to `Running` when admitted to execution, and ends in
`Completed`, `Failed`, or `Cancelled`. StateFlow conflates updates, so a late or slow observer may
see only the terminal state. `job.progress` similarly retains the latest `PoolEvent` for display;
it is not an audit log. A workflow failure is kept on its own handle and does not cancel other jobs.

`timeoutMs` starts at submission and covers scheduler waiting, lane waiting, and execution. Expiry
requests cooperative cancellation and ends in `Cancelled(DEADLINE_EXCEEDED)`. Cleanup may extend
past the deadline. `job.cancel()` explicitly cancels one job; cancelling a collector or an `await()`
caller has no effect on the submitted job.

`jobs.close()` rejects new submissions and requests cancellation of waiting and running work.
`jobs.closeAndJoin()` also waits for cancellation cleanup. Cancelling `hostScope` cancels the queue
and all its jobs. The queue does not close the pool's WebViews; the platform host still owns them.

Closing the underlying `FramePool` wakes jobs waiting for a lane with a failure. Jobs still waiting
for a top-level execution slot fail when admitted; existing lane owners may finish according to
the pool's close contract. Use `jobs.close()` when every accepted job must be cancelled immediately.
A pool with every lane quarantined similarly fails subsequent jobs without retrying broken lanes.

The IDs and states are process-local. Keep handles in the host for lookup by ID; the queue retains
no completed-job registry. Submitting the same workflow again creates a new ID and executes it
again: job IDs are not idempotency keys. There is no persistence, automatic retry, durable
checkpoint, or recovery after process death. Cancellation cannot undo a click, form submission,
or other page action that already happened. Inspect the resulting page or application state before
deciding whether a failed or cancelled workflow is safe to submit again.
