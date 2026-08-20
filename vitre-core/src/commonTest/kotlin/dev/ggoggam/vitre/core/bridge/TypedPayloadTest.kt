package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The typed overloads are serialization and nothing else, so what these assert is that they change
 * no protocol: the envelope a typed call puts on the wire is the one the hand-built call put there,
 * and the correlation, the id namespace and the reserved-type refusal all still apply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedPayloadTest {
    @Serializable
    data class Ack(
        val seen: Boolean,
        val note: String? = null,
    )

    @Serializable
    data class Config(
        val theme: String,
        val retries: Int,
    )

    @Test
    fun request_serializes_the_payload_and_decodes_the_reply() =
        runTest {
            val controller = FakeWebViewController()
            var answer: Config? = null

            val requesting =
                launch { answer = controller.bridge.request<Ack, Config>("config", Ack(seen = true)) }
            runCurrent()

            // What went out carries the serialized T, not a stringified blob assembled by the caller.
            val sent = controller.evaluatedScripts.last()
            assertTrue("""\"seen\":true""" in sent, "the typed payload was not serialized into the envelope: $sent")

            controller.simulatePageMessage(
                """{"id":"reply-1","type":"ack","replyTo":"${idOfLastPost(controller)}","payload":{"theme":"dark","retries":3}}""",
            )
            requesting.join()

            assertEquals(Config(theme = "dark", retries = 3), answer)
        }

    /** The typed round trip returns the reply's *payload*; the envelope is plumbing it resolved. */
    @Test
    fun request_returns_the_payload_not_the_envelope() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { script ->
                controller.simulatePageMessage(
                    """{"id":"reply-1","type":"ack","replyTo":"${idIn(script)}","payload":{"theme":"light","retries":0}}""",
                )
                "null"
            }

            assertEquals(
                Config(theme = "light", retries = 0),
                controller.bridge.request<Ack, Config>("config", Ack(seen = true)),
            )
        }

    /**
     * A typed call and the hand-built call it replaces must be byte-identical on the wire — that is
     * the whole claim these overloads make.
     */
    @Test
    fun a_typed_post_and_a_hand_built_post_put_the_same_envelope_on_the_wire() =
        runTest {
            val typed = FakeWebViewController()
            typed.bridge.post("ack", Ack(seen = true), id = "ack-1")

            val handBuilt = FakeWebViewController()
            handBuilt.bridge.postToWebView("""{"id":"ack-1","type":"ack","payload":{"seen":true}}""")

            assertEquals(handBuilt.evaluatedScripts.last(), typed.evaluatedScripts.last())
        }

    /** An absent nullable field stays off the wire, so a typed payload is not fatter than the raw one. */
    @Test
    fun an_omitted_optional_field_is_not_written() =
        runTest {
            val controller = FakeWebViewController()
            controller.bridge.post("ack", Ack(seen = true), id = "ack-1")

            assertTrue("note" !in controller.evaluatedScripts.last(), "a null optional was written to the wire")
        }

    @Test
    fun await_payload_decodes_the_matched_message() =
        runTest {
            val controller = FakeWebViewController()
            controller.simulatePageMessage("""{"id":"1","type":"loading","payload":{"theme":"x","retries":9}}""")
            controller.simulatePageMessage("""{"id":"2","type":"ready","payload":{"theme":"dark","retries":3}}""")

            assertEquals(Config("dark", 3), controller.bridge.awaitPayload<Config>("ready"))
        }

    /** `ignoreUnknownKeys`, so a page that adds a field does not break a class that predates it. */
    @Test
    fun a_payload_field_the_class_does_not_declare_is_ignored() =
        runTest {
            val controller = FakeWebViewController()
            controller.simulatePageMessage(
                """{"id":"1","type":"ready","payload":{"theme":"dark","retries":3,"added_later":true}}""",
            )

            assertEquals(Config("dark", 3), controller.bridge.awaitPayload<Config>("ready"))
        }

    /** The reserved-type check lives in the untyped `post` the typed one delegates to. */
    @Test
    fun a_typed_post_still_refuses_the_reserved_settle_type() =
        runTest {
            val controller = FakeWebViewController()

            assertFailsWith<IllegalArgumentException> {
                controller.bridge.post("script:result", Ack(seen = true), id = "ack-1")
            }
            assertTrue(controller.evaluatedScripts.isEmpty(), "the refused message was dispatched anyway")
        }

    /** Typed does not mean lenient: a reply shaped wrong is an error, not a half-filled object. */
    @Test
    fun a_reply_that_is_not_an_r_fails_rather_than_decoding_partially() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { script ->
                controller.simulatePageMessage(
                    """{"id":"reply-1","type":"ack","replyTo":"${idIn(script)}","payload":{"theme":"dark"}}""",
                )
                "null"
            }

            assertFailsWith<Exception> { controller.bridge.request<Ack, Config>("config", Ack(seen = true)) }
        }

    @Test
    fun a_typed_request_still_ignores_a_reply_to_a_different_request() =
        runTest {
            val controller = FakeWebViewController()
            var answer: Config? = null

            val requesting =
                launch { answer = controller.bridge.request<Ack, Config>("config", Ack(seen = true)) }
            runCurrent()

            controller.simulatePageMessage(
                """{"id":"reply-1","type":"ack","replyTo":"msg#somethingelse","payload":{"theme":"wrong","retries":1}}""",
            )
            runCurrent()
            assertNull(answer, "a reply addressed to another request was accepted")

            controller.simulatePageMessage(
                """{"id":"reply-2","type":"ack","replyTo":"${idOfLastPost(controller)}","payload":{"theme":"right","retries":2}}""",
            )
            requesting.join()
            assertEquals(Config("right", 2), answer)
        }

    private fun idOfLastPost(controller: FakeWebViewController): String = idIn(controller.evaluatedScripts.last())

    /** Pulls `"id":"…"` back out of a dispatch script, whose JSON payload is JS-string-escaped. */
    private fun idIn(script: String): String = ID_IN_SCRIPT.find(script)?.groupValues?.get(1) ?: error("no envelope id in: $script")

    private companion object {
        val ID_IN_SCRIPT = Regex("""\\"id\\":\\"([^\\]+)\\"""")
    }
}
