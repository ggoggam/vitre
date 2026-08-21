package dev.ggoggam.vitre.agent.session

import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** How long a lease is held before it expires on its own. */
const val DEFAULT_LEASE_TTL_MS: Long = 30_000L

/** How long `acquire_lease` waits to be handed the WebView before giving up. */
const val DEFAULT_LEASE_ACQUIRE_TIMEOUT_MS: Long = 15_000L

/** The shortest lease worth granting: below this the claim expires before the caller can use it. */
const val MIN_LEASE_TTL_MS: Long = 1_000L

/**
 * The longest a caller may hold a WebView.
 *
 * Enforced here rather than only in [PageDriver.acquireLease] because the whole point of the TTL is
 * to defend the page against a caller that stops, and a bound that only one of the two entry points
 * applies is not a bound.
 */
const val MAX_LEASE_TTL_MS: Long = 600_000L

/**
 * A claim on one session held across several tool calls.
 *
 * Ordering already stops two callers corrupting each other's individual operations. It does not stop
 * this happening:
 *
 * ```
 * agent A: wait_for(".price")        agent B: click("#next-page")
 * agent A: extract(".price")   ←  reads the price on B's page
 * ```
 *
 * Every one of those four operations was properly serialised and the answer is still wrong, because
 * what needed to be indivisible was A's *pair*. A lease is the public way to say so.
 */
class SessionLease internal constructor(
    val id: String,
    val sessionId: String,
    /**
     * The controller this lease actually holds the lock on. A leased call must run against *this*,
     * not against whatever is registered under [sessionId] when the call arrives: re-registering the
     * id (a recomposition that rebuilt the WebView) swaps the controller, and the lock the lease
     * holds is on the old one. Running the call on the new controller would find no claim and take
     * the lock per-operation — silently losing the very atomicity the lease exists to give.
     */
    val controller: WebViewController,
    /** The TTL actually applied, after clamping into [MIN_LEASE_TTL_MS]..[MAX_LEASE_TTL_MS]. */
    val ttlMs: Long,
    private val granted: CompletableDeferred<ExclusiveAccess>,
    private val released: CompletableDeferred<Unit>,
) {
    /**
     * Serialises calls made under this lease against each other.
     *
     * Without it the lease would be a hole in the ordering rather than an extension of it: every
     * call under a lease bypasses the WebView's lock — that is what holding it means — so two tool
     * calls arriving on the same lease at once would interleave freely, which is the exact failure
     * the lease was taken out to prevent.
     */
    private val gate = Mutex()

    internal suspend fun <T> use(block: suspend () -> T): T =
        gate.withLock {
            // Checked here as well as in the registry because releasing only *asks* the holder to
            // let go: the coroutine actually holding the lock resumes later, so between the two
            // there is a window in which this object still looks usable and the claim behind it is
            // already gone. Acting in that window would bypass the WebView's lock while holding
            // nothing.
            if (released.isCompleted) throw LeaseException("Lease `$id` has been released.")
            granted.await().use(block)
        }

    internal fun release() {
        released.complete(Unit)
    }
}

/**
 * Grants, holds and expires [SessionLease]s.
 *
 * The holding is done by a coroutine parked inside `WebViewController.exclusively`, which is the
 * only way to keep a lock across calls that each arrive on a coroutine of their own. That parked
 * coroutine is also where the expiry lives, and it has to live somewhere: an agent can stop between
 * acquiring a lease and releasing it — its process dies, its LLM call times out, a user cancels the
 * run — and a WebView held forever by a caller that no longer exists is worse than any interleaving
 * the lease was preventing. `vitre-core` deliberately does not impose this bound: it has no notion
 * of a client that can go away.
 */
class SessionLeases(
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Map<String, SessionLease>>(emptyMap())

    val active: Map<String, SessionLease> get() = state.value

    /** Whether [id] still names a live claim, rather than one expired or already released. */
    fun isActive(id: String): Boolean = id in state.value

    /**
     * Takes the WebView and holds it until released or [ttlMs] elapses.
     *
     * [ttlMs] is clamped into [MIN_LEASE_TTL_MS]..[MAX_LEASE_TTL_MS]: an unbounded lease lets a
     * caller that has stopped wedge the page for days, and `0` would return one already dead.
     *
     * @throws LeaseException if the WebView could not be claimed within [acquireTimeoutMs] — which
     *   means somebody else is holding it, since an unheld lock is taken without suspending.
     */
    suspend fun acquire(
        session: WebViewSession,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
        acquireTimeoutMs: Long = DEFAULT_LEASE_ACQUIRE_TIMEOUT_MS,
    ): SessionLease {
        val clamped = ttlMs.coerceIn(MIN_LEASE_TTL_MS, MAX_LEASE_TTL_MS)
        val id = "lease_" + Random.nextLong().toULong().toString(HEX_RADIX)
        val granted = CompletableDeferred<ExclusiveAccess>()
        val released = CompletableDeferred<Unit>()
        val lease = SessionLease(id, session.id, session.controller, clamped, granted, released)

        // Registered before the holder is launched, so the holder's own cleanup cannot run before
        // the entry it removes exists and leave a lease nobody can release.
        state.update { it + (id to lease) }
        val holder =
            scope.launch {
                try {
                    session.controller.exclusively { access ->
                        granted.complete(access)
                        withTimeoutOrNull(clamped) { released.await() }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // The controller was closed under us (a WebView that left the composition while
                    // still registered throws IllegalStateException from `exclusively`). Route it to
                    // the waiting acquire as the real cause rather than letting it reach the host
                    // scope this coroutine runs in — where it would be an app crash, not the isError
                    // tool result MCP promises.
                    granted.completeExceptionally(failure)
                } finally {
                    state.update { it - id }
                }
            }
        holder.invokeOnCompletion { cause ->
            // Only has an effect if the lock was never handed over; completing an already-completed
            // deferred is a no-op, so a normal release does not look like a failure here.
            granted.completeExceptionally(
                cause ?: CancellationException("Lease $id ended before the WebView was claimed"),
            )
        }

        val outcome =
            try {
                withTimeoutOrNull(acquireTimeoutMs) { runCatching { granted.await() } }
            } catch (interrupted: Throwable) {
                // The *caller* went away while waiting to be handed the lock. The holder runs on the
                // host's scope rather than the caller's, so nothing else would stop it: it would go
                // on to take the lock and park there for the whole TTL, holding the user's WebView
                // for an id that was never returned to anybody.
                holder.cancel()
                state.update { it - id }
                throw interrupted
            }
        if (outcome == null || outcome.isFailure) {
            holder.cancel()
            state.update { it - id }
            val cause = outcome?.exceptionOrNull()
            throw LeaseException(
                // Contention is the *timeout* case (outcome null) or a plain cancellation. A concrete
                // cause — a closed controller — is reported as itself, because telling the agent to
                // "retry against a caller holding it" is both the wrong diagnosis and the one remedy
                // that cannot work.
                if (cause == null || cause is CancellationException) {
                    "Could not take session `${session.id}` within ${acquireTimeoutMs}ms. Most likely " +
                        "another caller is holding it — an unheld WebView is claimed without waiting. " +
                        "Retry, or work without a lease if single steps are enough."
                } else {
                    "Could not take session `${session.id}`: ${cause.message ?: cause::class.simpleName}"
                },
            )
        }
        return lease
    }

    /** @throws LeaseException if [id] is unknown, which for a lease means expired or already released. */
    fun require(
        id: String,
        sessionId: String,
    ): SessionLease {
        val lease =
            state.value[id]
                ?: throw LeaseException(
                    "Lease `$id` is not active. It has expired (leases are held for a bounded time so " +
                        "a client that goes away cannot wedge the WebView) or was already released. " +
                        "Acquire a new one.",
                )
        if (lease.sessionId != sessionId) {
            throw LeaseException("Lease `$id` is held on session `${lease.sessionId}`, not `$sessionId`.")
        }
        return lease
    }

    fun release(id: String): Boolean {
        val lease = state.value[id] ?: return false
        // Deregistered here rather than left to the holder's own cleanup, which runs a coroutine
        // resumption later. Until it does, a lookup would still find the lease and let a call through
        // on a claim that is on its way out.
        state.update { it - id }
        lease.release()
        return true
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}

/** A lease could not be taken, or the one quoted is no longer live. */
class LeaseException(
    message: String,
) : RuntimeException(message)
