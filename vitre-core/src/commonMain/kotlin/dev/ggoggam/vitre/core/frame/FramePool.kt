package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.net.NetworkTap
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * A bounded set of independently drivable lanes, and the queue that feeds them.
 *
 * A pool is the unit a caller actually wants: "run these workflows against these sites" rather than
 * "manage four browsers". [run] is the way in — hand it every workflow and it drains them across
 * however many lanes exist:
 *
 * ```
 * pool.run(shops.map { it.workflow(query) }).collect { (index, laneId, _, event) ->
 *     …
 * }
 * ```
 *
 * **The lane count is not the task count, and that is the point.** A pool is sized to what the
 * device can carry, which is not something a caller can know in advance — see the platform pools.
 * Six workflows submitted to a pool of two run three deep rather than losing four of them, and
 * zipping tasks against [laneIds] by index (the shape this API used to have) is exactly the bug
 * that arrangement invites.
 *
 * The pool is a [LaneSource], and that is how the queue works: every submitted workflow gets a
 * `WorkflowEngine` over the pool, and the engine borrows a lane when it needs a page and returns it
 * when it does not — at the end, and at every fan-out. Lanes go to whoever asked first. A workflow
 * that fans out therefore spreads its items over every lane the pool has, including the one it
 * just gave up, and a pool of one still finishes it: the parent is never holding what its children
 * wait for. See [LaneSource] for the argument, and `docs/PARALLEL-LANES.md`.
 *
 * The work really is concurrent. A lane owns its WebView outright, so an operation is serialised
 * only against other callers of *that* lane; nothing a lane does waits on its neighbours.
 *
 * [lane] remains for a caller that wants one specific lane and its own `WorkflowEngine` — driving a
 * lane directly *while* [run] is draining into it is the caller's problem, and not one worth
 * having.
 */
class FramePool internal constructor(
    val laneIds: List<String>,
    /** Traffic the platform interceptor saw, or null if this WebView is not intercepting. */
    val tap: NetworkTap?,
    private val lanes: Map<String, WebViewController>,
) : LaneSource {
    private val mutableState = MutableStateFlow(PoolState())

    /** Ownership and health, independent of any one run's event collector. */
    val state: StateFlow<PoolState> = mutableState.asStateFlow()
    private val admissionGate = Mutex()

    /**
     * The lanes nobody is on. A channel rather than a semaphore plus a free list because a channel
     * is both at once, and hands lanes to waiting receivers in the order they arrived.
     */
    private val free =
        Channel<Lane>(Channel.UNLIMITED, onUndeliveredElement = ::returnUndeliveredLane).apply {
            for (id in laneIds) trySend(Lane(id, lanes.getValue(id)))
        }

    // A receive can consume a lane and then throw cancellation before the borrower resumes. In
    // that case acquire's try/finally never sees it. The channel returns ownership here instead.
    private fun returnUndeliveredLane(lane: Lane) {
        if (!state.value.closed && lane.id !in state.value.unavailableLaneIds) free.trySend(lane)
    }

    /** @throws IllegalArgumentException if [id] is not one of [laneIds]. */
    fun lane(id: String): WebViewController =
        lanes[id] ?: throw IllegalArgumentException("no such lane: $id (have ${laneIds.joinToString()})")

    val allLanes: List<WebViewController> get() = laneIds.map { lanes.getValue(it) }

    /** How many workflows may be in flight at once. Decided by the device, not by the caller. */
    val laneCount: Int get() = laneIds.size

    override val parallelism: Int get() = laneCount

    /**
     * Stops admission and wakes waiting borrowers. Existing borrowers may finish and release;
     * native WebView disposal remains the platform owner's responsibility.
     */
    fun close() {
        mutableState.update { it.copy(closed = true) }
        free.close(PoolUnavailableException("The lane pool is closed"))
    }

    /**
     * The next free lane, blanked, with [label] painted on it while its page is on the way.
     *
     * Every lease starts on a blank lane, not only the first of a task. Under the old
     * one-lane-per-site arrangement resetting was a between-runs nicety; with a queue, lane reuse
     * is the normal case, and a `WaitFor` matching the *previous* borrower's leftover DOM is a
     * failure that looks exactly like success. A lane that cannot be blanked is quarantined so
     * it cannot fail subsequent unrelated tasks. Rebuild the platform pool to replace unhealthy
     * WebViews. If every lane is unavailable, queued borrowers fail promptly.
     */
    override suspend fun acquire(label: String): Lane {
        val lane = admissionGate.withLock { claimLane() }
        return prepareLane(lane, label)
    }

    /** Called under admissionGate. Mark ownership before another admission or reset may start. */
    private suspend fun claimLane(): Lane {
        if (state.value.closed) throw PoolUnavailableException("The lane pool is closed")
        val lane = free.receive()
        val claimed = mutableState.updateAndGet { if (it.closed) it else it.copy(leasedLaneIds = it.leasedLaneIds + lane.id) }
        if (claimed.closed) throw PoolUnavailableException("The lane pool is closed")
        return lane
    }

    private suspend fun prepareLane(
        lane: Lane,
        label: String,
    ): Lane {
        try {
            lane.controller.loadHtml(placeholderHtml(lane.id, label))
        } catch (cancelled: CancellationException) {
            release(lane)
            throw cancelled
        } catch (t: Throwable) {
            val health =
                mutableState.updateAndGet {
                    it.copy(leasedLaneIds = it.leasedLaneIds - lane.id, unavailableLaneIds = it.unavailableLaneIds + lane.id)
                }
            if (health.unavailableLaneIds.size == laneCount) {
                free.close(PoolUnavailableException("Every lane is unavailable; rebuild the platform pool"))
            }
            throw t
        }
        return lane
    }

    override fun release(lane: Lane) {
        require(lanes[lane.id] === lane.controller) { "lane ${lane.id} does not belong to this pool" }
        mutableState.update {
            require(lane.id in it.leasedLaneIds) { "lane ${lane.id} is not leased" }
            it.copy(leasedLaneIds = it.leasedLaneIds - lane.id)
        }
        returnUndeliveredLane(lane)
    }

    /**
     * Runs every workflow in [workflows], at most [laneCount] at a time, and reports as it goes.
     *
     * Emissions from different lanes interleave — there is no ordering between tasks and there
     * cannot be, since that is the whole point. Within one task, events arrive in the order
     * `WorkflowEngine` produced them, beginning with a [WorkflowEvent.LaneLeased] and ending in
     * [WorkflowEvent.Completed] or [WorkflowEvent.Failed]. A workflow that fails costs its own
     * task and nothing else: its lane goes back to the pool and the next borrower takes it.
     *
     * A fixed number of workers admit workflows from the list, so pending workflows do not each
     * retain a coroutine. Fan-outs release their lane and process children with a separate bounded
     * budget, including inline work when that budget is exhausted. [PoolEvent.laneId] follows the
     * borrows: it is the lane of the most recent lease in that task, and null only for the event of
     * a task that failed before its first lease.
     *
     * The returned flow completes when the queue is drained. Cancelling the collector cancels the
     * lanes mid-step.
     */
    fun run(
        workflows: List<Workflow>,
        context: CoroutineContext = Dispatchers.Default,
    ): Flow<PoolEvent> =
        channelFlow {
            val admission = Mutex()
            var next = 0
            repeat(minOf(laneCount, workflows.size)) {
                launch {
                    while (true) {
                        val index = admission.withLock { if (next < workflows.size) next++ else null } ?: break
                        val workflow = workflows[index]
                        var laneId: String? = null
                        WorkflowEngine(this@FramePool, context).run(workflow).collect { event ->
                            if (event is WorkflowEvent.LaneLeased) laneId = event.laneId
                            send(PoolEvent(index, laneId, workflow, event))
                        }
                    }
                }
            }
        }

    /**
     * Reserves and blanks every healthy lane, then returns them together.
     *
     * Between runs rather than before one: a lane still showing the previous run's results looks
     * exactly like a lane that has already finished the current one, and that ambiguity has cost
     * more debugging time than it sounds like it should. A borrowed lane is reset only after its
     * current owner releases it, so a concurrent reset cannot replace an active borrower's page.
     */
    suspend fun resetAll(label: String = "idle") =
        admissionGate.withLock {
            // Stop new admission while owners finish. This also avoids two resets each holding
            // half the pool, and a lane being quarantined while a reset waits to reserve it.
            state.first { it.closed || it.leasedLaneIds.isEmpty() }
            val count = laneCount - state.value.unavailableLaneIds.size
            if (state.value.closed || count == 0) throw PoolUnavailableException("The lane pool is unavailable")
            val borrowed = mutableListOf<Lane>()
            try {
                repeat(count) { borrowed += prepareLane(claimLane(), label) }
            } finally {
                borrowed.forEach(::release)
            }
        }

    /**
     * `about:blank` would be simpler and is a trap: it is not a document the injected runtime ever
     * reports ready for on every platform, so resetting a lane to it can hang the reset itself.
     * A real, if tiny, document always answers.
     */
    private fun placeholderHtml(
        laneId: String,
        label: String,
    ): String =
        """
        <!doctype html><meta charset="utf-8">
        <style>
          :root { color-scheme: light dark; }
          body { margin:0; height:100vh; display:grid; place-content:center; gap:2px;
                 font:12px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                 color:#8e8e9a; text-align:center; }
          b { font-size:11px; letter-spacing:.08em; }
        </style>
        <b>LANE ${laneId.uppercase().htmlEscaped()}</b><span>${label.htmlEscaped()}</span>
        """.trimIndent()

    /**
     * Escapes the two characters that break out of this text into markup. [label] is
     * [Workflow.name], which the caller controls: an unescaped `<` corrupts the placeholder, and a
     * `</span><script>…` in a name would run script in a document that has the bridge installed.
     */
    private fun String.htmlEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Current pool ownership. A quarantined lane remains unavailable until the pool is rebuilt. */
data class PoolState(
    val closed: Boolean = false,
    val leasedLaneIds: Set<String> = emptySet(),
    val unavailableLaneIds: Set<String> = emptySet(),
)

/** The pool was closed or cannot provide any healthy lanes. */
class PoolUnavailableException(
    message: String,
) : IllegalStateException(message)

/**
 * One workflow's progress, and which lane it is on.
 *
 * Flat rather than a sealed hierarchy because every field is always known and a caller invariably
 * wants two different keys at once: [laneId] to render the lane that is working, [taskIndex] to
 * file the result against the thing that was asked for. Those are the same key only in the
 * degenerate case where the pool is as wide as the work.
 *
 * [taskIndex] is the position in the list handed to [FramePool.run] rather than [Workflow.id],
 * because nothing stops a caller submitting the same workflow twice and everything about a queue
 * makes that reasonable.
 *
 * [laneId] is the lane of the task's most recent lease — a fan-out returns the lane and borrows
 * another afterwards, so it can change mid-task — and null only when the task failed before it was
 * ever given one.
 */
data class PoolEvent(
    val taskIndex: Int,
    val laneId: String?,
    val workflow: Workflow,
    val event: WorkflowEvent,
)
