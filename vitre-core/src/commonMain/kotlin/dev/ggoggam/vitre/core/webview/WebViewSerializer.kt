package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.concurrent.WebViewDispatcher
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** How long a single script evaluation may take before the caller is released. */
const val DEFAULT_SCRIPT_TIMEOUT_MS: Long = 15_000L

/**
 * The library's entire concurrency policy, in one object per WebView.
 *
 * A WebView is a single-threaded, single-document resource with two callers that do not know about
 * each other — the workflow engine, and whatever the host does directly (an agent tool call, a
 * button, later an MCP request). Two rules keep that sound, and both live here so neither platform
 * actual can drift from the other:
 *
 *  1. **Confinement.** Every platform call is handed to [WebViewDispatcher]. Callers may run on any
 *     dispatcher they like; they cross to the WebView thread for the duration of one operation.
 *  2. **Serialisation.** [navigate] and [evaluate] share one lock, so operations against the WebView
 *     are totally ordered. Interleaving them is not merely racy, it is meaningless: a script
 *     evaluated halfway through someone else's navigation runs against whichever document happened
 *     to be committed, which is not a result any caller asked for.
 *
 * Waiting on an inbound bridge message deliberately does *not* take the lock — it is a wait, not an
 * operation, and holding the WebView while waiting for the page to say something is a deadlock: the
 * page usually needs a script to run before it will say anything.
 *
 * [dispatcher] is injectable because unit tests have no main thread to confine to.
 */
internal class WebViewSerializer(
    private val dispatcher: CoroutineDispatcher = WebViewDispatcher,
) {
    private val signals = MutableSharedFlow<Signal>(extraBufferCapacity = SIGNAL_BUFFER)
    private val order = WebViewOrdering()

    /** The main frame started committing a new document. */
    fun started() {
        signals.tryEmit(Signal.Started)
    }

    /** The main frame finished loading. */
    fun finished() {
        signals.tryEmit(Signal.Finished(errorMessage = null))
    }

    /** The main frame failed to load; [message] is surfaced on the [PageLoadException]. */
    fun failed(message: String) {
        signals.tryEmit(Signal.Finished(errorMessage = message))
    }

    /**
     * Runs [startLoad] on the WebView thread and suspends until the page it kicks off has loaded.
     *
     * Neither `WebViewClient` nor `WKNavigationDelegate` hands us a token we can compare against the
     * load we asked for, so the callbacks have to be correlated positionally. Two rules make that
     * sound:
     *  - the caller subscribes *before* the load is triggered, and the signal flow has no replay, so
     *    anything a previously in-flight load already reported is never seen;
     *  - a [finished] only counts once a [started] has been seen, so the tail end of a load that was
     *    still running when [navigate] was called cannot satisfy it.
     *
     * A page that never settles — or a callback the platform simply never delivers — must not
     * strand the caller forever, so the wait is bounded and the expiry is reported as a
     * [PageLoadException] rather than a `TimeoutCancellationException`. Letting a cancellation
     * escape would be indistinguishable, one frame up, from the caller having been cancelled.
     *
     * @throws PageLoadException if the load fails or does not settle within [timeoutMs].
     */
    suspend fun navigate(
        timeoutMs: Long,
        startLoad: () -> Unit,
    ) {
        // Taken before the dispatcher switch: an uncontended Mutex.lock() does not suspend, so a
        // caller started UNDISPATCHED — which is how the Compose hosts kick off their initial load —
        // still owns the WebView by the time it hands the controller to anyone else.
        ordered {
            var started = false
            val outcome =
                try {
                    withTimeout(timeoutMs) {
                        signals
                            .onSubscription { withContext(dispatcher) { startLoad() } }
                            .transform { signal ->
                                when (signal) {
                                    Signal.Started -> started = true
                                    is Signal.Finished -> if (started) emit(signal)
                                }
                            }.first()
                    }
                } catch (_: TimeoutCancellationException) {
                    throw PageLoadException("Timed out after ${timeoutMs}ms waiting for the page to load")
                }
            outcome.errorMessage?.let { throw PageLoadException(it) }
        }
    }

    /**
     * Runs [submit] on the WebView thread and suspends for the result it resumes with.
     *
     * Both platforms drop a pending script callback when the document it was submitted against goes
     * away, without ever invoking it. Ordering against [navigate] rules that out for navigations
     * *we* start; a page that redirects, meta-refreshes, or navigates out from under a click starts
     * its own, and the script submitted just before it is never answered.
     *
     * So a lost script is **resubmitted once**, against the document that replaced the one it was
     * lost to. Once, because a second loss is a genuine fault and should look like one — and
     * waiting out [timeoutMs] instead would report a page that is visibly fine as a slow one.
     *
     * The caveat is [submit]'s to carry: a resubmitted script runs a second time, so a step that
     * navigates *by* running (a click on a link) can act twice. In practice the platform answers a
     * click's own evaluation before the navigation it triggers commits, and it is the step *after*
     * the click that gets lost; a caller for whom that is not good enough should hold
     * [exclusively] and drive the navigation itself.
     *
     * @throws ScriptTimeoutException if no result arrives within [timeoutMs], or if the page
     *   navigated away from both attempts.
     */
    suspend fun evaluate(
        timeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
        submit: (CancellableContinuation<String>) -> Unit,
    ): String =
        ordered {
            try {
                withTimeout(timeoutMs) {
                    submitOnce(submit)
                        ?: submitOnce(submit)
                        ?: throw ScriptTimeoutException("The page navigated away from two attempts at the script")
                }
            } catch (_: TimeoutCancellationException) {
                throw ScriptTimeoutException("Script did not return within ${timeoutMs}ms")
            }
        }

    /**
     * One attempt: the result, or null if the page replaced the document out from under it.
     *
     * Two details carry this, and both are the same ones [navigate] relies on:
     *
     *  - The watcher subscribes *before* the script is submitted, and the signal flow has no
     *    replay, so the only [Signal.Started] it can see is one that arrived after the script went
     *    in. Without that ordering a load already in flight would look like the script being lost,
     *    and every evaluation immediately after a navigation would run twice.
     *  - It resolves on the [Signal.Finished] that *follows* that start, in one uninterrupted
     *    collection, rather than returning on the start and subscribing again. Returning early
     *    would hand the retry a document still being parsed — an `Extract` against half a DOM
     *    returns nothing and looks like a selector that stopped matching — and re-subscribing
     *    afterwards would race the finish it is waiting for.
     *
     * A script that answers late, after the new document has started but before it settles, still
     * wins: whichever completes first is the one taken.
     */
    private suspend fun submitOnce(submit: (CancellableContinuation<String>) -> Unit): String? =
        coroutineScope {
            val subscribed = CompletableDeferred<Unit>()
            val replaced =
                async {
                    var started = false
                    signals
                        .onSubscription { subscribed.complete(Unit) }
                        .transform { signal ->
                            when (signal) {
                                Signal.Started -> started = true
                                is Signal.Finished -> if (started) emit(signal)
                            }
                        }.first()
                }
            val result =
                async {
                    subscribed.await()
                    withContext(dispatcher) { suspendCancellableCoroutine { cont -> submit(cont) } }
                }
            val outcome =
                select {
                    result.onAwait { it }
                    replaced.onAwait { null }
                }
            // Whichever lost is now waiting on a document that has gone, or on a navigation that is
            // no longer interesting. Neither will ever complete on its own.
            replaced.cancel()
            result.cancel()
            outcome
        }

    /**
     * Holds the ordering lock for the whole of [block], so a multi-step sequence cannot be
     * interleaved with another caller's operations.
     *
     * Deliberately not bounded here. The block is the caller's own code, and a library-imposed
     * deadline on it would fire in the middle of somebody's workflow for reasons they never asked
     * about; a caller who wants one writes `withTimeout`. The bound that genuinely matters belongs a
     * layer up, where "the client went away mid-sequence" is a thing that can happen — which is why
     * `vitre-mcp` expires its leases and core does not.
     */
    suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

    private suspend fun <T> ordered(block: suspend () -> T): T = order.ordered(block)

    private sealed interface Signal {
        data object Started : Signal

        data class Finished(
            val errorMessage: String?,
        ) : Signal
    }

    private companion object {
        const val SIGNAL_BUFFER = 32
    }
}
