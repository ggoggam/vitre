package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.net.NetworkTap
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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
    /**
     * The lanes nobody is on. A channel rather than a semaphore plus a free list because a channel
     * is both at once, and hands lanes to waiting receivers in the order they arrived.
     */
    private val free =
        Channel<Lane>(Channel.UNLIMITED).apply {
            for (id in laneIds) trySend(Lane(id, lanes.getValue(id)))
        }

    /** @throws IllegalArgumentException if [id] is not one of [laneIds]. */
    fun lane(id: String): WebViewController =
        lanes[id] ?: throw IllegalArgumentException("no such lane: $id (have ${laneIds.joinToString()})")

    val allLanes: List<WebViewController> get() = laneIds.map { lanes.getValue(it) }

    /** How many workflows may be in flight at once. Decided by the device, not by the caller. */
    val laneCount: Int get() = laneIds.size

    /**
     * The next free lane, blanked, with [label] painted on it while its page is on the way.
     *
     * Every lease starts on a blank lane, not only the first of a task. Under the old
     * one-lane-per-site arrangement resetting was a between-runs nicety; with a queue, lane reuse
     * is the normal case, and a `WaitFor` matching the *previous* borrower's leftover DOM is a
     * failure that looks exactly like success. A lane that cannot be blanked — wedged, or closed
     * under us — is put back for the next borrower to discover and the failure goes to this one,
     * so it costs one task and not the pool.
     */
    override suspend fun acquire(label: String): Lane {
        val lane = free.receive()
        try {
            lane.controller.loadHtml(placeholderHtml(lane.id, label))
        } catch (t: Throwable) {
            free.trySend(lane)
            throw t
        }
        return lane
    }

    override fun release(lane: Lane) {
        require(lanes[lane.id] === lane.controller) { "lane ${lane.id} does not belong to this pool" }
        free.trySend(lane)
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
     * Every workflow is submitted at once and borrows its lanes from this pool as it goes, so a
     * task past the lane count sits in the lane queue rather than in a task queue — the visible
     * difference being that a fan-out in a running task can use lanes ahead of a task that has not
     * started. [PoolEvent.laneId] follows the borrows: it is the lane of the most recent lease in
     * that task, and null only for the event of a task that failed before its first lease.
     *
     * The returned flow completes when the queue is drained. Cancelling the collector cancels the
     * lanes mid-step.
     */
    fun run(
        workflows: List<Workflow>,
        context: CoroutineContext = Dispatchers.Default,
    ): Flow<PoolEvent> =
        channelFlow {
            workflows.forEachIndexed { index, workflow ->
                launch {
                    var laneId: String? = null
                    WorkflowEngine(this@FramePool, context).run(workflow).collect { event ->
                        if (event is WorkflowEvent.LaneLeased) laneId = event.laneId
                        send(PoolEvent(index, laneId, workflow, event))
                    }
                }
            }
        }

    /**
     * Blanks every lane, concurrently, and waits for all of them.
     *
     * Between runs rather than before one: a lane still showing the previous run's results looks
     * exactly like a lane that has already finished the current one, and that ambiguity has cost
     * more debugging time than it sounds like it should. Meant for a pool with nothing borrowed;
     * a lane on loan at the time is blanked under its borrower.
     */
    suspend fun resetAll(label: String = "idle") {
        coroutineScope {
            laneIds.map { id -> async { lanes.getValue(id).loadHtml(placeholderHtml(id, label)) } }.awaitAll()
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
