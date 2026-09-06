package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
 * A navigation invalidates all submitted entries, including ones whose raw evaluate has not yet
 * returned the pending sentinel. This closes the gap between submission and starting to await a
 * promise: a late sentinel observes the same unknown outcome as a wait already in progress.
 * Scripts are never automatically replayed against a replacement document.
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
     * [evaluateRaw] invokes its submission callback on the WebView thread immediately before
     * submitting the wrapped script. Registration therefore precedes even instant settlements,
     * while a script queued behind an earlier navigation is not invalidated before it starts.
     *
     * @throws ScriptFailedException if the promise rejected, with the page's own message.
     * @throws ScriptTimeoutException if the promise neither settles within [timeoutMs] nor
     *   survives the document it belonged to.
     */
    suspend fun evaluate(
        script: String,
        timeoutMs: Long,
        evaluateRaw: suspend (String, onSubmitted: () -> Unit) -> String,
    ): String {
        val cid = cids.incrementAndFetch()
        val entry = Entry(CompletableDeferred())
        try {
            val immediate = evaluateRaw(asyncScript.wrap(script, cid)) { update { it + (cid to entry) } }
            if (immediate != asyncScript.pendingResult(cid)) return immediate
            return withTimeoutOrNull(timeoutMs) { entry.deferred.await() }
                ?: throw ScriptOutcomeUnknownException(
                    "Promise did not settle within ${timeoutMs}ms; it may already have taken effect. " +
                        "Inspect the page before retrying.",
                )
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
     * A new document committed: invalidate every pending promise, including evaluates still
     * waiting for the platform's immediate callback. An already-delivered result remains valid.
     */
    fun clear() {
        while (true) {
            val current = pending.load()
            if (current.isEmpty()) return
            if (pending.compareAndSet(current, emptyMap())) {
                for ((_, entry) in current) {
                    entry.deferred.completeExceptionally(
                        ScriptOutcomeUnknownException(
                            "The page navigated away while a promise was settling; it may already have taken effect. " +
                                "Inspect the page before retrying.",
                        ),
                    )
                }
                return
            }
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
    )
}
