package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.sync.Mutex

/** One lane as the engine sees it: a WebView, and the name whoever handed it out knows it by. */
data class Lane(
    val id: String,
    val controller: WebViewController,
)

/**
 * Where a `WorkflowEngine` gets the WebView it drives — one lane at a time, for as long as it needs
 * one and no longer.
 *
 * The engine used to be handed a controller and keep it for the whole run, and that was the right
 * shape while a workflow was a straight line: one page, one WebView, start to finish. A
 * [dev.ggoggam.vitre.core.workflow.WorkflowStep.ForEach] breaks it. Its body wants to visit twenty
 * product pages, ideally several at once, and a workflow that held its own lane while waiting for
 * twenty children to find lanes of their own would be holding the one thing they are waiting for.
 * With N such workflows on an N-lane pool, every lane is held by a parent and no child can ever
 * start — a deadlock that a bigger pool only postpones.
 *
 * So the engine *borrows*. It takes a lane when a step needs a page, keeps it across the steps that
 * follow, and gives it back at a fan-out — before any child asks for one. After the children finish
 * it borrows again, which is why **a fan-out is a page barrier**: the variables a workflow extracted
 * survive it, the document it was looking at does not. That is the whole cost, and it is what makes
 * starvation impossible at any pool size from one upwards.
 *
 * Two implementations ship. A [FramePool] is one, handing out its lanes in the order they are asked
 * for and blanking each before it goes out. [of] wraps a single controller, for the host with one
 * WebView: everything runs on it, one segment at a time, and a fan-out simply runs its items in
 * sequence.
 *
 * [acquire] suspends until a lane is free; [release] does not suspend, so it can be called from a
 * `finally` block on the way out of a cancelled run. What the engine does between the two is its
 * business; what an implementation must guarantee is that a lane is with one borrower at a time.
 */
interface LaneSource {
    /**
     * Hands out a lane, suspending until one is free.
     *
     * [label] is what the lane is about to be used for — the workflow's name — for an
     * implementation that shows its lanes on screen. A pool paints it on the lane while the page
     * is on its way; a single controller has nowhere to put it and ignores it.
     *
     * @throws Exception if the lane could not be made ready, in which case it is *not* handed out
     *   and the caller has nothing to release.
     */
    suspend fun acquire(label: String): Lane

    /** Hands [lane] back. Must be called exactly once per successful [acquire]. */
    fun release(lane: Lane)

    companion object {
        /** The id of the one lane a [of] source has. */
        const val SOLO_LANE_ID: String = "solo"

        /**
         * A source with exactly one lane: [controller], as it is.
         *
         * No blanking between borrowers, which is deliberate. A host that hands the engine its one
         * WebView is usually looking at it, and an agent driving the same WebView one step at a
         * time (see `vitre-agent`) is counting on the page still being there for the next step.
         * The one thing this does impose is order: a second borrower waits for the first to give
         * the lane back, which is what lets a fan-out run its items one after another on a single
         * WebView instead of on top of each other.
         *
         * Each call is an independent source with its own queue. Two engines built over the same
         * controller through two calls do not wait for each other — they interleave, exactly as
         * two engines over one controller always have — so nothing that used to work stops.
         */
        fun of(
            controller: WebViewController,
            laneId: String = SOLO_LANE_ID,
        ): LaneSource = SingleLaneSource(Lane(laneId, controller))
    }
}

/** [LaneSource.acquire], [block], [LaneSource.release] — however [block] returns. */
suspend fun <T> LaneSource.withLane(
    label: String,
    block: suspend (Lane) -> T,
): T {
    val lane = acquire(label)
    try {
        return block(lane)
    } finally {
        release(lane)
    }
}

/**
 * [LaneSource.of]. A fair mutex: borrowers queue in the order they asked, so a fan-out's items run
 * in index order rather than whichever order the scheduler happened to resume them in.
 */
private class SingleLaneSource(
    private val lane: Lane,
) : LaneSource {
    private val mutex = Mutex()

    override suspend fun acquire(label: String): Lane {
        mutex.lock()
        return lane
    }

    override fun release(lane: Lane) {
        require(lane === this.lane) { "lane ${lane.id} was not borrowed from this source" }
        mutex.unlock()
    }
}
