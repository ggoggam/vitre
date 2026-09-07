package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.NoSuchSessionException
import dev.ggoggam.vitre.agent.session.SessionLeases
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.net.ExchangeOutcome
import dev.ggoggam.vitre.core.net.NetworkExchange
import dev.ggoggam.vitre.core.net.NetworkLog
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "The tap has existed since the lane pool did; this is the first thing that lets an agent read it."
 *
 * The rules under test are the ones a model would otherwise have to be told twice — once per adapter
 * — and the ones that turn a correct empty answer into a wrong conclusion about the world.
 */
class PageDriverNetworkTest {
    private fun exchange(
        id: Long,
        url: String,
        body: String? = null,
        truncated: Boolean = false,
        status: Int = 200,
        error: String? = null,
    ) = NetworkExchange(
        id = id,
        method = "GET",
        url = url,
        outcome = if (error == null) ExchangeOutcome.Fetched else ExchangeOutcome.Failed,
        status = status,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        contentType = "application/json",
        body = body,
        bodyTruncated = truncated,
        durationMs = 34,
        error = error,
    )

    private class Fixture(
        scope: CoroutineScope,
        val log: NetworkLog? = NetworkLog(),
    ) {
        val sessions = WebViewSessions()
        val driver = PageDriver(sessions, SessionLeases(scope), engineContext = EmptyCoroutineContext)

        init {
            sessions.register("main", SilentController(), "the shopping tab", network = log)
        }
    }

    @Test
    fun a_session_with_no_capture_wired_says_so_rather_than_answering_nothing() =
        runTest {
            val fixture = Fixture(this, log = null)

            val failure = assertFailsWith<PageDriverException> { fixture.driver.readNetwork() }

            // "No capture here" and "nothing was captured" call for different next moves, and a
            // model told the second when the first is true will keep asking the same question.
            assertTrue("no network capture" in failure.message, failure.message)
            assertTrue("snapshot" in failure.message, failure.message)
        }

    @Test
    fun the_matches_come_back_newest_first_with_the_count_that_was_cut_from() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/api/search?q=a"))
            fixture.log.record(exchange(2, "https://shop.test/static/app.js"))
            fixture.log.record(exchange(3, "https://shop.test/api/search?q=b"))

            val read = fixture.driver.readNetwork(urlContains = "api/search", limit = 1)

            assertEquals("https://shop.test/api/search?q=b", read.exchanges.single().url)
            assertEquals(2, read.matched)
            assertEquals(3, read.retained)
            assertEquals("api/search", read.filter)
        }

    @Test
    fun a_body_the_read_had_to_cut_is_labelled_where_the_body_is() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/api/search", body = "{\"items\":[" + "x".repeat(500)))

            val read = fixture.driver.readNetwork(maxBodyChars = 20)
            val only = read.exchanges.single()

            assertEquals(20, only.body?.length)
            assertTrue(only.bodyTruncated)

            // In the reply itself, not in a header the model can skim past: a cut JSON body read as
            // complete is how an agent reports four results out of forty and nothing downstream can
            // tell it did.
            val rendered = PageToolReplies.network(read)
            assertTrue("TRUNCATED" in rendered, rendered)
        }

    @Test
    fun a_body_the_capture_policy_had_already_cut_stays_cut_after_this_read_fits_it() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/api/search", body = "short", truncated = true))

            val only =
                fixture.driver
                    .readNetwork(maxBodyChars = 5_000)
                    .exchanges
                    .single()

            // Deriving the flag from "did *this* read cut it?" would clear a truncation that
            // happened two caps upstream, and report a partial response as a whole one.
            assertTrue(only.bodyTruncated)
        }

    @Test
    fun asking_for_no_bodies_withholds_them_rather_than_claiming_there_were_none() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/api/search", body = "{\"items\":[]}"))

            val only =
                fixture.driver
                    .readNetwork(maxBodyChars = 0)
                    .exchanges
                    .single()

            assertNull(only.body)
            assertTrue(only.hasBody)
            assertFalse(only.bodyTruncated)

            // The distinction has to survive into the words, because the two look identical to a
            // model and mean opposite things about the API it is investigating.
            val rendered = PageToolReplies.network(fixture.driver.readNetwork(maxBodyChars = 0))
            assertTrue("not shown" in rendered, rendered)
        }

    @Test
    fun an_exchange_that_never_had_a_body_is_reported_as_such() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/image.png", body = null))

            val only =
                fixture.driver
                    .readNetwork()
                    .exchanges
                    .single()

            assertFalse(only.hasBody)
            assertTrue("none captured" in PageToolReplies.network(fixture.driver.readNetwork()))
        }

    @Test
    fun an_empty_answer_is_never_left_to_read_as_proof_the_request_never_happened() =
        runTest {
            val fixture = Fixture(this)
            fixture.log!!.record(exchange(1, "https://shop.test/static/app.js"))

            val rendered = PageToolReplies.network(fixture.driver.readNetwork(urlContains = "api/search"))

            // The whole reason this tool needs prose around it. Every other tool fails loudly when
            // it is wrong; this one succeeds with an empty list, and on iOS an empty list is the
            // *expected* answer for a request that certainly happened.
            assertTrue("not proof" in rendered.lowercase(), rendered)
            assertTrue("iOS" in rendered, rendered)
            assertTrue("snapshot" in rendered, rendered)
        }

    @Test
    fun a_full_buffer_admits_that_older_traffic_has_been_dropped() =
        runTest {
            val fixture = Fixture(this, log = NetworkLog(maxExchanges = 2))
            repeat(4) { fixture.log!!.record(exchange(it.toLong(), "https://shop.test/api/item/$it")) }

            val rendered = PageToolReplies.network(fixture.driver.readNetwork())

            // A caller looking for a request made five minutes ago has to be told the buffer wrapped
            // rather than left to conclude the request was never made.
            assertTrue("dropped" in rendered, rendered)
        }

    @Test
    fun the_limits_are_clamped_here_so_neither_adapter_has_to_do_it() =
        runTest {
            val fixture = Fixture(this)
            repeat(300) { fixture.log!!.record(exchange(it.toLong(), "https://shop.test/api/item/$it", body = "x")) }

            // A model that asks for everything gets a bounded answer rather than a context-eating
            // one, and the bound is the same through MCP as it is through Koog.
            assertEquals(
                PageDriver.MAX_NETWORK_LIMIT,
                fixture.driver
                    .readNetwork(limit = 10_000)
                    .exchanges.size,
            )
            assertEquals(
                1,
                fixture.driver
                    .readNetwork(limit = -5)
                    .exchanges.size,
            )
        }

    @Test
    fun naming_a_session_that_does_not_exist_fails_the_way_every_other_tool_does() =
        runTest {
            val fixture = Fixture(this)

            val failure =
                assertFailsWith<NoSuchSessionException> { fixture.driver.readNetwork(session = "nope") }
            assertTrue("main" in (failure.message ?: ""), "${failure.message}")
        }

    /**
     * A controller that does nothing, because nothing here touches the page.
     *
     * That is the assertion, not a shortcut: `readNetwork` reads a buffer, so a test of it that
     * needed a working WebView would mean the buffer had been wired to the page by mistake.
     */
    private class SilentController : WebViewController {
        private val inbox = WebViewInbox()

        override val bridge: WebViewBridge = DefaultWebViewBridge(inbox) { evaluateJs(it) }

        override suspend fun navigate(url: String) = error("readNetwork must not touch the page")

        override suspend fun loadHtml(
            html: String,
            baseUrl: String?,
        ) = error("readNetwork must not touch the page")

        override suspend fun evaluateJs(script: String): String = error("readNetwork must not touch the page")

        override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = error("readNetwork must not take the lock")

        override fun close() = Unit
    }
}
