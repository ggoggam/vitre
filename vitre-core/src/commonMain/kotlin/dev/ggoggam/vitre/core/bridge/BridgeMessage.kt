package dev.ggoggam.vitre.core.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The envelope both directions of the bridge agree on.
 *
 * [id] is *this* message's own identity, never the identity of something it answers. The settle
 * plane depends on that reading — `AsyncScript` names its result envelopes `script:result#<cid>` —
 * and so does anything watching the firehose, where two messages sharing an id would be
 * indistinguishable.
 *
 * [type] `script:result` is reserved for the settle plane: those messages are claimed by
 * `ScriptResults` before the inbox ever sees them, so a message a caller sends or awaits under that
 * type is swallowed. `TypedBridge` refuses it outright.
 *
 * [replyTo] carries the [id] of the request being answered, and is how a native→page request finds
 * its reply (see `TypedBridge.request`). It is absent on ordinary one-way traffic, and
 * default-omission keeps it off the wire there, so every message written before this field existed
 * still decodes and re-encodes unchanged.
 */
@Serializable
data class BridgeMessage(
    val id: String,
    val type: String,
    val payload: JsonElement = JsonNull,
    val replyTo: String? = null,
)
