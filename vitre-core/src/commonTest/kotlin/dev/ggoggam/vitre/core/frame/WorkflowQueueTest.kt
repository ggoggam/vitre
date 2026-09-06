package dev.ggoggam.vitre.core.frame

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.PageLoadException
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowFailureKind
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.handle
import dev.ggoggam.vitre.core.workflow.workflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowQueueTest {
    private fun workflow(id: String) = Workflow(id, id, listOf(WorkflowStep.Navigate("https://$id.test")))

    private fun pool(lane: FakeWebViewController) = FramePool(listOf("a"), null, mapOf("a" to lane))

    @Test
    fun `queued jobs preserve known rejection and ambiguous action outcomes`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var answer = "null"
            val lane =
                FakeWebViewController().apply {
                    onNavigate = { if (it.contains("slow")) release.await() }
                    nextEvalResult = { answer }
                }
            val queue = WorkflowQueue(this, pool(lane), context = EmptyCoroutineContext)
            try {
                val first = queue.submit(workflow("slow"))
                val task = workflow("click", "click") { click(handle("ref")) }
                val waiting = queue.submit(task)
                runCurrent()
                assertIs<WorkflowJobState.Queued>(waiting.state.value)
                release.complete(Unit)
                assertIs<WorkflowJobState.Completed>(first.await())
                assertEquals(WorkflowFailureKind.OutcomeUnknown, assertIs<WorkflowJobState.Failed>(waiting.await()).kind)

                answer = """{"status":"detached","handle":"ref"}"""
                val rejected = queue.submit(task)
                assertEquals(WorkflowFailureKind.ActionRejected, assertIs<WorkflowJobState.Failed>(rejected.await()).kind)
                assertEquals(2, lane.evaluatedScripts.size, "neither job should replay its click")
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `existing job failure constructor arguments keep their order and default kind`() {
        val cause = IllegalStateException("failure")
        val failure = WorkflowJobState.Failed("failure", null, cause)
        assertEquals(cause, failure.cause)
        assertEquals(WorkflowFailureKind.Failure, failure.kind)
    }

    @Test
    fun `multiple observers and reconnecting do not execute a submission again`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val lane = FakeWebViewController().apply { onNavigate = { if (it.contains("slow")) release.await() } }
            val queue = WorkflowQueue(this, pool(lane), context = EmptyCoroutineContext)
            try {
                val job = queue.submit(workflow("slow"))
                val observer = launch { job.state.collect {} }
                val first = async { job.await() }
                val second = async { job.await() }
                runCurrent()
                observer.cancel()
                assertIs<WorkflowJobState.Running>(job.state.value)
                release.complete(Unit)
                assertIs<WorkflowJobState.Completed>(first.await())
                assertEquals(first.await(), second.await())
                assertEquals(first.await(), job.await())
                assertEquals(listOf("https://slow.test"), lane.navigations)
                val another = queue.submit(workflow("slow"))
                assertNotEquals(job.id, another.id)
                assertIs<WorkflowJobState.Completed>(another.await())
                assertEquals(2, lane.navigations.size)
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `cancelling queued work frees capacity and never touches the browser`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val lane = FakeWebViewController().apply { onNavigate = { if (it.contains("slow")) release.await() } }
            val queue = WorkflowQueue(this, pool(lane), capacity = 2, context = EmptyCoroutineContext)
            try {
                val active = queue.submit(workflow("slow"))
                val waiting = queue.submit(workflow("cancelled"))
                runCurrent()
                assertIs<WorkflowJobState.Queued>(waiting.state.value)
                assertFailsWith<WorkflowQueueFullException> { queue.submit(workflow("rejected")) }
                waiting.cancel()
                assertIs<WorkflowJobState.Cancelled>(waiting.await())
                val replacement = queue.submit(workflow("replacement"))
                release.complete(Unit)
                assertIs<WorkflowJobState.Completed>(active.await())
                assertIs<WorkflowJobState.Completed>(replacement.await())
                assertEquals(listOf("https://slow.test", "https://replacement.test"), lane.navigations)
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `deadline expires while queued without starting the workflow`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val lane = FakeWebViewController().apply { onNavigate = { if (it.contains("slow")) release.await() } }
            val queue = WorkflowQueue(this, pool(lane), context = EmptyCoroutineContext)
            try {
                queue.submit(workflow("slow"))
                val waiting = queue.submit(workflow("expired"), timeoutMs = 100)
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(WorkflowJobState.Cancelled(WorkflowCancellationReason.DEADLINE_EXCEEDED), waiting.await())
                assertEquals(listOf("https://slow.test"), lane.navigations)
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `running deadline includes queued time and publishes cancellation after cleanup`() =
        runTest {
            val releaseFirst = CompletableDeferred<Unit>()
            var cleanedUp = false
            val lane =
                FakeWebViewController().apply {
                    onNavigate = { url ->
                        if (url.contains("first")) releaseFirst.await()
                        if (url.contains("second")) {
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                withContext(NonCancellable) {
                                    delay(50)
                                    cleanedUp = true
                                }
                            }
                        }
                    }
                }
            val queue = WorkflowQueue(this, pool(lane), capacity = 2, context = EmptyCoroutineContext)
            try {
                queue.submit(workflow("first"))
                val second = queue.submit(workflow("second"), timeoutMs = 100)
                runCurrent()
                advanceTimeBy(60)
                releaseFirst.complete(Unit)
                runCurrent()
                assertIs<WorkflowJobState.Running>(second.state.value)
                advanceTimeBy(40)
                runCurrent()
                assertFalse(second.state.value.isTerminal)
                assertFalse(cleanedUp)
                advanceTimeBy(50)
                runCurrent()
                assertEquals(WorkflowJobState.Cancelled(WorkflowCancellationReason.DEADLINE_EXCEEDED), second.await())
                assertTrue(cleanedUp)
                assertIs<WorkflowJobState.Completed>(queue.submit(workflow("next")).await())
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `close cancels queued and running jobs rejects submission and preserves the pool`() =
        runTest {
            val lane = FakeWebViewController().apply { onNavigate = { if (it.contains("slow")) CompletableDeferred<Unit>().await() } }
            val queue = WorkflowQueue(this, pool(lane), context = EmptyCoroutineContext)
            val active = queue.submit(workflow("slow"))
            val waiting = queue.submit(workflow("waiting"))
            runCurrent()
            queue.closeAndJoin()
            queue.close()
            assertEquals(WorkflowJobState.Cancelled(WorkflowCancellationReason.QUEUE_CLOSED), active.await())
            assertEquals(active.await(), waiting.await())
            assertFailsWith<WorkflowQueueClosedException> { queue.submit(workflow("late")) }
            assertFalse(lane.closed)
            assertEquals(listOf("https://slow.test"), lane.navigations)
        }

    @Test
    fun `host cancellation before dispatch resolves admitted job handles`() =
        runTest {
            val host = CoroutineScope(coroutineContext + SupervisorJob())
            val lane = FakeWebViewController()
            val queue = WorkflowQueue(host, pool(lane), context = EmptyCoroutineContext)
            val job = queue.submit(workflow("unstarted"), timeoutMs = 100)
            host.cancel()
            assertIs<WorkflowJobState.Cancelled>(job.await())
            assertTrue(lane.navigations.isEmpty())
            assertFailsWith<WorkflowQueueClosedException> { queue.submit(workflow("late")) }
            queue.closeAndJoin()
        }

    @Test
    fun `a failing job retains its failure and does not cancel the next job`() =
        runTest {
            val lane = FakeWebViewController().apply { onNavigate = { if (it.contains("broken")) throw PageLoadException("no route") } }
            val queue = WorkflowQueue(this, pool(lane), context = EmptyCoroutineContext)
            try {
                val failed = queue.submit(workflow("broken"))
                val good = queue.submit(workflow("fine"))
                assertTrue(assertIs<WorkflowJobState.Failed>(failed.await()).message.contains("no route"))
                assertIs<WorkflowJobState.Completed>(good.await())
                assertIs<WorkflowJobState.Failed>(failed.state.value)
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `a fanout finishes with one top level permit and one browser lane`() =
        runTest {
            val lane = FakeWebViewController().apply { nextEvalResult = { "[{\"url\":\"https://item.test\"}]" } }
            val queue = WorkflowQueue(this, pool(lane), capacity = 1, parallelism = 1, context = EmptyCoroutineContext)
            try {
                val job =
                    queue.submit(
                        workflow("fanout", "fanout") {
                            evaluateJs("items", into = "items")
                            forEach(over = "items", item = "item", into = "results") {
                                navigate("https://child.test")
                            }
                        },
                    )
                assertIs<WorkflowJobState.Completed>(job.await())
                assertEquals(listOf("https://child.test"), lane.navigations)
            } finally {
                queue.closeAndJoin()
            }
        }

    @Test
    fun `closing the pool settles lane waiters and jobs behind them`() =
        runTest {
            val lane = FakeWebViewController()
            val pool = pool(lane)
            val externalLease = pool.acquire("external owner")
            val queue = WorkflowQueue(this, pool, parallelism = 1, context = EmptyCoroutineContext)
            try {
                val laneWaiter = queue.submit(workflow("lane-waiter"))
                val queued = queue.submit(workflow("queued"))
                runCurrent()
                assertIs<WorkflowJobState.Running>(laneWaiter.state.value)
                assertIs<WorkflowJobState.Queued>(queued.state.value)
                pool.close()
                assertTrue(assertIs<WorkflowJobState.Failed>(laneWaiter.await()).message.contains("closed"))
                assertTrue(assertIs<WorkflowJobState.Failed>(queued.await()).message.contains("closed"))
                assertTrue(lane.navigations.isEmpty())
                assertFalse(lane.closed)
            } finally {
                pool.release(externalLease)
                queue.closeAndJoin()
            }
        }

    @Test
    fun `an unavailable pool fails each job without retrying the quarantined lane`() =
        runTest {
            val lane =
                FakeWebViewController().apply {
                    onNavigate = { url -> if (url == "about:blank") throw PageLoadException("cannot initialize") }
                }
            val pool = pool(lane)
            val queue = WorkflowQueue(this, pool, context = EmptyCoroutineContext)
            try {
                val first = queue.submit(workflow("first"))
                val next = queue.submit(workflow("next"))
                assertTrue(assertIs<WorkflowJobState.Failed>(first.await()).message.contains("cannot initialize"))
                assertTrue(assertIs<WorkflowJobState.Failed>(next.await()).message.contains("unavailable"))
                assertEquals(setOf("a"), pool.state.value.unavailableLaneIds)
                assertEquals(1, lane.loadedHtml.size)
                assertTrue(lane.navigations.isEmpty())
            } finally {
                queue.closeAndJoin()
            }
        }
}
