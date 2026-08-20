package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import dev.ggoggam.vitre.core.webview.AsyncScript
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TypedBridgeTest {
    @Test
    fun await_message_by_type_returns_both_the_raw_string_and_the_decoded_envelope() =
        runTest {
            val controller = FakeWebViewController()

            // Decoding drops keys BridgeMessage does not declare, so a caller that only got the
            // envelope back would silently lose `extra` — hence both halves are returned.
            val raw = """{"id":"7","type":"ready","payload":{"ok":true},"extra":"kept"}"""
            controller.simulatePageMessage(raw)

            val received = controller.bridge.awaitMessage("ready")

            assertEquals(raw, received.raw, "the raw string was not handed back byte-for-byte")
            assertEquals("7", received.envelope.id)
            assertEquals("ready", received.envelope.type)
            assertEquals(
                "true",
                received.envelope.payload.jsonObject["ok"]
                    ?.toString(),
            )
        }

    @Test
    fun await_message_by_type_skips_other_types_and_malformed_json() =
        runTest {
            val controller = FakeWebViewController()

            // A driven page posts whatever it likes on the same channel; neither an unrelated type
            // nor a string that is not an envelope at all may end the wait.
            controller.simulatePageMessage("not-json-at-all")
            controller.simulatePageMessage("""{"id":"1","type":"loading"}""")
            val wanted = """{"id":"2","type":"ready"}"""
            controller.simulatePageMessage(wanted)

            assertEquals(wanted, controller.bridge.awaitMessage("ready").raw)
        }

    @Test
    fun await_message_by_type_times_out_with_a_plain_exception() =
        runTest {
            val controller = FakeWebViewController()

            // A self-imposed bound expiring is the page failing to answer, not the caller giving
            // up: raising a CancellationException here would cancel the caller's scope and be read
            // by WorkflowEngine as "the collector cancelled us".
            val failure: Throwable =
                assertFailsWith<BridgeTimeoutException> {
                    controller.bridge.awaitMessage("ready", timeoutMs = 1_000)
                }

            assertFalse(failure is CancellationException, "the timeout escaped as a cancellation")
            assertTrue("ready" in failure.message.orEmpty(), "the message does not name the type: ${failure.message}")
            assertTrue("1000" in failure.message.orEmpty(), "the message does not name the bound: ${failure.message}")
        }

    @Test
    fun post_dispatches_a_well_formed_envelope_to_the_page() =
        runTest {
            val controller = FakeWebViewController()

            val id =
                controller.bridge.post(
                    type = "config",
                    payload = buildJsonObject { put("locale", JsonPrimitive("en-GB")) },
                )

            // The page only ever sees the dispatch script, so that is where the envelope has to be
            // correct — the returned id included, since a reply is expected to name it.
            val script = controller.evaluatedScripts.single()
            val expected = DefaultWebViewBridge.dispatchScript("""{"id":"$id","type":"config","payload":{"locale":"en-GB"}}""")
            assertEquals(expected, script, "the page was handed a different envelope than post reported")
            // Absent by default, so a one-way message stays byte-compatible with pages that predate the field.
            assertFalse("replyTo" in script, "replyTo was written onto a message that answers nothing: $script")
        }

    @Test
    fun request_correlates_the_reply_by_reply_to() =
        runTest {
            val controller = FakeWebViewController()
            var received: ReceivedMessage? = null

            val requesting = launch { received = controller.bridge.request("config") }
            runCurrent()
            assertNull(received, "the request completed before the page replied")

            val id = idOfLastPost(controller)
            val reply = """{"id":"reply-1","type":"ack","replyTo":"$id","payload":{"seen":true}}"""
            controller.simulatePageMessage(reply)
            requesting.join()

            assertEquals(reply, received?.raw)
            assertEquals(id, received?.envelope?.replyTo, "the reply was matched on something other than replyTo")
        }

    @Test
    fun request_matches_a_reply_that_arrived_before_the_await_began() =
        runTest {
            val controller = FakeWebViewController()

            // The normal case, not the unlucky one: a synchronous page handler replies while the
            // request is still between its post and its wait. The inbox buffering unread messages
            // is exactly what makes post-then-await safe rather than a race.
            controller.nextEvalResult = { script ->
                controller.simulatePageMessage(
                    """{"id":"reply-1","type":"ack","replyTo":"${idIn(script)}","payload":{"seen":true}}""",
                )
                "null"
            }

            val received = controller.bridge.request("config")

            assertEquals("ack", received.envelope.type, "the reply posted during the dispatch was lost")
        }

    @Test
    fun request_ignores_a_reply_addressed_to_a_different_request() =
        runTest {
            val controller = FakeWebViewController()
            var received: ReceivedMessage? = null

            val requesting = launch { received = controller.bridge.request("config") }
            runCurrent()

            // A stale reply to some earlier request is still sitting unread; taking it would answer
            // this question with the previous one's answer.
            controller.simulatePageMessage("""{"id":"reply-0","type":"ack","replyTo":"msg#deadbeef","payload":{"stale":true}}""")
            runCurrent()
            assertNull(received, "a reply addressed to another request was accepted")

            val id = idOfLastPost(controller)
            controller.simulatePageMessage("""{"id":"reply-1","type":"ack","replyTo":"$id"}""")
            requesting.join()

            assertEquals("reply-1", received?.envelope?.id)
        }

    @Test
    fun two_concurrent_requests_each_take_their_own_reply() =
        runTest {
            val controller = FakeWebViewController()
            val answers = mutableMapOf<String, String>()

            // Correlation, not arrival order, is what decides who gets which answer — the reason
            // ids exist at all. The replies come back deliberately reversed.
            val first = launch { answers["first"] = answeredFor(controller.bridge.request("config")) }
            runCurrent()
            val firstId = idOfLastPost(controller)
            val second = launch { answers["second"] = answeredFor(controller.bridge.request("config")) }
            runCurrent()
            val secondId = idOfLastPost(controller)

            controller.simulatePageMessage("""{"id":"r2","type":"ack","replyTo":"$secondId","payload":{"for":"second"}}""")
            controller.simulatePageMessage("""{"id":"r1","type":"ack","replyTo":"$firstId","payload":{"for":"first"}}""")
            first.join()
            second.join()

            assertEquals(mapOf("first" to "first", "second" to "second"), answers, "the requests crossed their replies")
        }

    @Test
    fun generated_ids_stay_out_of_the_script_result_namespace() =
        runTest {
            val controller = FakeWebViewController()

            // Two namespaces share the firehose. Keeping `msg#` disjoint from `script:result#<cid>`
            // is what lets a reader tell page traffic from settle-plane plumbing at a glance.
            val ids = List(8) { controller.bridge.post("config") }

            assertEquals(ids.toSet().size, ids.size, "generated ids repeated: $ids")
            for (id in ids) {
                assertTrue(id.startsWith("msg#"), "id is outside the msg# namespace: $id")
                assertFalse(AsyncScript.RESULT_TYPE in id, "id collides with the settle plane: $id")
            }
        }

    @Test
    fun posting_the_script_result_type_is_refused() =
        runTest {
            val controller = FakeWebViewController()

            // ScriptResults claims every script:result message before the inbox sees it, so such a
            // message would vanish and the caller would learn about it as a bare timeout.
            assertFailsWith<IllegalArgumentException> {
                controller.bridge.post(AsyncScript.RESULT_TYPE)
            }
            assertFailsWith<IllegalArgumentException> {
                controller.bridge.request(AsyncScript.RESULT_TYPE)
            }
            assertTrue(controller.evaluatedScripts.isEmpty(), "a reserved-type message reached the page anyway")
        }

    /** Which request the page says this reply answers, as the fixtures below label it. */
    private fun answeredFor(received: ReceivedMessage): String =
        received.envelope.payload.jsonObject
            .getValue("for")
            .jsonPrimitive.content

    /** The id of the envelope in the most recent dispatch script the fake recorded. */
    private fun idOfLastPost(controller: FakeWebViewController): String = idIn(controller.evaluatedScripts.last())

    /** Pulls `"id":"…"` back out of a dispatch script, whose JSON payload is JS-string-escaped. */
    private fun idIn(script: String): String = ID_IN_SCRIPT.find(script)?.groupValues?.get(1) ?: error("no envelope id in: $script")

    private companion object {
        val ID_IN_SCRIPT = Regex("""\\"id\\":\\"([^\\]+)\\"""")
    }
}
