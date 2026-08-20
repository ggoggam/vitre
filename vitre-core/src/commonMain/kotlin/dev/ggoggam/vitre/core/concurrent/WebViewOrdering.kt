package dev.ggoggam.vitre.core.concurrent

import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Who may touch one WebView, and in what order. Half of the library's concurrency policy; the other
 * half — which thread — belongs to `WebViewSerializer`, which owns one of these.
 *
 * It is public because [WebViewController.exclusively] cannot be implemented without it. An
 * [ExclusiveAccess] is only issuable from here, so a controller written outside this module — a test
 * double, a headless implementation, a wrapper around some other embedded browser — would otherwise
 * have a method in its interface it had no way to satisfy. Implementors hold one of these and
 * delegate both [ordered] and [exclusively] to it:
 *
 * ```kotlin
 * private val order = WebViewOrdering()
 *
 * override suspend fun evaluateJs(script: String): String = order.ordered { … }
 * override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)
 * ```
 *
 * Sharing it rather than reimplementing it is not only convenience. `docs/PLAN.md` records that the
 * test double was laxer than production in exactly the two places production turned out to be
 * broken; a second ordering implementation is the one place a lease bug can hide from every test
 * written to catch it.
 */
class WebViewOrdering {
    private val order = Mutex()

    /**
     * Runs [block] with the WebView to itself for the duration of that one operation.
     *
     * A caller already inside an [exclusively] block over *this* ordering passes straight through,
     * since it holds the lock already and `Mutex` is not reentrant.
     *
     * The check is per-owner rather than a boolean, so holding session A's claim grants nothing over
     * session B — a caller inside A's exclusive block still queues for B like anyone else. The flip
     * side is ordinary lock-ordering: two callers that each hold one session's claim and reach for
     * the other's deadlock, exactly as two nested mutexes always have.
     */
    suspend fun <T> ordered(block: suspend () -> T): T {
        val lease = currentCoroutineContext()[WebViewLease]
        return if (lease != null && lease.holds(order::holdsLock)) block() else order.withLock { block() }
    }

    /**
     * Holds the lock for the whole of [block], so a multi-step sequence cannot be interleaved.
     *
     * Reentrant: called while already holding it, [block] runs inline rather than deadlocking
     * against itself. Not bounded — see [WebViewController.exclusively] for why the deadline belongs
     * to the caller rather than here.
     */
    suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T {
        val existing = currentCoroutineContext()[WebViewLease]
        existing
            ?.takeIf { it.holds(order::holdsLock) }
            ?.let { return block(ExclusiveAccess(it)) }

        // Chained onto whatever lease is already in context (an outer WebView's, or null), so a call
        // that reaches back out to that WebView can still find its lock — see WebViewLease.outer.
        val lease = WebViewLease(Any(), outer = existing)
        order.lock(lease.owner)
        return try {
            // Putting the claim in context here is what makes calls *inside* the block reentrant
            // without the caller having to pass anything around.
            withContext(lease) { block(ExclusiveAccess(lease)) }
        } finally {
            order.unlock(lease.owner)
        }
    }
}
