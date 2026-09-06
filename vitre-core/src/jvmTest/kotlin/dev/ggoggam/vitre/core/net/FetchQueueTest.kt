package dev.ggoggam.vitre.core.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FetchQueueTest {
    @Test
    fun `saturation rejects immediately and cancellation frees queued capacity`() {
        val queue = FetchQueue(workers = 1, capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val cancelledRuns = AtomicInteger()
        val cancelledCallbacks = AtomicInteger()
        try {
            queue
                .task({
                    entered.countDown()
                    release.awaitReady()
                }, {})
                .start()
            entered.awaitReady()
            val cancelled = queue.task({ cancelledRuns.incrementAndGet() }, { cancelledCallbacks.incrementAndGet() })
            cancelled.start()
            var rejection: Throwable? = null
            queue.task({ error("overloaded work must never run") }, { rejection = it.exceptionOrNull() }).start()
            assertIs<RejectedExecutionException>(rejection)
            cancelled.cancel()
            queue
                .task({ 42 }, {
                    assertEquals(42, it.getOrThrow())
                    completed.countDown()
                })
                .start()
            release.countDown()
            completed.awaitReady()
            assertEquals(0, cancelledRuns.get())
            assertEquals(0, cancelledCallbacks.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `closing rejects waiting and new work while running work completes`() {
        val queue = FetchQueue(workers = 1, capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val rejected = AtomicInteger()
        try {
            queue
                .task({
                    entered.countDown()
                    release.awaitReady()
                }, {
                    it.getOrThrow()
                    completed.countDown()
                })
                .start()
            entered.awaitReady()
            val waiting =
                queue.task({ error("closed queued work must never run") }, {
                    assertIs<RejectedExecutionException>(it.exceptionOrNull())
                    rejected.incrementAndGet()
                })
            waiting.start()
            queue.close()
            queue.close()
            waiting.start()
            queue
                .task({ error("closed submission must never run") }, {
                    assertIs<RejectedExecutionException>(it.exceptionOrNull())
                    rejected.incrementAndGet()
                })
                .start()
            assertEquals(2, rejected.get())
            release.countDown()
            completed.awaitReady()
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `expired queued work fails without fetching`() {
        val clock = AtomicLong()
        val queue = FetchQueue(workers = 1, capacity = 1, maxQueueWaitNanos = 10, nanoTime = clock::get)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expired = CountDownLatch(1)
        try {
            queue
                .task({
                    entered.countDown()
                    release.awaitReady()
                }, {})
                .start()
            entered.awaitReady()
            queue
                .task({ error("expired work must not run") }, {
                    assertIs<TimeoutException>(it.exceptionOrNull())
                    expired.countDown()
                })
                .start()
            clock.set(10)
            release.countDown()
            expired.awaitReady()
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `cancellation before admission and during a fetch suppresses completion`() {
        val queue = FetchQueue(workers = 1, capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            val early = queue.task({ error("cancelled work must not run") }, { callbacks.incrementAndGet() })
            early.cancel()
            early.start()
            val running =
                queue.task({
                    entered.countDown()
                    release.awaitReady()
                }, { callbacks.incrementAndGet() })
            running.start()
            entered.awaitReady()
            running.cancel()
            queue.task({}, { drained.countDown() }).start()
            release.countDown()
            drained.awaitReady()
            assertEquals(0, callbacks.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `a failed fetch releases the worker for following requests`() {
        val queue = FetchQueue(workers = 1, capacity = 1)
        val failed = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            queue
                .task({ throw IllegalArgumentException("bad URL") }, {
                    assertIs<IllegalArgumentException>(it.exceptionOrNull())
                    failed.countDown()
                })
                .start()
            failed.awaitReady()
            queue
                .task({ 42 }, {
                    assertEquals(42, it.getOrThrow())
                    completed.countDown()
                })
                .start()
            completed.awaitReady()
        } finally {
            queue.close()
        }
    }

    @Test
    fun `racing cancellation and admission never leaves a cancelled task in the queue`() {
        val queue = FetchQueue(workers = 1, capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            queue
                .task({
                    entered.countDown()
                    release.awaitReady()
                }, {})
                .start()
            entered.awaitReady()
            repeat(100) {
                val start = CountDownLatch(1)
                val task = queue.task({ error("cancelled work must not run") }, { callbacks.incrementAndGet() })
                val submitter =
                    thread {
                        start.awaitReady()
                        task.start()
                    }
                val canceller =
                    thread {
                        start.awaitReady()
                        task.cancel()
                    }
                start.countDown()
                submitter.join(5_000)
                canceller.join(5_000)
                assertTrue(!submitter.isAlive && !canceller.isAlive)
                // The single waiting slot must already be available again.
                val next = queue.task({}, { callbacks.incrementAndGet() })
                next.start()
                next.cancel()
                assertEquals(0, callbacks.get())
            }
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `shutdown racing submissions answers every request once`() {
        val queue = FetchQueue(workers = 1, capacity = 32)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val start = CountDownLatch(1)
        val rejected = AtomicInteger()
        try {
            queue
                .task({
                    entered.countDown()
                    release.awaitReady()
                }, {})
                .start()
            entered.awaitReady()
            val contenders =
                List(32) {
                    thread {
                        start.awaitReady()
                        queue
                            .task({ error("waiting work must not run") }, {
                                assertIs<RejectedExecutionException>(it.exceptionOrNull())
                                rejected.incrementAndGet()
                            })
                            .start()
                    }
                } +
                    thread {
                        start.awaitReady()
                        queue.close()
                    }
            start.countDown()
            contenders.forEach {
                it.join(5_000)
                assertTrue(!it.isAlive)
            }
            assertEquals(32, rejected.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    private fun CountDownLatch.awaitReady() {
        assertTrue(await(5, TimeUnit.SECONDS), "worker did not reach expected state")
    }
}
