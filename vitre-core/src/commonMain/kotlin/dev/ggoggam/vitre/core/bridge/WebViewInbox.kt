package dev.ggoggam.vitre.core.bridge

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds messages the page has posted until somebody asks for one.
 *
 * A plain `SharedFlow` cannot do this job, and getting it wrong is the single easiest way to hang a
 * workflow. `AwaitMessage` subscribes at the moment its step runs, but the page posts on its own
 * schedule — commonly the instant the script from the *previous* step finished. A no-replay flow
 * drops anything sent in that window, so the step waits forever for a message that already came and
 * went. Replay is not the fix either: a replayed message is matched again by every later step that
 * happens to look for the same type.
 *
 * So messages are buffered and consumed exactly once. [awaitMatching] takes the first unread message
 * satisfying its predicate — whether it arrived a second ago or before the caller existed — and
 * removes it. Anything not matched stays unread for a later awaiter.
 *
 * Only main-frame messages are consumable. A page a lane drives is free to embed anything, and an
 * iframe posting `{"type":"ready"}` would otherwise both satisfy *and* consume the await armed for
 * the main document — the same forgery `ScriptResults.deliver` documents for the settle plane, with
 * the same answer: only the document being driven may answer. Subframe messages are therefore never
 * queued for [awaitMatching]. They are not buffered either, because nothing else ever consumes from
 * the queue, so a buffered subframe message could only accumulate until the next navigation cleared
 * it.
 *
 * [messages] and [inbound] are the other half: non-consuming firehoses carrying *everything*,
 * subframes included, for hosts that want to log or display page traffic without competing with the
 * workflow for it. A subframe error is information; it just is not an answer.
 */
class WebViewInbox {
    /**
     * Arrivals and resets travel down one ordered channel rather than mutating shared state,
     * because [deliver] and [clear] are called from platform callbacks that cannot suspend to take
     * a lock. Draining happens under [mutex], on whichever awaiter gets there first.
     */
    private val arrivals = Channel<Arrival>(Channel.UNLIMITED)
    private val wakeups =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = WAKEUP_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val observers = MutableSharedFlow<String>(extraBufferCapacity = OBSERVER_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val taggedObservers =
        MutableSharedFlow<InboundBridgeMessage>(extraBufferCapacity = OBSERVER_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val unread = ArrayDeque<String>()

    /** Every message the page has posted, main frame or not, for observers. Does not consume. */
    val messages: SharedFlow<String> get() = observers.asSharedFlow()

    /**
     * The same traffic as [messages], tagged with the frame and origin it came from. Does not
     * consume. This is the stream to read to tell an iframe's message from the document's.
     */
    val inbound: SharedFlow<InboundBridgeMessage> get() = taggedObservers.asSharedFlow()

    /**
     * Called from the platform's message callback. Never suspends, never fails.
     *
     * Only a [fromMainFrame] message is queued for [awaitMatching]; every message reaches the
     * observer firehoses regardless. The defaults are what a caller with nothing better to say
     * means — a test simulating the page, a platform that cannot tell us.
     */
    fun deliver(
        message: String,
        fromMainFrame: Boolean = true,
        sourceOrigin: String? = null,
    ) {
        if (fromMainFrame) arrivals.trySend(Arrival.Message(message))
        observers.tryEmit(message)
        taggedObservers.tryEmit(InboundBridgeMessage(message, fromMainFrame, sourceOrigin))
        if (fromMainFrame) wakeups.tryEmit(Unit)
    }

    /**
     * Discards everything unread. Called when a new document commits: those messages belong to a
     * page that no longer exists, and leaving them would let a stale `ready` satisfy the
     * `AwaitMessage` that was waiting for the *new* page's.
     */
    fun clear() {
        arrivals.trySend(Arrival.Reset)
        wakeups.tryEmit(Unit)
    }

    /**
     * Suspends until an unread message satisfies [predicate], then consumes and returns it.
     *
     * Only main-frame messages are candidates — see the class KDoc. A subframe posting the exact
     * string this predicate wants leaves the wait suspended, and shows up on [inbound] instead.
     *
     * Wrap in `withTimeout` to bound the wait — a page that never posts is indistinguishable from
     * one that has not posted yet.
     */
    suspend fun awaitMatching(predicate: (String) -> Boolean): String =
        wakeups
            // Subscribe first, then probe. Rescanning before the subscription exists would leave a
            // window where a message arrives, its wakeup lands on nobody, and the caller sleeps on
            // through a message that is already sitting in the buffer.
            .onSubscription { wakeups.emit(Unit) }
            .mapNotNull { takeMatch(predicate) }
            .first()

    private suspend fun takeMatch(predicate: (String) -> Boolean): String? =
        mutex.withLock {
            drainArrivals()
            val index = unread.indexOfFirst(predicate)
            if (index < 0) null else unread.removeAt(index)
        }

    /** Must be called under [mutex]. */
    private fun drainArrivals() {
        while (true) {
            when (val arrival = arrivals.tryReceive().getOrNull()) {
                null -> return
                is Arrival.Message -> unread.addLast(arrival.raw)
                Arrival.Reset -> unread.clear()
            }
        }
    }

    private sealed interface Arrival {
        data class Message(
            val raw: String,
        ) : Arrival

        data object Reset : Arrival
    }

    private companion object {
        const val WAKEUP_BUFFER = 8
        const val OBSERVER_BUFFER = 64
    }
}
