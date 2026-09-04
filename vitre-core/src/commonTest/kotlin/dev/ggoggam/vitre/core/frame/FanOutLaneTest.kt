package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.FanOutResult
import dev.ggoggam.vitre.core.workflow.StepPath
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.decode
import dev.ggoggam.vitre.core.workflow.template
import dev.ggoggam.vitre.core.workflow.workflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a fan-out's items run when the engine has a pool to borrow from, and the one property
 * that makes any of it safe: a parent never holds a lane while its children wait for one.
 *
 * `ForEachStepTest` covers what the step does with its items; this file covers the lanes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FanOutLaneTest {
    private fun pool(lanes: Map<String, WebViewController>) = FramePool(laneIds = lanes.keys.toList(), tap = null, lanes = lanes)

    /** Four product URLs, from every "search", and a page's own URL from every extract. */
    private fun fake(itemsHeldBy: CompletableDeferred<Unit>? = null) =
        FakeWebViewController().apply {
            nextEvalResult = { script ->
                when {
                    script.startsWith("(function(){return") -> {
                        (1..4).joinToString(",", prefix = "[", postfix = "]") { "{\"url\":\"https://shop.test/p/$it\"}" }
                    }

                    else -> {
                        "\"${navigations.lastOrNull() ?: ""}\""
                    }
                }
            }
            onNavigate = { url -> if (itemsHeldBy != null && url.contains("/p/")) itemsHeldBy.await() }
        }

    private fun searchThenVisit(id: String = "wf") =
        workflow(id, "search then visit") {
            navigate("https://shop.test/search?$id")
            extractRows(rows = css("li"), into = "results") { column("url", css("a")) }
            forEach(over = "results", item = "product", into = "details") {
                navigate(template("{product.url}"))
                extract("#price", into = "price")
            }
            extract("#done", into = "after")
        }

    @Test
    fun items_spread_across_every_lane_the_pool_has() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val lanes = listOf("a", "b").associateWith { fake(itemsHeldBy = release) }

            val events = mutableListOf<WorkflowEvent>()
            val run = launch { WorkflowEngine(pool(lanes), EmptyCoroutineContext).run(searchThenVisit()).toList(events) }
            runCurrent()

            // With every product page held open, both lanes should be sitting on one — the parent
            // searched on one lane, gave it up, and the first two items took both.
            val inFlight = lanes.mapValues { (_, lane) -> lane.navigations.lastOrNull() }
            assertTrue(inFlight.values.all { it?.contains("/p/") == true }, "both lanes should be on a product page: $inFlight")

            release.complete(Unit)
            run.join()

            assertIs<WorkflowEvent.Completed>(events.last())
            val items = events.filterIsInstance<WorkflowEvent.FanOutItem>()
            assertEquals(setOf("a", "b"), items.mapNotNull { it.laneId }.toSet())
            assertEquals(4, items.count { it.event is WorkflowEvent.Completed })
        }

    /**
     * The deadlock argument, run: a pool of one lane, and a workflow whose children need that
     * lane. If the parent kept it while waiting, nothing would ever finish.
     */
    @Test
    fun a_pool_of_one_lane_still_finishes_a_fan_out() =
        runTest {
            val lane = fake()

            val events = WorkflowEngine(pool(mapOf("a" to lane)), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(4, completed.decode<List<FanOutResult>>("details").size)
            assertEquals(5, lane.navigations.size)
        }

    /** N parents on N lanes, each about to fan out — the case a bigger pool only postpones. */
    @Test
    fun several_fanning_out_workflows_share_a_pool_without_starving_each_other() =
        runTest {
            val lanes = listOf("a", "b").associateWith { fake() }
            val workflows = listOf(searchThenVisit("one"), searchThenVisit("two"))

            val events = pool(lanes).run(workflows, EmptyCoroutineContext).toList()

            val completed = events.filter { it.event is WorkflowEvent.Completed }.map { it.taskIndex }.toSet()
            assertEquals(setOf(0, 1), completed)
            // Eight product pages in all, spread over the two lanes.
            assertEquals(8, lanes.values.sumOf { lane -> lane.navigations.count { it.contains("/p/") } })
        }

    @Test
    fun every_lease_starts_on_a_blank_lane_painted_with_what_it_is_for() =
        runTest {
            val lane = fake()

            WorkflowEngine(pool(mapOf("a" to lane)), EmptyCoroutineContext).run(searchThenVisit()).toList()

            // The run's own lease, one per item, and one for the segment after the barrier.
            assertEquals(6, lane.loadedHtml.size)
            assertTrue(lane.loadedHtml.all { (html, _) -> html.contains("LANE A") })
            assertTrue(lane.loadedHtml[0].first.contains("search then visit"))
            assertTrue(lane.loadedHtml[1].first.contains("product 1/4"), lane.loadedHtml[1].first)
        }

    /** The barrier, seen from the step after it: a blank page, and the variables intact. */
    @Test
    fun the_step_after_a_fan_out_runs_on_a_fresh_lane_with_the_variables_intact() =
        runTest {
            val lane = fake()

            val events = WorkflowEngine(pool(mapOf("a" to lane)), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            // The extract after the fan-out answered with the page it was on: the last product
            // page's URL is what the fake reports, but the lane was blanked in between —
            // the blank is the last load, after that navigation.
            assertEquals(setOf("results", "details", "after"), completed.variables.keys)
            val lastNavigation = lane.navigations.last()
            assertTrue(lastNavigation.contains("/p/4"), lastNavigation)
            // The run's own lease and the one after the barrier; the items' are wrapped.
            assertEquals(2, events.count { it is WorkflowEvent.LaneLeased })
        }

    @Test
    fun pool_events_name_the_lane_each_segment_ran_on() =
        runTest {
            val lanes = listOf("a", "b").associateWith { fake() }

            val events = pool(lanes).run(listOf(searchThenVisit()), EmptyCoroutineContext).toList()

            var current: String? = null
            for (poolEvent in events) {
                val event = poolEvent.event
                if (event is WorkflowEvent.LaneLeased) current = event.laneId
                assertEquals(current, poolEvent.laneId, "at $event")
            }
            // Two leases at the top level; the second may land on either lane.
            assertEquals(2, events.count { it.event is WorkflowEvent.LaneLeased })
        }

    @Test
    fun a_lane_that_cannot_be_blanked_fails_that_task_and_goes_back_for_the_next() =
        runTest {
            var loads = 0
            val lane =
                FakeWebViewController().apply {
                    onNavigate = { url ->
                        if (url == "about:blank" && loads++ == 0) throw IllegalStateException("wedged")
                    }
                }
            val plain =
                Workflow(
                    id = "plain",
                    name = "plain",
                    steps =
                        listOf(
                            dev.ggoggam.vitre.core.workflow.WorkflowStep
                                .Navigate("https://x.test/"),
                        ),
                )

            val events = pool(mapOf("a" to lane)).run(listOf(plain, plain), EmptyCoroutineContext).toList()

            val first = events.single { it.taskIndex == 0 }
            val failed = assertIs<WorkflowEvent.Failed>(first.event)
            assertEquals(StepPath.root(0), failed.path)
            assertEquals("wedged", failed.message)
            assertNull(first.laneId, "a task that never got a lane has no lane to name")
            assertTrue(events.any { it.taskIndex == 1 && it.event is WorkflowEvent.Completed })
        }

    @Test
    fun a_source_of_one_controller_runs_items_in_sequence() =
        runTest {
            val lane = fake()

            val events = WorkflowEngine(LaneSource.of(lane), EmptyCoroutineContext).run(searchThenVisit()).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            val leases = events.filterIsInstance<WorkflowEvent.FanOutItem>().filter { it.event is WorkflowEvent.LaneLeased }
            assertEquals(listOf(0, 1, 2, 3), leases.map { it.index })
            // No blanking on a bare controller: the host is looking at it.
            assertTrue(lane.loadedHtml.isEmpty())
        }
}
