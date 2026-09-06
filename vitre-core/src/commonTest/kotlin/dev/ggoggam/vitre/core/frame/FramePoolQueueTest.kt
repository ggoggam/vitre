package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The queue that makes a device-dependent lane count safe.
 *
 * The bug this exists to prevent is not hypothetical: the arrangement it replaces zipped tasks
 * against lanes by index, so a pool that came back narrower than the caller asked for dropped the
 * surplus work silently — no error, no event, just two shops that were never searched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FramePoolQueueTest {
    private fun workflow(id: String) = Workflow(id = id, name = id, steps = listOf(WorkflowStep.Navigate("https://$id.test/")))

    private fun pool(
        laneIds: List<String>,
        lanes: Map<String, WebViewController>,
    ) = FramePool(laneIds = laneIds, tap = null, lanes = lanes)

    @Test
    fun `cancelling a receiver during handoff returns its lane`() =
        runTest {
            val pool = pool(listOf("a"), mapOf("a" to FakeWebViewController()))
            val held = pool.acquire("first")
            val waiting = launch { pool.withLane("cancelled") { error("must not run") } }
            runCurrent()

            // The channel hands the lane to the suspended receiver, but it is cancelled before
            // its continuation runs. No successful acquire exists for withLane to release.
            pool.release(held)
            waiting.cancel()
            waiting.join()

            val recovered = withTimeoutOrNull(100) { pool.acquire("next") }
            assertNotNull(recovered, "the cancelled receiver permanently consumed the only lane")
            pool.release(recovered)
        }

    @Test
    fun `a cancelled handoff can pass its lane to another waiting borrower`() =
        runTest {
            val pool = pool(listOf("a"), mapOf("a" to FakeWebViewController()))
            val held = pool.acquire("first")
            val cancelled = launch { pool.withLane("cancelled") { error("must not run") } }
            var nextRan = false
            val next = launch { pool.withLane("next") { nextRan = true } }
            runCurrent()
            pool.release(held)
            cancelled.cancel()
            cancelled.join()
            runCurrent()
            assertTrue(nextRan, "the next queued borrower did not receive the recovered lane")
            next.join()

            val recovered = assertNotNull(withTimeoutOrNull(100) { pool.acquire("last") })
            assertEquals(null, withTimeoutOrNull(100) { pool.acquire("duplicate") })
            pool.release(recovered)
        }

    @Test
    fun `runs every workflow even when there are more of them than lanes`() =
        runTest {
            val lanes = listOf("a", "b").associateWith { FakeWebViewController() }
            val submitted = (1..5).map { workflow("task$it") }

            val events = pool(lanes.keys.toList(), lanes).run(submitted, EmptyCoroutineContext).toList()

            val completed = events.filter { it.event is WorkflowEvent.Completed }
            assertEquals(5, completed.size)
            assertEquals(setOf(0, 1, 2, 3, 4), completed.map { it.taskIndex }.toSet())
        }

    @Test
    fun `attributes each event to the lane that actually ran it`() =
        runTest {
            val lanes = listOf("a", "b").associateWith { FakeWebViewController() }

            val events = pool(lanes.keys.toList(), lanes).run(listOf(workflow("one"), workflow("two")), EmptyCoroutineContext).toList()

            for (event in events) {
                val navigated = lanes.getValue(checkNotNull(event.laneId) { "no lane on $event" }).navigations
                assertTrue(
                    navigated.any { it.contains(event.workflow.id) },
                    "lane ${event.laneId} was credited with ${event.workflow.id} but never navigated there",
                )
            }
        }

    @Test
    fun `holds a task in the queue until a lane frees up`() =
        runTest {
            // One lane, and the first task is held open. If the second task were dispatched anyway
            // the pool would be running two workflows against one WebView, which is precisely the
            // serialisation the lane count exists to impose.
            val release = CompletableDeferred<Unit>()
            val lane = FakeWebViewController()
            lane.onNavigate = { url -> if (url.contains("slow")) release.await() }

            val events = mutableListOf<Int>()
            val flow = pool(listOf("a"), mapOf("a" to lane)).run(listOf(workflow("slow"), workflow("quick")), EmptyCoroutineContext)

            val collector = launch { flow.collect { events += it.taskIndex } }
            runCurrent()
            assertTrue(events.none { it == 1 }, "the queued task started before the lane was free")

            release.complete(Unit)
            collector.join()
            assertTrue(events.any { it == 1 }, "the queued task never ran")
        }

    @Test
    fun `a failing workflow costs its own task and nothing else`() =
        runTest {
            val lane = FakeWebViewController()
            lane.onNavigate = { url ->
                if (url.contains("broken")) {
                    throw dev.ggoggam.vitre.core.webview
                        .PageLoadException("no route")
                }
            }

            val events =
                pool(listOf("a"), mapOf("a" to lane))
                    .run(listOf(workflow("broken"), workflow("fine")), EmptyCoroutineContext)
                    .toList()

            assertTrue(events.any { it.taskIndex == 0 && it.event is WorkflowEvent.Failed })
            assertTrue(events.any { it.taskIndex == 1 && it.event is WorkflowEvent.Completed })
        }

    @Test
    fun `blanks a lane before handing it the next task`() =
        runTest {
            // Lane reuse is the normal case once there is a queue, and a WaitFor that matches the
            // previous task's leftover DOM fails by succeeding.
            val lane = FakeWebViewController()

            pool(listOf("a"), mapOf("a" to lane)).run(listOf(workflow("first"), workflow("second")), EmptyCoroutineContext).toList()

            assertEquals(2, lane.loadedHtml.size)
            assertTrue(lane.loadedHtml.all { (html, _) -> html.contains("LANE A") })
        }

    @Test
    fun `an empty submission completes without touching a lane`() =
        runTest {
            val lane = FakeWebViewController()

            val events = pool(listOf("a"), mapOf("a" to lane)).run(emptyList(), EmptyCoroutineContext).toList()

            assertEquals(emptyList(), events)
            assertEquals(emptyList(), lane.loadedHtml)
        }
}
