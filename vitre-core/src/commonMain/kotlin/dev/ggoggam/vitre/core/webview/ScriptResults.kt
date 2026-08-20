package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The settle plane: where an `evaluateJs` whose script returned a promise waits for the answer.
 *
 * This is deliberately not the inbox. Settled-promise reports used to travel through
 * [dev.ggoggam.vitre.core.bridge.WebViewInbox] like page traffic, which meant every settle scanned a
 * deque of unrelated messages, internal plumbing leaked into `bridge.messages` — the observer
 * stream documented as being for hosts — and, worst, *anything* on the page could post a message
 * that resolved a workflow's extraction, because the cids are a guessable counter. Here the
 * correlation is a map keyed by cid, a report is only credited when it arrives from the main frame
 * and names this controller's [AsyncScript.nonce], and a claimed message never reaches the inbox
 * at all. See `docs/ASYNC-BRIDGE.md` for the whole argument.
 *
 * The subtlety worth reading twice is [clear] versus `WebViewSerializer`'s resubmit-once. A
 * navigation kills the page's promises, so waits must fail promptly rather than sit out the whole
 * script timeout — but a navigation that lands while the *evaluate itself* is still in flight is
 * exactly the case the serializer survives by resubmitting the script against the new document,
 * and that resubmitted script will settle under the same cid. So an entry is *armed* only once its
 * caller has seen the pending sentinel and is genuinely waiting on a promise the old document
 * owned: [clear] fails armed entries and leaves in-flight ones for the resubmit to answer. The
 * residual window — a navigation between the sentinel arriving and the arm — degrades to the
 * ordinary timeout, which is the pre-existing behaviour for every orphaned wait.
 *
 * Thread contract: [evaluate] is called from caller coroutines; [deliver] and [clear] from
 * platform callbacks on the WebView thread. The table is copy-on-write for that reason, and
 * [deliver] never suspends.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ScriptResults(
    private val asyncScript: AsyncScript = AsyncScript(),
) {
    /** Distinguishes one awaited promise from the next, including one a caller has given up on. */
    private val cids = AtomicLong(0L)

    private val pending = AtomicReference<Map<Long, Entry>>(emptyMap())

    /**
     * Evaluates [script] through [evaluateRaw] — the platform's own evaluate, encoding and all —
     * and waits on the bridge for the answer when the answer is a promise.
     *
     * The wait is outside [evaluateRaw] on purpose. Waiting is not an operation on the WebView,
     * and holding the WebView while waiting for the page to speak is the deadlock
     * `WebViewSerializer` warns about — a promise that resolves from a `fetch` needs the
     * renderer's main thread, and the renderer's main thread is what the lock would be sitting on.
     *
     * No race in the gap between registration and settling: the entry is registered before the
     * script is submitted, so a promise that settles instantly finds its deferred already there.
     *
     * @throws ScriptFailedException if the promise rejected, with the page's own message.
     * @throws ScriptTimeoutException if the promise neither settles within [timeoutMs] nor
     *   survives the document it belonged to.
     */
    suspend fun evaluate(
        script: String,
        timeoutMs: Long,
        evaluateRaw: suspend (String) -> String,
    ): String {
        val cid = cids.incrementAndFetch()
        val entry = Entry(CompletableDeferred(), armed = false)
        update { it + (cid to entry) }
        try {
            val immediate = evaluateRaw(asyncScript.wrap(script, cid))
            if (immediate != asyncScript.pendingResult(cid)) return immediate
            arm(cid)
            return try {
                withTimeout(timeoutMs) { entry.deferred.await() }
            } catch (_: TimeoutCancellationException) {
                throw ScriptTimeoutException("Promise did not settle within ${timeoutMs}ms")
            }
        } finally {
            update { it - cid }
        }
    }

    /**
     * Offers one inbound bridge message to the settle plane. Never suspends.
     *
     * True means the message was a settled-promise report and is consumed — it must not reach the
     * inbox, whether or not it completed a wait, or `bridge.messages` would carry internal
     * plumbing and forged reports alike. False means the message belongs to the page.
     *
     * A report is credited only when [fromMainFrame] — a subframe's report is a forgery by
     * definition, since only the document being driven runs wrapped scripts — and only when it
     * names this controller's nonce and a cid somebody is waiting on. Everything else is dropped.
     */
    fun deliver(
        raw: String,
        fromMainFrame: Boolean,
    ): Boolean {
        val settled = asyncScript.parse(raw) ?: return false
        if (!fromMainFrame) return true
        if (settled.nonce != asyncScript.nonce) return true
        val cid = settled.cid ?: return true
        val entry = take(cid) ?: return true
        val value = runCatching { settled.valueOrThrow() }
        value.fold(
            onSuccess = { entry.deferred.complete(it) },
            onFailure = { entry.deferred.completeExceptionally(it) },
        )
        return true
    }

    /**
     * A new document committed: promises the old one owned can never settle now, so every armed
     * wait fails immediately rather than sitting out its timeout. Entries whose evaluate is still
     * in flight stay — `WebViewSerializer` may be about to resubmit their script against the new
     * document, and that run settles under the same cid.
     */
    fun clear() {
        while (true) {
            val current = pending.load()
            val kept = current.filterValues { !it.armed }
            if (kept.size == current.size) return
            if (pending.compareAndSet(current, kept)) {
                for ((_, entry) in current) {
                    if (entry.armed) {
                        entry.deferred.completeExceptionally(
                            ScriptTimeoutException("The page navigated away while a promise was settling"),
                        )
                    }
                }
                return
            }
        }
    }

    /** Marks [cid]'s caller as genuinely waiting on a promise — see [clear] for what that changes. */
    private fun arm(cid: Long) {
        while (true) {
            val current = pending.load()
            val entry = current[cid] ?: return
            if (entry.armed) return
            if (pending.compareAndSet(current, current + (cid to entry.copy(armed = true)))) return
        }
    }

    /** Removes and returns [cid]'s entry, or null if nobody is waiting on it any more. */
    private fun take(cid: Long): Entry? {
        while (true) {
            val current = pending.load()
            val entry = current[cid] ?: return null
            if (pending.compareAndSet(current, current - cid)) return entry
        }
    }

    private inline fun update(transform: (Map<Long, Entry>) -> Map<Long, Entry>) {
        while (true) {
            val current = pending.load()
            if (pending.compareAndSet(current, transform(current))) return
        }
    }

    private data class Entry(
        val deferred: CompletableDeferred<String>,
        val armed: Boolean,
    )
}
