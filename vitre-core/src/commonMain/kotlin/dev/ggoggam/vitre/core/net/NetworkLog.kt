package dev.ggoggam.vitre.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A bounded, queryable history of what a [NetworkTap] saw.
 *
 * ## Why this has to exist
 *
 * A [NetworkTap] is a `SharedFlow` with **no replay**. That is right for what it was built for — a
 * debug pane rendering traffic as it happens, and a recorder that must never stall somebody's
 * resource load — but it means the tap is *fire-and-forget*: an exchange nobody was collecting at
 * the instant it was published is gone. Anything that asks "what did that page fetch?" after the
 * fact, which is every agent tool and most of the interesting host code, has nothing to ask.
 *
 * So this is the retention the tap deliberately does not have. It subscribes once, keeps the last
 * so-many exchanges, and answers questions about them.
 *
 * ## What it can and cannot contain
 *
 * **It holds what was published after it started subscribing, and nothing before.** Replay is zero
 * upstream, so there is no backfill to be had — a log attached after a page has already loaded is
 * empty and correct. Attach it when the pool is built, not when the first question is asked.
 *
 * The *coverage* question — which requests reach a tap at all — is the platform's rather than this
 * class's, and the two platforms answer very differently. See [NetworkTap] and `ScriptedTap`:
 * Android and desktop see everything from below the page, iOS sees only what the page's own script
 * asked for.
 *
 * ## Eviction
 *
 * Two bounds, both enforced on every record, oldest evicted first:
 *
 *  - [maxExchanges] — how many are kept at all.
 *  - [maxBodyChars] — how much *response body* text is kept across all of them together.
 *
 * The second one is not belt-and-braces. `InterceptionPolicy.maxCapturedBodyBytes` defaults to
 * 256 KiB per exchange, so a count-only bound of 200 is a licence to hold 50 MB, which on a phone
 * is not a buffer but a leak with a ceiling. A single body longer than [maxBodyChars] is truncated
 * to it rather than evicting everything else to make room, and says so through
 * [NetworkExchange.bodyTruncated].
 *
 * Characters rather than bytes because that is what survives the seam: the JVM recorder caps a
 * `ByteArray` and the iOS scripted tap caps a JavaScript string, and by the time an exchange gets
 * here it is text either way.
 */
class NetworkLog(
    val maxExchanges: Int = DEFAULT_MAX_EXCHANGES,
    val maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS,
) {
    private val retained = MutableStateFlow<List<NetworkExchange>>(emptyList())

    /**
     * Everything currently held, oldest first.
     *
     * A [StateFlow] for the same reason `WebViewSessions` uses one: updates are atomic without a
     * lock — which matters, because exchanges arrive on whichever thread the tap published on — and
     * a host that wants to render the traffic gets something observable for free.
     */
    val exchanges: StateFlow<List<NetworkExchange>> get() = retained.asStateFlow()

    /** How many exchanges are held right now. */
    val size: Int get() = retained.value.size

    /**
     * Adds one exchange, evicting oldest until both bounds hold again.
     *
     * Public so a host with a tap of its own — or a test — can feed one without a coroutine. The
     * usual caller is [retainIn].
     */
    fun record(exchange: NetworkExchange) {
        val trimmed = exchange.withBodyCappedAt(maxBodyChars)
        retained.update { held -> (held + trimmed).evicted() }
    }

    /** Forgets everything. For a host that reuses a pool across unrelated runs. */
    fun clear() {
        retained.update { emptyList() }
    }

    /**
     * The most recent exchanges whose URL contains [urlContains], newest first.
     *
     * Newest first because the caller almost always just did the thing it is asking about, and a
     * `limit` that cuts from the wrong end would drop exactly the exchange it wanted.
     *
     * The match is a case-insensitive substring of the whole URL rather than a glob or a regex.
     * Substring is what a caller can write without knowing whether the page used a relative URL, a
     * query string or a port, and it is the one form that cannot fail to compile in the caller's
     * hands and be reported as "no traffic".
     */
    fun query(
        urlContains: String? = null,
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): NetworkQuery {
        val held = retained.value
        val matched =
            if (urlContains.isNullOrEmpty()) {
                held
            } else {
                held.filter { it.url.contains(urlContains, ignoreCase = true) }
            }
        return NetworkQuery(
            exchanges = matched.asReversed().take(limit.coerceAtLeast(0)),
            matched = matched.size,
            retained = held.size,
        )
    }

    private fun List<NetworkExchange>.evicted(): List<NetworkExchange> {
        var kept = if (size > maxExchanges) subList(size - maxExchanges, size).toList() else this
        // Walked from the front because eviction is oldest-first; summing every time is affordable
        // at these sizes and is the version that cannot drift out of step with the list.
        var bodyChars = kept.sumOf { it.body?.length ?: 0 }
        var from = 0
        while (bodyChars > maxBodyChars && from < kept.size - 1) {
            bodyChars -= kept[from].body?.length ?: 0
            from++
        }
        if (from > 0) kept = kept.subList(from, kept.size).toList()
        return kept
    }

    companion object {
        /** Enough to cover a page load and the calls a few interactions make, and no more. */
        const val DEFAULT_MAX_EXCHANGES: Int = 200

        /** Roughly a quarter of a megabyte of text across the whole log, not per exchange. */
        const val DEFAULT_MAX_BODY_CHARS: Int = 256 * 1024

        const val DEFAULT_QUERY_LIMIT: Int = 20
    }
}

/** The answer to one [NetworkLog.query]: what matched, and what it was drawn from. */
data class NetworkQuery(
    /** The matches, newest first, already cut to the requested limit. */
    val exchanges: List<NetworkExchange>,
    /**
     * How many matched *before* the limit was applied.
     *
     * Reported separately so a caller can tell "there were three" from "there were three hundred and
     * you are looking at twenty of them", which is the difference between an answer and a sample.
     */
    val matched: Int,
    /** How many the log held in total when the query ran. */
    val retained: Int,
)

/**
 * Retains everything [this] publishes from now on, in a log bound by the given limits.
 *
 * ```kotlin
 * val pool = AndroidWebViewPool(context, policy = InterceptionPolicy())
 * val network = pool.tap.retainIn(scope)   // before anything navigates
 * ```
 *
 * The collector lives in [scope] and stops with it. Nothing else needs closing: a log whose scope
 * has been cancelled simply stops growing, and what it already holds stays readable, which is the
 * useful behaviour after a run has finished.
 *
 * The subscription is what starts retention, so this has to happen before the traffic does — see
 * [NetworkLog] on why there is no backfill to be had.
 */
fun NetworkTap.retainIn(
    scope: CoroutineScope,
    maxExchanges: Int = NetworkLog.DEFAULT_MAX_EXCHANGES,
    maxBodyChars: Int = NetworkLog.DEFAULT_MAX_BODY_CHARS,
): NetworkLog {
    val log = NetworkLog(maxExchanges, maxBodyChars)
    scope.launch { exchanges.collect { log.record(it) } }
    return log
}

/**
 * The same exchange with its body cut to [maxChars], marked truncated if anything was dropped.
 *
 * `bodyTruncated` is deliberately sticky: an exchange that arrived already truncated by the capture
 * policy and is then trimmed again here is still, and only, "not the whole response". Splitting that
 * into two flags would ask every reader to care which cap did it, and none of them do.
 */
internal fun NetworkExchange.withBodyCappedAt(maxChars: Int): NetworkExchange {
    val text = body ?: return this
    if (text.length <= maxChars) return this
    return copy(body = text.take(maxChars.coerceAtLeast(0)), bodyTruncated = true)
}
