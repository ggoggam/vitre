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
import kotlin.coroutines.cancellation.CancellationException

/**
 * A bounded set of independently drivable lanes, and a queue that feeds them.
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
 * The work really is concurrent. A lane owns its WebView outright, so an operation is serialised
 * only against other callers of *that* lane; nothing a lane does waits on its neighbours. See
 * `docs/PARALLEL-LANES.md`.
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
) {
    /** @throws IllegalArgumentException if [id] is not one of [laneIds]. */
    fun lane(id: String): WebViewController =
        lanes[id] ?: throw IllegalArgumentException("no such lane: $id (have ${laneIds.joinToString()})")

    val allLanes: List<WebViewController> get() = laneIds.map { lanes.getValue(it) }

    /** How many workflows may be in flight at once. Decided by the device, not by the caller. */
    val laneCount: Int get() = laneIds.size

    /**
     * Runs every workflow in [workflows], at most [laneCount] at a time, and reports as it goes.
     *
     * Emissions from different lanes interleave — there is no ordering between tasks and there
     * cannot be, since that is the whole point. Within one task, events arrive in the order
     * `WorkflowEngine` produced them, ending in [WorkflowEvent.Completed] or
     * [WorkflowEvent.Failed]. A workflow that fails costs its own task and nothing else: the lane
     * takes the next one off the queue.
     *
     * Each task starts on a blank lane. Under the old one-lane-per-site arrangement resetting was
     * a between-runs nicety; with a queue, lane reuse is the normal case, and a `WaitFor` matching
     * the *previous* task's leftover DOM is a failure that looks exactly like success.
     *
     * The returned flow completes when the queue is drained. Cancelling the collector cancels the
     * lanes mid-step.
     */
    fun run(
        workflows: List<Workflow>,
        context: CoroutineContext = Dispatchers.Default,
    ): Flow<PoolEvent> =
        channelFlow {
            // Filled and closed before any lane starts, so `for (task in queue)` terminates on its
            // own and no lane can outlive the work. An unlimited buffer because the queue holds
            // descriptions of work rather than the work itself.
            val queue = Channel<IndexedValue<Workflow>>(Channel.UNLIMITED)
            workflows.forEachIndexed { index, workflow -> queue.trySend(IndexedValue(index, workflow)) }
            queue.close()

            for (laneId in laneIds) {
                launch {
                    val controller = lanes.getValue(laneId)
                    for ((index, workflow) in queue) {
                        try {
                            controller.loadHtml(placeholderHtml(laneId, workflow.name))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (t: Throwable) {
                            // Blanking the lane is the pool's own step, not the workflow's, and a
                            // failure here (a wedged lane's PageLoadException, a closed controller's
                            // IllegalStateException) must cost this one task and nothing else — the
                            // same contract the engine keeps for a step that throws. Letting it
                            // escape the launch would cancel every other lane's in-flight work.
                            send(PoolEvent(index, laneId, workflow, WorkflowEvent.Failed(0, t.message ?: "lane reset failed")))
                            continue
                        }
                        WorkflowEngine(controller, context).run(workflow).collect { event ->
                            send(PoolEvent(index, laneId, workflow, event))
                        }
                    }
                }
            }
        }

    /**
     * Blanks every lane, concurrently, and waits for all of them.
     *
     * Between runs rather than before one: a lane still showing the previous run's results looks
     * exactly like a lane that has already finished the current one, and that ambiguity has cost
     * more debugging time than it sounds like it should.
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
 * One workflow's progress, and which lane it landed on.
 *
 * Flat rather than a sealed hierarchy because every field is always known and a caller invariably
 * wants two different keys at once: [laneId] to render the lane that is working, [taskIndex] to
 * file the result against the thing that was asked for. Those are the same key only in the
 * degenerate case where the pool is as wide as the work.
 *
 * [taskIndex] is the position in the list handed to [FramePool.run] rather than [Workflow.id],
 * because nothing stops a caller submitting the same workflow twice and everything about a queue
 * makes that reasonable.
 */
data class PoolEvent(
    val taskIndex: Int,
    val laneId: String,
    val workflow: Workflow,
    val event: WorkflowEvent,
)
