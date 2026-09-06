package dev.ggoggam.vitre.agent.session

import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLeasesTest {
    @Test
    fun expiry_cancels_active_and_queued_uses_before_unlocking() = revocationKeepsOwnership(expire = true)

    @Test
    fun release_cancels_active_and_queued_uses_before_unlocking() = revocationKeepsOwnership(expire = false)

    private fun revocationKeepsOwnership(expire: Boolean) =
        runTest {
            val page = Page()
            val leases = SessionLeases(backgroundScope)
            val lease = leases.acquire(page.session(), ttlMs = 1_000)
            val cleanupStarted = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            val running =
                launch {
                    lease.use {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                finishCleanup.await()
                                page.evaluateJs("cleanup")
                            }
                        }
                    }
                }
            runCurrent()
            var queuedEntered = false
            val queued = launch { lease.use { queuedEntered = true } }
            runCurrent()
            if (expire) {
                advanceTimeBy(1_000)
                runCurrent()
            } else {
                assertTrue(leases.release(lease.id))
            }
            cleanupStarted.await()
            assertFalse(leases.isActive(lease.id))
            assertFailsWith<LeaseException> { lease.use { error("stale lease entered") } }
            val outsider = launch { page.evaluateJs("outsider") }
            runCurrent()
            assertEquals(emptyList(), page.scripts)
            assertFalse(queuedEntered)

            finishCleanup.complete(Unit)
            running.join()
            queued.join()
            outsider.join()
            assertTrue(running.isCancelled)
            assertTrue(queued.isCancelled)
            assertEquals(listOf("cleanup", "outsider"), page.scripts)
        }

    @Test
    fun host_cancellation_revokes_existing_references_and_waits_for_cleanup() =
        runTest {
            val page = Page()
            val host = SupervisorJob()
            val leases = SessionLeases(CoroutineScope(coroutineContext + host))
            val lease = leases.acquire(page.session())
            val finishCleanup = CompletableDeferred<Unit>()
            val running =
                launch {
                    lease.use {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { finishCleanup.await() }
                        }
                    }
                }
            runCurrent()
            host.cancel()
            val outsider = launch { page.evaluateJs("outsider") }
            runCurrent()
            assertFalse(leases.isActive(lease.id))
            assertFalse(outsider.isCompleted)
            assertFalse(host.isCompleted)
            assertFailsWith<LeaseException> { lease.use { } }
            finishCleanup.complete(Unit)
            running.join()
            host.join()
            outsider.join()
        }

    @Test
    fun cancelling_one_use_does_not_revoke_the_lease() =
        runTest {
            val page = Page()
            val leases = SessionLeases(backgroundScope)
            val lease = leases.acquire(page.session())
            val running = launch { lease.use { awaitCancellation() } }
            runCurrent()
            running.cancelAndJoin()
            lease.use { page.evaluateJs("next") }
            assertTrue(leases.isActive(lease.id))
            assertEquals(listOf("next"), page.scripts)
            leases.release(lease.id)
        }

    @Test
    fun concurrent_uses_keep_whole_sequences_together() =
        runTest {
            val page = Page()
            val leases = SessionLeases(backgroundScope)
            val lease = leases.acquire(page.session())
            val finishFirst = CompletableDeferred<Unit>()
            val first =
                launch {
                    lease.use {
                        page.evaluateJs("first start")
                        finishFirst.await()
                        page.evaluateJs("first finish")
                    }
                }
            runCurrent()
            val second = launch { lease.use { page.evaluateJs("second") } }
            runCurrent()
            assertEquals(listOf("first start"), page.scripts)
            finishFirst.complete(Unit)
            first.join()
            second.join()
            assertEquals(listOf("first start", "first finish", "second"), page.scripts)
            leases.release(lease.id)
        }

    @Test
    fun a_cancelled_host_does_not_leave_a_registered_lease() =
        runTest {
            val host = SupervisorJob().apply { cancel() }
            val leases = SessionLeases(CoroutineScope(coroutineContext + host))
            assertFailsWith<LeaseException> { leases.acquire(Page().session()) }
            assertTrue(leases.active.isEmpty())
        }

    private class Page : WebViewController {
        private val order = WebViewOrdering()
        val scripts = mutableListOf<String>()
        override val bridge: WebViewBridge get() = error("unused")

        override suspend fun navigate(url: String) = order.ordered { }

        override suspend fun loadHtml(
            html: String,
            baseUrl: String?,
        ) = order.ordered { }

        override suspend fun evaluateJs(script: String): String =
            order.ordered {
                scripts.add(script)
                "null"
            }

        override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

        override fun close() = Unit

        fun session() = WebViewSessions().apply { register("main", this@Page) }.resolve("main")
    }
}
