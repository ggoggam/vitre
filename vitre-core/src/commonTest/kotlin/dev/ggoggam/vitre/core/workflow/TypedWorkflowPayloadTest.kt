package dev.ggoggam.vitre.core.workflow

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The typed workflow helpers, and the seam between them.
 *
 * There is no `(T) -> R` on this side and these pin down why: the block builds a value, so the
 * input can be typed at build time but the output cannot be returned to the block at all. It
 * arrives in a variable, and the typing moves to the far end.
 */
class TypedWorkflowPayloadTest {
    @Serializable
    data class Ack(
        val seen: Boolean,
    )

    @Serializable
    data class Product(
        val sku: String,
        val price: String,
    )

    @Serializable
    data class Pong(
        val subject: String,
    )

    /** The typed step must be the hand-written step — same class, same string. */
    @Test
    fun a_typed_post_message_builds_the_same_step_as_the_hand_written_envelope() {
        val typed =
            workflow("typed", "typed") {
                postMessage(type = "ack", payload = Ack(seen = true), id = "ack-1")
            }

        assertEquals(
            listOf(WorkflowStep.PostMessage("""{"id":"ack-1","type":"ack","payload":{"seen":true}}""")),
            typed.steps,
        )
    }

    /** A workflow is a value; building it twice must produce equal values, so no id is invented. */
    @Test
    fun building_the_same_typed_workflow_twice_is_equal() {
        fun build() =
            workflow("typed", "typed") {
                postMessage(type = "ack", payload = Ack(seen = true), id = "ack-1")
            }

        assertEquals(build(), build())
    }

    @Test
    fun decode_reads_a_json_variable_into_a_type() {
        val completed =
            WorkflowEvent.Completed(
                mapOf("results" to """[{"sku":"KB-1001","price":"$89.99"},{"sku":"KB-1002","price":"$79.00"}]"""),
            )

        assertEquals(
            listOf(Product("KB-1001", "$89.99"), Product("KB-1002", "$79.00")),
            completed.decode<List<Product>>("results"),
        )
    }

    /**
     * `AwaitMessage` stores the whole envelope, so the payload has to be reached through it. Both
     * routes are offered and both must work on the same string.
     */
    @Test
    fun decode_payload_reaches_into_a_stored_bridge_envelope() {
        val completed =
            WorkflowEvent.Completed(
                mapOf("fromPage" to """{"id":"pong-1","type":"pong","payload":{"subject":"Kotlin Multiplatform"}}"""),
            )

        assertEquals(Pong("Kotlin Multiplatform"), completed.decodePayload<Pong>("fromPage"))
        assertEquals("pong", completed.decode<dev.ggoggam.vitre.core.bridge.BridgeMessage>("fromPage").type)
    }

    /** A typo in a variable name is otherwise a null that travels a long way from its cause. */
    @Test
    fun decoding_a_variable_that_was_never_set_names_the_variable_and_what_was_set() {
        val completed = WorkflowEvent.Completed(mapOf("results" to "[]", "title" to "hi"))

        val failure = assertFailsWith<IllegalArgumentException> { completed.decode<List<Product>>("reslts") }

        assertTrue("reslts" in failure.message.orEmpty(), "the failure did not name the missing variable")
        assertTrue("results, title" in failure.message.orEmpty(), "the failure did not list what was set: ${failure.message}")
    }

    @Test
    fun decoding_from_a_workflow_that_set_nothing_says_so() {
        val failure = assertFailsWith<IllegalArgumentException> { WorkflowEvent.Completed(emptyMap()).decode<Int>("x") }

        assertTrue("nothing" in failure.message.orEmpty(), "expected the empty case to be spelled out: ${failure.message}")
    }
}
