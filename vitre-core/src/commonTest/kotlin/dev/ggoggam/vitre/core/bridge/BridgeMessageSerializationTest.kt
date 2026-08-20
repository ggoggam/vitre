package dev.ggoggam.vitre.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeMessageSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun envelope_roundtrips_through_kotlinx_serialization() {
        val original =
            BridgeMessage(
                id = "abc-123",
                type = "form-submit",
                payload =
                    buildJsonObject {
                        put("email", JsonPrimitive("foo@bar.com"))
                        put("ok", JsonPrimitive(true))
                    },
            )

        val encoded = json.encodeToString(BridgeMessage.serializer(), original)
        val decoded = json.decodeFromString(BridgeMessage.serializer(), encoded)

        assertEquals(original.id, decoded.id)
        assertEquals(original.type, decoded.type)
        assertEquals(
            "foo@bar.com",
            decoded.payload.jsonObject["email"]
                ?.toString()
                ?.trim('"'),
        )
        assertEquals("true", decoded.payload.jsonObject["ok"]?.toString())
    }

    @Test
    fun reply_to_round_trips_and_is_omitted_from_the_wire_when_absent() {
        // The field has to be free for the traffic that predates it: every one-way message must
        // still encode exactly as it did, or pages parsing our envelopes see a key appear.
        val oneWay = BridgeMessage(id = "m1", type = "config")
        assertEquals("""{"id":"m1","type":"config"}""", json.encodeToString(BridgeMessage.serializer(), oneWay))

        val reply = BridgeMessage(id = "reply-1", type = "ack", replyTo = "m1")
        val encoded = json.encodeToString(BridgeMessage.serializer(), reply)
        assertEquals("""{"id":"reply-1","type":"ack","replyTo":"m1"}""", encoded)
        // And the id stays the reply's own — correlation reads replyTo, never id.
        val decoded = json.decodeFromString(BridgeMessage.serializer(), encoded)
        assertEquals("reply-1", decoded.id)
        assertEquals("m1", decoded.replyTo)
        assertEquals(null, json.decodeFromString(BridgeMessage.serializer(), """{"id":"m1","type":"config"}""").replyTo)
    }

    @Test
    fun message_without_payload_decodes_back_to_json_null() {
        // Wire format omits the elided default; round-trip restores JsonNull.
        val wire = """{"id":"x","type":"ping"}"""
        val decoded = json.decodeFromString(BridgeMessage.serializer(), wire)
        assertEquals("x", decoded.id)
        assertEquals("ping", decoded.type)
        assertEquals(kotlinx.serialization.json.JsonNull, decoded.payload)
    }
}
