package dev.ggoggam.vitre.core.workflow

sealed class WorkflowEvent {
    data class StepStarted(
        val path: StepPath,
        val step: WorkflowStep,
    ) : WorkflowEvent()

    data class StepCompleted(
        val path: StepPath,
    ) : WorkflowEvent()

    data class Completed(
        val variables: Map<String, String>,
    ) : WorkflowEvent()

    data class Failed(
        val path: StepPath,
        val message: String,
    ) : WorkflowEvent()

    /**
     * The engine took a lane from its `LaneSource`; everything that follows happened on it, until
     * the next [LaneLeased].
     *
     * The first event of every run, and again after every [WorkflowStep.ForEach], because a
     * fan-out gives the lane back and borrows afresh afterwards. It exists so that a caller
     * watching several workflows share a pool can say *which* WebView an event came from — the
     * only way to know is to be told at the moment of the lease, since by the time an event is
     * read the engine may have moved on. A host with one WebView sees `LaneSource.SOLO_LANE_ID`
     * and can ignore it.
     */
    data class LaneLeased(
        val laneId: String,
    ) : WorkflowEvent()

    /**
     * One event from one item of a [WorkflowStep.ForEach] — the step at [path].
     *
     * The wrapper is how item identity travels, since a body step's [StepPath] is the same for
     * every item that runs it. [index] is the item's position in the array it came from and
     * [count] how many are running in all, so a timeline can say "3 of 8" from any one of these.
     * [laneId] is the lane the item was on when [event] was emitted, or null for the rare event
     * that precedes its first lease — a lane that could not be made ready.
     *
     * [event] is anything the item's own run emitted: its [LaneLeased], its body's [StepStarted]
     * and [StepCompleted], and then exactly one of [Completed] — with the variables the body set —
     * or [Failed]. Items on different lanes interleave; within one item, events arrive in order.
     * The enclosing step's own [StepCompleted] comes after the last item's.
     */
    data class FanOutItem(
        val path: StepPath,
        val index: Int,
        val count: Int,
        val laneId: String?,
        val event: WorkflowEvent,
    ) : WorkflowEvent()
}
