package dev.ggoggam.vitre.mcp

import dev.ggoggam.vitre.mcp.session.WebViewSessions
import dev.ggoggam.vitre.mcp.testing.FakePageController
import dev.ggoggam.vitre.mcp.testing.McpTestClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "The page I read is the page I waited for" — across separate tool calls.
 *
 * This is the one guarantee the WebView's own ordering cannot supply. Each MCP call is a separate
 * caller on a coroutine of its own, so a sequence of them is exactly as interleavable as two
 * unrelated clients, and the interleaving does not corrupt anything — it just answers a different
 * question than the one asked.
 */
class SessionLeaseTest {
    private fun leaseIdOf(text: String): String =
        Regex("lease_[0-9a-f]+")
            .find(text)
            ?.value
            ?: error("no lease id in: $text")

    @Test
    fun a_lease_keeps_another_callers_tool_call_out_until_it_is_released() =
        runTest {
            val page = FakePageController()
            page.respond = { "\"ok\"" }
            val sessions = WebViewSessions().apply { register("main", page) }
            val client = McpTestClient(McpServer(sessions, this, engineContext = EmptyCoroutineContext))

            val acquired = client.callTool("acquire_lease")
            assertFalse(acquired.isError, acquired.text)
            val lease = acquired.structured!!["lease"]!!.jsonPrimitive.content

            // A second client — no lease quoted — is not privileged out; it simply queues, which is
            // the correct reading of "somebody else is using the page right now".
            val intruder =
                launch {
                    client.callTool("evaluate", buildJsonObject { put("script", "theirs()") })
                }
            runCurrent()

            client.callTool(
                "evaluate",
                buildJsonObject {
                    put("script", "mine()")
                    put("lease", lease)
                },
            )
            runCurrent()

            assertEquals(
                listOf("mine()"),
                page.evaluatedScripts,
                "an unleased call ran in the middle of a leased sequence",
            )

            client.callTool("release_lease", buildJsonObject { put("lease", lease) })
            intruder.join()

            assertEquals(listOf("mine()", "theirs()"), page.evaluatedScripts)
        }

    @Test
    fun a_released_lease_cannot_be_used_again_and_says_why() =
        runTest {
            val page = FakePageController()
            val sessions = WebViewSessions().apply { register("main", page) }
            val client = McpTestClient(McpServer(sessions, this, engineContext = EmptyCoroutineContext))

            val lease = leaseIdOf(client.callTool("acquire_lease").text)
            client.callTool("release_lease", buildJsonObject { put("lease", lease) })

            val afterwards =
                client.callTool(
                    "evaluate",
                    buildJsonObject {
                        put("script", "1")
                        put("lease", lease)
                    },
                )

            assertTrue(afterwards.isError)
            // Expiry and release land on the same message on purpose: from the client's side they
            // are the same situation, and the fix — acquire a new one — is the same too.
            assertTrue("expired" in afterwards.text || "released" in afterwards.text, afterwards.text)
        }

    @Test
    fun a_lease_expires_on_its_own_so_a_client_that_stops_cannot_wedge_the_page() =
        runTest {
            val page = FakePageController()
            page.respond = { "\"ok\"" }
            val sessions = WebViewSessions().apply { register("main", page) }
            val client = McpTestClient(McpServer(sessions, this, engineContext = EmptyCoroutineContext))

            client.callTool(
                "acquire_lease",
                buildJsonObject { put("ttl_ms", 1_000) },
            )

            // The client crashes here — no release_lease is ever sent. Without the TTL the WebView
            // stays held by nobody, and the app's own UI is locked out for as long as it runs.
            testScheduler.advanceTimeBy(2_000)
            runCurrent()

            val afterExpiry = client.callTool("evaluate", buildJsonObject { put("script", "recovered()") })

            assertFalse(afterExpiry.isError, afterExpiry.text)
            assertEquals(listOf("recovered()"), page.evaluatedScripts)
        }

    @Test
    fun a_lease_is_only_valid_on_the_session_it_was_taken_on() =
        runTest {
            val first = FakePageController()
            val second = FakePageController()
            val sessions =
                WebViewSessions().apply {
                    register("first", first)
                    register("second", second)
                }
            val client = McpTestClient(McpServer(sessions, this, engineContext = EmptyCoroutineContext))

            val lease =
                client
                    .callTool("acquire_lease", buildJsonObject { put("session", "first") })
                    .structured!!["lease"]!!
                    .jsonPrimitive.content

            val misused =
                client.callTool(
                    "evaluate",
                    buildJsonObject {
                        put("script", "1")
                        put("session", "second")
                        put("lease", lease)
                    },
                )

            // Honouring it would let a claim on one WebView bypass the lock on another, which is
            // precisely the guarantee the claim exists to provide.
            assertTrue(misused.isError)
            assertTrue("first" in misused.text, misused.text)
        }
}
