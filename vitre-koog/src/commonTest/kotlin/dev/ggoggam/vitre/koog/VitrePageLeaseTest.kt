package dev.ggoggam.vitre.koog

import ai.koog.agents.core.tools.ToolCallMetadata
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.koog.feature.VitrePageLease
import dev.ggoggam.vitre.koog.testing.FakePageController
import dev.ggoggam.vitre.koog.tools.EvaluateTool
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_METADATA_KEY
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_SESSION_METADATA_KEY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * "A lease the model never sees still has to behave like one it did."
 *
 * The feature's whole value is that it removes a step the model would forget — so its own mistakes
 * are, by construction, ones nobody in the transcript can see or correct. These are the endings the
 * pipeline hooks alone do not cover.
 */
class VitrePageLeaseTest {
    private class Fixture(
        scope: CoroutineScope,
    ) {
        val page = FakePageController()
        val sessions = WebViewSessions()
        val driver = PageDriver(sessions, scope, engineContext = EmptyCoroutineContext)
        val feature = VitrePageLease(driver)

        init {
            page.respond = { "\"ok\"" }
            sessions.register("main", page, "the sample gallery's WebView")
        }
    }

    private fun config(
        fixture: Fixture,
        ttl: Long,
    ) = VitrePageLease.Config().apply {
        driver = fixture.driver
        ttlMs = ttl
    }

    @Test
    fun a_run_that_ends_normally_gives_the_page_back() =
        runTest {
            val fixture = Fixture(this)
            fixture.feature.acquire("run-1", config(fixture, 30_000))
            val id = fixture.feature.leaseFor("run-1")!!

            fixture.feature.release("run-1")

            assertNull(fixture.feature.leaseFor("run-1"))
            assertFalse(fixture.driver.isLeaseActive(id), "the claim outlived the run that took it")
            // And the page is usable by anyone again, immediately rather than at the TTL.
            EvaluateTool(fixture.driver).execute(EvaluateTool.Args(script = "after()"), ToolCallMetadata.EMPTY)
            assertEquals(listOf("after()"), fixture.page.evaluatedScripts)
        }

    @Test
    fun a_run_that_is_cancelled_gives_the_page_back_too() =
        runTest {
            val fixture = Fixture(this)
            fixture.feature.acquire("run-1", config(fixture, 600_000))
            val id = fixture.feature.leaseFor("run-1")!!

            // Koog rethrows a cancellation without completing or failing the run, so neither
            // per-run hook fires. Without the closing backstop the user's WebView would stay held
            // for the whole TTL — ten minutes here — by an agent nobody is talking to any more.
            fixture.feature.releaseAll()

            assertNull(fixture.feature.leaseFor("run-1"))
            assertFalse(fixture.driver.isLeaseActive(id))
            EvaluateTool(fixture.driver).execute(EvaluateTool.Args(script = "after()"), ToolCallMetadata.EMPTY)
            assertEquals(listOf("after()"), fixture.page.evaluatedScripts)
        }

    @Test
    fun a_lease_that_expires_mid_run_stops_being_published_rather_than_poisoning_every_later_call() =
        runTest {
            val fixture = Fixture(this)
            fixture.feature.acquire("run-1", config(fixture, 1_000))
            val id = fixture.feature.leaseFor("run-1")!!

            advanceTimeBy(1_001)
            runCurrent()

            // The registry has dropped it, and so must the feature. Publishing a dead id would make
            // every remaining tool call fail with "Lease is not active … Acquire a new one" — which
            // the model cannot do, because installing this feature is what takes `acquire_lease`
            // out of its list. Falling back to unleased calls loses atomicity that is already lost.
            assertFalse(fixture.driver.isLeaseActive(id))
            assertNull(fixture.feature.leaseFor("run-1"), "an expired lease id was still being handed to tools")

            EvaluateTool(fixture.driver).execute(EvaluateTool.Args(script = "after()"), ToolCallMetadata.EMPTY)
            assertEquals(listOf("after()"), fixture.page.evaluatedScripts)
        }

    @Test
    fun one_agent_can_hold_a_lease_per_run() =
        runTest {
            val fixture = Fixture(this)
            val second = FakePageController().also { it.respond = { "\"ok\"" } }
            fixture.sessions.register("other", second, "a second tab")

            fixture.feature.acquire("run-1", config(fixture, 30_000).apply { session = "main" })
            fixture.feature.acquire("run-2", config(fixture, 30_000).apply { session = "other" })

            val first = fixture.feature.grantFor("run-1")!!
            val other = fixture.feature.grantFor("run-2")!!
            assertEquals("main", first.sessionId)
            assertEquals("other", other.sessionId)

            // Releasing one run must not disturb the other's claim.
            fixture.feature.release("run-1")
            assertFalse(fixture.driver.isLeaseActive(first.id))
            assertEquals(other.id, fixture.feature.leaseFor("run-2"))

            // And the metadata a call carries names the session, so a call aimed elsewhere can drop it.
            val metadata =
                ToolCallMetadata.of(
                    VITRE_LEASE_METADATA_KEY to other.id,
                    VITRE_LEASE_SESSION_METADATA_KEY to other.sessionId,
                )
            EvaluateTool(fixture.driver).execute(
                EvaluateTool.Args(script = "elsewhere()", session = "other"),
                metadata,
            )
            assertEquals(listOf("elsewhere()"), second.evaluatedScripts)

            fixture.feature.releaseAll()
        }
}
