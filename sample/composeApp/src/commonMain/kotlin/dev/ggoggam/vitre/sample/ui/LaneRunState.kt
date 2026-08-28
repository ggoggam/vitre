package dev.ggoggam.vitre.sample.ui

import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.sample.data.LaneSite
import dev.ggoggam.vitre.sample.data.Offer

/**
 * One lane's run, folded out of its engine events the same way [RunState] folds a single workflow's.
 *
 * Per lane rather than per scenario because that is how the failures come: three shops answer and
 * the fourth times out, and a scenario-level status that collapsed to "failed" would throw away the
 * three results anybody actually wanted.
 */
data class LaneRun(
    /**
     * Position in the list handed to `FramePool.run`, and the only stable identity a run has.
     *
     * Not [laneId]: the pool queues, so a pool narrower than the work runs several tasks on one lane
     * and the same id comes back two or three times in a single scenario. Keying a list on it crashed
     * the Lanes tab outright — on any low-RAM or sub-2GB device, where `forDevice` hands back two
     * lanes for the sample's four sites.
     */
    val taskIndex: Int,
    val laneId: String,
    val site: LaneSite,
    val workflow: Workflow,
    val events: List<WorkflowEvent>,
    val elapsedMs: Long?,
) {
    val state: RunState = runStateOf(workflow, events)

    /** Empty until the lane completes: a half-scraped page yields half a price list. */
    val offers: List<Offer> =
        if (state.status == RunStatus.Completed) site.offersFrom(state.variables) else emptyList()

    val note: String? = site.notesFrom(state.variables)
}

/** How the scenario as a whole is doing, for the app bar. */
fun List<LaneRun>.overallStatus(running: Boolean): RunStatus =
    when {
        isEmpty() -> RunStatus.Idle
        running || any { it.state.status == RunStatus.Running } -> RunStatus.Running
        all { it.state.status == RunStatus.Completed } -> RunStatus.Completed
        any { it.state.status == RunStatus.Completed } -> RunStatus.Completed
        else -> RunStatus.Failed
    }

/** One product with every shop's quote for it, cheapest delivered first. */
data class ProductComparison(
    val title: String,
    val quotes: List<Offer>,
) {
    val best: Offer? = quotes.firstOrNull { it.totalCents != null }

    /** What choosing the winner saves over the worst quote, or null if nothing to compare. */
    val spreadCents: Int?
        get() {
            val totals = quotes.mapNotNull { it.totalCents }
            return if (totals.size < 2) null else totals.max() - totals.min()
        }
}

/**
 * Every lane's offers, grouped by product and ranked within each group.
 *
 * Grouped rather than one flat list because that is the question being asked: not "what is the
 * cheapest thing any shop sells" but "for this item, who is cheapest". Ranking is on the delivered
 * total, not the sticker price — the fixture shops disagree about which of those is lower for most
 * of the catalogue, which is the single most common way a price comparison is wrong.
 */
fun List<LaneRun>.compareByProduct(): List<ProductComparison> =
    flatMap { it.offers }
        .groupBy { it.title.trim() }
        .map { (title, quotes) ->
            ProductComparison(
                title = title,
                quotes = quotes.sortedWith(compareBy({ it.totalCents ?: Int.MAX_VALUE }, { it.shop })),
            )
        }.sortedBy { it.best?.totalCents ?: Int.MAX_VALUE }
