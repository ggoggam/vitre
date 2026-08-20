package dev.ggoggam.vitre.sample.data

import kotlinx.serialization.json.JsonObject

/**
 * One tool call the model asked for.
 *
 * The shape is the Messages API's `tool_use` block — an id, the tool's name, and its arguments as
 * JSON — because that is what the loop below is a stand-in for. The id is what a real API pairs the
 * result back against; here it also keys the pending row in the transcript.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
)

/** A call and what the server said about it — everything the next inference call gets to read. */
data class ToolOutcome(
    val call: ToolCall,
    val output: McpToolOutput,
)

/**
 * One assistant turn: some text, and zero or more tools it wants run before it can continue.
 *
 * [stopReason] is derived rather than stored for the same reason the real API's is meaningful: an
 * assistant turn that asked for a tool is not finished, and treating it as an answer is how a loop
 * silently drops half the work.
 */
data class AssistantTurn(
    val text: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    val stopReason: String get() = if (toolCalls.isEmpty()) "end_turn" else "tool_use"
}

/**
 * The one thing this sample fakes.
 *
 * Everything downstream of here is real — the JSON-RPC, the tool dispatch, the WebView — so the
 * seam is deliberately narrow and deliberately shaped like the real call: the whole exchange goes
 * in, one assistant turn comes out, and the implementation may look at what the tools returned
 * before deciding what to do next. Swapping [MockModel] for a client that posts these messages to
 * a real model is a change to this one interface's implementation and nothing else.
 */
fun interface AgentModel {
    suspend fun next(
        prompt: String,
        soFar: List<ToolOutcome>,
    ): AssistantTurn
}

/** A line in the chat pane. The transcript is append-only, so the list index is a stable key. */
sealed interface ChatEntry {
    data class User(
        val text: String,
    ) : ChatEntry

    data class Assistant(
        val text: String,
    ) : ChatEntry

    data class Call(
        val call: ToolCall,
    ) : ChatEntry

    data class Result(
        val name: String,
        val output: McpToolOutput,
    ) : ChatEntry

    /** Something the harness says about itself — the handshake, or a loop that gave up. */
    data class Note(
        val text: String,
    ) : ChatEntry
}

/**
 * The agentic tool-use loop, in full.
 *
 * ```
 * user message → model → tool_use? → run the tools → results back to the model → …until end_turn
 * ```
 *
 * That is the whole thing, and it is worth seeing written out because it is the part people expect
 * to be complicated. Two details are load-bearing rather than incidental:
 *
 *  - **Every result goes back before the model is asked again.** A turn that requested three tools
 *    gets three results in one go. Feeding them back one at a time works, and teaches a real model
 *    to stop asking for more than one at a time.
 *  - **A failed tool is a result, not an exception.** `isError` travels back into the conversation
 *    so the model can correct itself; the scripted "how much is the Logitech" exchange exists to
 *    show exactly that, and its first selector genuinely does not match.
 *
 * [MAX_HOPS] is the runaway guard every real loop needs and most examples omit.
 */
class ChatAgent(
    private val model: AgentModel,
    private val mcp: McpClient,
) {
    suspend fun send(
        prompt: String,
        emit: (ChatEntry) -> Unit,
    ) {
        emit(ChatEntry.User(prompt))
        val outcomes = mutableListOf<ToolOutcome>()
        repeat(MAX_HOPS) {
            val turn = model.next(prompt, outcomes)
            turn.text?.takeIf { it.isNotBlank() }?.let { emit(ChatEntry.Assistant(it)) }
            if (turn.stopReason == "end_turn") return
            for (call in turn.toolCalls) {
                emit(ChatEntry.Call(call))
                val output = mcp.callTool(call.name, call.input)
                emit(ChatEntry.Result(call.name, output))
                outcomes += ToolOutcome(call, output)
            }
        }
        emit(
            ChatEntry.Note(
                "Stopped after $MAX_HOPS rounds of tool calls without a final answer. A loop with " +
                    "no cap is a loop that can bill forever.",
            ),
        )
    }

    private companion object {
        const val MAX_HOPS = 6
    }
}
