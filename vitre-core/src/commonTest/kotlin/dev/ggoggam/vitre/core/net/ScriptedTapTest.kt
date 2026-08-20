package dev.ggoggam.vitre.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tap iOS is left with once there is nothing to intercept.
 *
 * The reports come from a page, which is to say from something that can be wrong, truncated, or
 * hostile. The rule these tests hold to is that a bad report costs its own exchange and nothing
 * else — a tap that throws would take down the lane it was observing.
 */
class ScriptedTapTest {
    private val policy = InterceptionPolicy()

    @Test
    fun `reads a json api report the way an extraction would want it`() {
        val exchange =
            scriptExchange(
                raw =
                    """
                    {"method":"get","url":"https://api.nordicparts.test/items?q=switch","status":200,
                     "contentType":"application/json","body":"{\"items\":[]}","bodyTruncated":false,
                     "durationMs":42,"responseHeaders":{"content-type":"application/json"}}
                    """.trimIndent(),
                id = 7,
                policy = policy,
            )

        assertEquals(7, exchange?.id)
        assertEquals("GET", exchange?.method)
        assertEquals(ExchangeOutcome.Fetched, exchange?.outcome)
        assertEquals(200, exchange?.status)
        assertEquals("""{"items":[]}""", exchange?.body)
        assertEquals("api.nordicparts.test", exchange?.host)
        assertEquals(42, exchange?.durationMs)
    }

    @Test
    fun `a failed fetch is reported as failed rather than as a zero status`() {
        val exchange =
            scriptExchange(
                raw = """{"method":"GET","url":"https://blocked.test/x","status":0,"error":"Load failed"}""",
                id = 1,
                policy = policy,
            )

        assertEquals(ExchangeOutcome.Failed, exchange?.outcome)
        assertEquals("Load failed", exchange?.error)
    }

    @Test
    fun `survives a report that is not json`() {
        assertNull(scriptExchange("not json at all", id = 1, policy = policy))
    }

    @Test
    fun `survives a report missing everything but a url`() {
        val exchange = scriptExchange("""{"url":"https://thin.test/"}""", id = 1, policy = policy)

        assertEquals("GET", exchange?.method)
        assertEquals(0, exchange?.status)
        assertNull(exchange?.body)
    }

    @Test
    fun `drops a report with no url that nothing could be attributed to`() {
        assertNull(scriptExchange("""{"status":200}""", id = 1, policy = policy))
    }

    @Test
    fun `honours the policy's body cap even when the page ignored it`() {
        val oversized = "x".repeat(64)
        val capped = InterceptionPolicy(maxCapturedBodyBytes = 16)
        val exchange =
            scriptExchange(
                raw = """{"url":"https://big.test/","status":200,"body":"$oversized"}""",
                id = 1,
                policy = capped,
            )

        assertEquals(16, exchange?.body?.length)
        assertTrue(exchange?.bodyTruncated == true)
    }

    @Test
    fun `drops the body entirely when the policy says not to capture one`() {
        val exchange =
            scriptExchange(
                raw = """{"url":"https://big.test/","status":200,"body":"secret"}""",
                id = 1,
                policy = InterceptionPolicy(captureBodies = false),
            )

        assertNull(exchange?.body)
    }

    @Test
    fun `the injected script rewrites fixture urls only where the document is a fixture`() {
        val script = ScriptedTap.script(maxBodyBytes = 1024, captureBodies = true)

        // The rewrite is gated on the document's own protocol. Without that gate the same script
        // would retarget a real site's API calls onto a scheme with nothing behind it.
        assertTrue(script.contains("location.protocol === '${FixtureScheme.SCHEME}:'"))
        assertTrue(script.contains("inFixture && text.indexOf(HTTPS) === 0"))
        assertTrue(script.contains("messageHandlers.${ScriptedTap.HANDLER}"))
        assertTrue(script.contains("var MAX_BODY = 1024;"))
    }
}
