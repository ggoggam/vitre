package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.workflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PoolLifecycleTest {
    private fun pool(vararg pages: FakeWebViewController) =
        FramePool(pages.indices.map(Int::toString), null, pages.mapIndexed { i, page -> i.toString() to page }.toMap())

    @Test
    fun close_wakes_waiters_and_rejects_new_borrowers_but_allows_release() =
        runTest {
            val pool = pool(FakeWebViewController())
            val held = pool.acquire("held")
            val waiter = async { runCatching { pool.acquire("waiter") }.exceptionOrNull() }
            runCurrent()
            pool.close()
            assertIs<PoolUnavailableException>(waiter.await())
            assertFailsWith<PoolUnavailableException> { pool.acquire("late") }
            pool.release(held)
            pool.close()
            assertTrue(pool.state.value.closed)
            assertTrue(
                pool.state.value.leasedLaneIds
                    .isEmpty(),
            )
        }

    @Test
    fun unhealthy_lane_does_not_poison_later_workflows() =
        runTest {
            val broken = FakeWebViewController().apply { close() }
            val healthy = FakeWebViewController()
            val pool = pool(broken, healthy)
            val tasks = (1..10).map { workflow("$it", "$it") { navigate("https://example.test/$it") } }
            val events = pool.run(tasks, EmptyCoroutineContext).toList()
            assertEquals(1, events.count { it.event is WorkflowEvent.Failed })
            assertEquals(9, events.count { it.event is WorkflowEvent.Completed })
            assertEquals(setOf("0"), pool.state.value.unavailableLaneIds)
        }

    @Test
    fun cancellation_while_blanking_preserves_lane_health() =
        runTest {
            val page = FakeWebViewController().apply { onNavigate = { awaitCancellation() } }
            val pool = pool(page)
            val borrower = launch { pool.acquire("cancelled") }
            runCurrent()
            borrower.cancel()
            borrower.join()
            assertTrue(
                pool.state.value.unavailableLaneIds
                    .isEmpty(),
            )
            page.onNavigate = { }
            pool.release(pool.acquire("recovered"))
        }

    @Test
    fun cancellation_during_partial_reset_returns_all_reserved_lanes() =
        runTest {
            val second = FakeWebViewController().apply { onNavigate = { awaitCancellation() } }
            val pool = pool(FakeWebViewController(), second)
            val reset = launch { pool.resetAll() }
            runCurrent()
            assertEquals(2, pool.state.value.leasedLaneIds.size)
            reset.cancel()
            reset.join()
            second.onNavigate = { }
            val one = pool.acquire("one")
            val two = pool.acquire("two")
            assertEquals(setOf("0", "1"), setOf(one.id, two.id))
            pool.release(one)
            pool.release(two)
        }

    @Test
    fun losing_the_last_lane_wakes_a_waiting_borrower() =
        runTest {
            val fail = CompletableDeferred<Unit>()
            val page =
                FakeWebViewController().apply {
                    onNavigate = {
                        fail.await()
                        error("unhealthy")
                    }
                }
            val pool = pool(page)
            val failing = async { runCatching { pool.acquire("failing") }.exceptionOrNull() }
            runCurrent()
            val waiter = async { runCatching { pool.acquire("waiting") }.exceptionOrNull() }
            runCurrent()
            fail.complete(Unit)
            assertEquals("unhealthy", failing.await()?.message)
            assertIs<PoolUnavailableException>(waiter.await())
        }

    @Test
    fun close_interrupts_reset_and_borrowers_waiting_behind_it() =
        runTest {
            val pool = pool(FakeWebViewController())
            val held = pool.acquire("held")
            val reset = async { runCatching { pool.resetAll() }.exceptionOrNull() }
            runCurrent()
            val waiter = async { runCatching { pool.acquire("waiting") }.exceptionOrNull() }
            runCurrent()
            pool.close()
            assertIs<PoolUnavailableException>(reset.await())
            assertIs<PoolUnavailableException>(waiter.await())
            pool.release(held)
        }

    @Test
    fun concurrent_resets_wait_for_borrowers_and_do_not_deadlock() =
        runTest {
            val first = FakeWebViewController()
            val second = FakeWebViewController()
            val pool = pool(first, second)
            val held = pool.acquire("working")
            val one = launch { pool.resetAll("one") }
            val two = launch { pool.resetAll("two") }
            runCurrent()
            assertEquals(1, first.loadedHtml.size, "reset replaced an active borrower's page")
            pool.release(held)
            one.join()
            two.join()
            assertEquals(3, first.loadedHtml.size)
            assertEquals(2, second.loadedHtml.size)
        }

    @Test
    fun a_large_submission_does_not_allocate_a_coroutine_per_queued_workflow() =
        runTest {
            val page = FakeWebViewController().apply { onNavigate = { awaitCancellation() } }
            val pool = pool(page)
            val task = workflow("pending", "pending") { navigate("https://example.test") }
            val collector = launch { pool.run(List(10_000) { task }, EmptyCoroutineContext).collect { } }
            runCurrent()

            fun descendants(job: kotlinx.coroutines.Job): Int = job.children.sumOf { 1 + descendants(it) }
            assertTrue(descendants(collector) < 100, "pending workflows each retained a coroutine")
            collector.cancel()
            collector.join()
            assertTrue(
                pool.state.value.leasedLaneIds
                    .isEmpty(),
            )
        }

    @Test
    fun a_large_fan_out_only_admits_a_bounded_number_of_children() =
        runTest {
            val page = FakeWebViewController().apply { nextEvalResult = { (1..10_000).joinToString(",", "[", "]") } }
            var acquisitions = 0
            val source =
                object : LaneSource {
                    override val parallelism = 2

                    override suspend fun acquire(label: String): Lane {
                        acquisitions++
                        if (acquisitions > 1) awaitCancellation()
                        return Lane("a", page)
                    }

                    override fun release(lane: Lane) = Unit
                }
            val task =
                workflow("fan", "fan") {
                    evaluateJs("items", into = "items")
                    forEach("items", "item", "results", limit = 10_000) { navigate("https://example.test") }
                }
            val running = launch { WorkflowEngine(source, EmptyCoroutineContext).run(task).collect { } }
            runCurrent()
            assertEquals(3, acquisitions, "fan-out admitted more children than useful parallelism")
            running.cancel()
            running.join()
        }

    @Test
    fun nested_fan_out_progresses_when_every_extra_worker_is_busy() =
        runTest {
            val pages = List(2) { FakeWebViewController().apply { nextEvalResult = { "[1,2,3]" } } }
            val pool = pool(*pages.toTypedArray())
            val task =
                workflow("nested", "nested") {
                    evaluateJs("items", into = "items")
                    forEach("items", "outer", "outerResults") {
                        forEach("items", "inner", "innerResults") { navigate("https://example.test") }
                    }
                }
            val result = WorkflowEngine(pool, EmptyCoroutineContext).run(task).toList()
            assertIs<WorkflowEvent.Completed>(result.last())
            assertEquals(9, pages.sumOf { it.navigations.size })
            assertTrue(
                pool.state.value.leasedLaneIds
                    .isEmpty(),
            )
        }
}
