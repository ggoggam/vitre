package dev.ggoggam.vitre.core.net

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Bounded, non-blocking admission for desktop's blocking HTTP client. */
internal class FetchQueue(
    workers: Int,
    capacity: Int,
    private val maxQueueWaitNanos: Long = TimeUnit.SECONDS.toNanos(20),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(maxQueueWaitNanos > 0) { "maxQueueWaitNanos must be positive" }
    }

    private val lifecycle = Any()
    private val threadIds = AtomicLong()
    private val executor =
        ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(capacity),
            { runnable ->
                Thread(runnable, "vitre-fetch-${threadIds.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )

    /** Create before starting so cancellation can race admission without losing the task handle. */
    fun <T> task(
        work: () -> T,
        complete: (Result<T>) -> Unit,
    ): Task<T> = Task(work, complete)

    /** Running fetches finish; waiting requests receive a failure instead of doing abandoned IO. */
    fun close() {
        val waiting = mutableListOf<Runnable>()
        synchronized(lifecycle) {
            executor.shutdown()
            executor.queue.drainTo(waiting)
        }
        waiting.forEach { (it as FetchQueue.Task<*>).reject(RejectedExecutionException("fetch queue is closed")) }
    }

    internal inner class Task<T>(
        private val work: () -> T,
        private val complete: (Result<T>) -> Unit,
    ) : Runnable {
        private val lock = Any()
        private var state = State.NEW
        private var queuedAt = 0L

        fun start() {
            synchronized(lock) {
                if (state != State.NEW) return
                state = State.QUEUED
                queuedAt = nanoTime()
            }
            val rejection =
                synchronized(lifecycle) {
                    // Cancellation may have won before admission. Holding the task lock through
                    // execute also ensures cancel() can always remove an admitted waiting task.
                    synchronized(lock) {
                        if (state != State.QUEUED) return
                        try {
                            executor.execute(this)
                            null
                        } catch (_: RejectedExecutionException) {
                            RejectedExecutionException(if (executor.isShutdown) "fetch queue is closed" else "fetch queue is full")
                        }
                    }
                }
            if (rejection != null) reject(rejection)
        }

        override fun run() {
            val expired =
                synchronized(lock) {
                    if (state != State.QUEUED) return
                    state = State.RUNNING
                    nanoTime() - queuedAt >= maxQueueWaitNanos
                }
            val result =
                if (expired) {
                    Result.failure(TimeoutException("fetch exceeded its queue wait limit"))
                } else {
                    try {
                        Result.success(work())
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                }
            synchronized(lock) {
                if (state != State.RUNNING) return
                state = State.FINISHED
                complete(result)
            }
        }

        fun cancel() {
            synchronized(lock) {
                if (state == State.FINISHED || state == State.CANCELLED) return
                state = State.CANCELLED
                executor.remove(this)
            }
        }

        internal fun reject(failure: RejectedExecutionException) {
            synchronized(lock) {
                if (state != State.QUEUED) return
                state = State.FINISHED
                complete(Result.failure(failure))
            }
        }
    }

    private enum class State { NEW, QUEUED, RUNNING, FINISHED, CANCELLED }
}
