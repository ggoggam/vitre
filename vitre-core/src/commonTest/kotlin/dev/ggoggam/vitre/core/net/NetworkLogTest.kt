package dev.ggoggam.vitre.core.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "The tap is fire-and-forget; this is the part that remembers, and it is bounded." */
class NetworkLogTest {
    private fun exchange(
        id: Long,
        url: String,
        body: String? = null,
        truncated: Boolean = false,
    ) = NetworkExchange(
        id = id,
        method = "GET",
        url = url,
        outcome = ExchangeOutcome.Fetched,
        status = 200,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        contentType = "application/json",
        body = body,
        bodyTruncated = truncated,
        durationMs = 12,
    )

    @Test
    fun the_newest_exchange_is_the_first_one_read() {
        val log = NetworkLog()
        log.record(exchange(1, "https://shop.test/api/search?q=a"))
        log.record(exchange(2, "https://shop.test/api/search?q=b"))

        // Newest first is not a preference. A caller asks about the request it just caused, and a
        // limit applied to an oldest-first list drops exactly that one.
        assertEquals(
            listOf("https://shop.test/api/search?q=b", "https://shop.test/api/search?q=a"),
            log.query().exchanges.map { it.url },
        )
    }

    @Test
    fun the_filter_is_a_substring_of_the_whole_url_whatever_its_case() {
        val log = NetworkLog()
        log.record(exchange(1, "https://shop.test/API/Search?q=keyboard"))
        log.record(exchange(2, "https://shop.test/static/app.js"))

        val found = log.query(urlContains = "api/search")

        assertEquals(1, found.matched)
        assertEquals(2, found.retained)
        assertEquals("https://shop.test/API/Search?q=keyboard", found.exchanges.single().url)
    }

    @Test
    fun a_limit_cuts_the_answer_without_hiding_how_much_was_cut() {
        val log = NetworkLog()
        repeat(5) { log.record(exchange(it.toLong(), "https://shop.test/api/item/$it")) }

        val found = log.query(limit = 2)

        // "3 of 200" and "3 of 3" are different answers, and a caller that cannot tell them apart
        // reads a sample as an inventory.
        assertEquals(2, found.exchanges.size)
        assertEquals(5, found.matched)
    }

    @Test
    fun the_oldest_exchange_is_the_one_dropped_when_the_count_bound_bites() {
        val log = NetworkLog(maxExchanges = 3)
        repeat(5) { log.record(exchange(it.toLong(), "https://shop.test/$it")) }

        assertEquals(3, log.size)
        assertEquals(
            listOf("https://shop.test/4", "https://shop.test/3", "https://shop.test/2"),
            log.query().exchanges.map { it.url },
        )
    }

    @Test
    fun the_body_bound_evicts_too_because_a_count_is_not_a_memory_bound() {
        // The case that makes the second bound necessary: three exchanges is well within a count
        // bound of a hundred, and at a quarter-megabyte body each it is 750 KB held on a phone.
        val log = NetworkLog(maxExchanges = 100, maxBodyChars = 20)
        log.record(exchange(1, "https://shop.test/a", body = "a".repeat(10)))
        log.record(exchange(2, "https://shop.test/b", body = "b".repeat(10)))
        log.record(exchange(3, "https://shop.test/c", body = "c".repeat(10)))

        assertEquals(
            listOf("https://shop.test/c", "https://shop.test/b"),
            log.query().exchanges.map { it.url },
        )
    }

    @Test
    fun one_oversized_body_is_cut_rather_than_evicting_everything_around_it() {
        val log = NetworkLog(maxExchanges = 10, maxBodyChars = 8)
        log.record(exchange(1, "https://shop.test/huge", body = "x".repeat(500)))

        val held = log.query().exchanges.single()

        // Evicting to make room for a body that cannot fit whatever is evicted would empty the log
        // and still not fit. Cutting it keeps the exchange readable, and the flag keeps it honest.
        assertEquals(8, held.body?.length)
        assertTrue(held.bodyTruncated)
    }

    @Test
    fun a_body_that_arrived_truncated_stays_truncated_after_it_fits() {
        val already = exchange(1, "https://shop.test/a", body = "short", truncated = true)

        val kept = already.withBodyCappedAt(1_000)

        // The capture policy cut this one long before the log saw it. Re-deriving the flag from
        // "did I cut it?" would clear it and report a partial response as a whole one.
        assertTrue(kept.bodyTruncated)
        assertEquals("short", kept.body)
    }

    @Test
    fun an_exchange_with_no_body_survives_the_cap_unchanged() {
        val bodiless = exchange(1, "https://shop.test/a", body = null)

        val kept = bodiless.withBodyCappedAt(0)

        assertNull(kept.body)
        assertFalse(kept.bodyTruncated)
    }

    @Test
    fun what_a_tap_publishes_after_retention_starts_is_what_the_log_holds() =
        runTest {
            val tap = FakeTap()
            // `backgroundScope` because the collector never completes on its own — a tap is a
            // firehose, not a finite flow — and `runTest` waits for the test body's own children.
            val log = tap.retainIn(backgroundScope)
            runCurrent()

            tap.publish(exchange(1, "https://shop.test/api/search"))
            tap.publish(exchange(2, "https://shop.test/api/stock"))
            runCurrent()

            assertEquals(2, log.size)
            assertEquals(
                "https://shop.test/api/search",
                log
                    .query(urlContains = "search")
                    .exchanges
                    .single()
                    .url,
            )
        }

    @Test
    fun traffic_from_before_retention_started_is_gone_rather_than_replayed() =
        runTest {
            val tap = FakeTap()
            tap.publish(exchange(1, "https://shop.test/api/search"))

            val log = tap.retainIn(backgroundScope)
            runCurrent()

            // The upstream `SharedFlow` has no replay, so there is nothing to back-fill and no
            // amount of waiting produces it. A host that attaches a log after the page has loaded
            // gets an empty one; this is why `retainIn` belongs where the pool is built.
            assertEquals(0, log.size)
        }

    /** A tap under our control, so the test can decide exactly when an exchange is published. */
    private class FakeTap : NetworkTap {
        private val published =
            MutableSharedFlow<NetworkExchange>(
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val exchanges: SharedFlow<NetworkExchange> get() = published.asSharedFlow()

        fun publish(exchange: NetworkExchange) {
            published.tryEmit(exchange)
        }
    }
}
