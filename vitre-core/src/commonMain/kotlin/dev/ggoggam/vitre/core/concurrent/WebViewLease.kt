package dev.ggoggam.vitre.core.concurrent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Proof, carried in the coroutine context, that the bearer already holds a WebView's ordering lock.
 *
 * The lock is not reentrant — `Mutex` never is — so a caller inside an exclusive block that then
 * calls `evaluateJs` would deadlock against itself without something to say "this one is already
 * mine". Threading a token through every signature would work and would put it in the public API of
 * eight methods that have no other use for it; the context element goes where suspending calls
 * already go, which includes across `flowOn` into the workflow engine.
 *
 * [owner] is the identity `Mutex.holdsLock` is asked about, so a lease over one WebView is
 * invisible to every other: a caller holding session A's lease still queues normally for session B.
 *
 * [outer] is the lease this one shadowed when it was installed. The context holds one element per
 * key, so a nested `exclusively` over a *different* WebView replaces the outer WebView's lease under
 * the shared [Key]. Keeping the link means a call that reaches back out to the outer WebView — the
 * `a.exclusively { b.exclusively { a.evaluateJs(…) } }` shape — can still prove it holds A's lock and
 * pass through reentrantly, instead of deadlocking by re-taking A's non-reentrant Mutex it already
 * owns. Walking two chains toward each other from two coroutines still deadlocks, exactly as two
 * nested mutexes always have.
 */
internal class WebViewLease(
    val owner: Any,
    val outer: WebViewLease? = null,
) : AbstractCoroutineContextElement(WebViewLease) {
    companion object Key : CoroutineContext.Key<WebViewLease>

    /** True when this lease, or any it shadowed, owns the lock [holdsLock] reports on. */
    fun holds(holdsLock: (Any) -> Boolean): Boolean {
        var lease: WebViewLease? = this
        while (lease != null) {
            if (holdsLock(lease.owner)) return true
            lease = lease.outer
        }
        return false
    }
}
