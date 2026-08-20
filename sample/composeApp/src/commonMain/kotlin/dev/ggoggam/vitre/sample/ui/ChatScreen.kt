package dev.ggoggam.vitre.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.compose.VitreWebView
import dev.ggoggam.vitre.compose.rememberVitreWebViewState
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.sample.data.ChatAgent
import dev.ggoggam.vitre.sample.data.ChatEntry
import dev.ggoggam.vitre.sample.data.MockModel
import dev.ggoggam.vitre.sample.data.SampleMcp
import dev.ggoggam.vitre.sample.data.SampleWorkflows
import dev.ggoggam.vitre.sample.data.ToolCall
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A chat window whose model is fake and whose tools are not.
 *
 * The screen is one WebView with a conversation under it. The host loads a page into the WebView
 * and registers it with the MCP session registry; from then on nothing in the chat pane touches the
 * WebView directly. Every action you see happen to the page arrived as a `tools/call` over
 * JSON-RPC, was resolved to a session, ran as a workflow step, and came back as a tool result the
 * conversation then reads.
 *
 * The transcript deliberately shows the calls and their results rather than hiding them behind the
 * answer, because they are the demonstration: an agent that has never seen this page reaches it
 * through the same vocabulary a workflow uses, and its mistakes come back as results it can correct.
 *
 * What is mocked, and only this, is which tool to call — see
 * [MockModel][dev.ggoggam.vitre.sample.data.MockModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val webViewState = rememberVitreWebViewState(initialUrl = "about:blank")
    val controller = webViewState.controller
    val transcript = remember { mutableStateListOf<ChatEntry>() }
    val agent = remember { ChatAgent(MockModel(), SampleMcp.client) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var resetId by remember { mutableStateOf(0) }

    // The same two lines as the workflow runner, and the whole of the host's obligation: the agent
    // can drive what is registered here and nothing else, and a call arriving after this screen
    // closes is told the session is gone.
    DisposableEffect(controller) {
        val current = controller
        if (current != null) {
            SampleMcp.sessions.register(SampleMcp.SESSION_ID, current, "the gallery's WebView")
        }
        onDispose { SampleMcp.sessions.unregister(SampleMcp.SESSION_ID) }
    }

    // Put a page up, then shake hands. The handshake is shown rather than done silently because it
    // is the part of MCP a reader has usually only seen described.
    LaunchedEffect(controller, resetId) {
        val current = controller ?: return@LaunchedEffect
        transcript.clear()
        busy = true
        WorkflowEngine(current).run(SampleWorkflows.ChatFixture).collect { }
        val handshake =
            runCatching {
                val version = SampleMcp.client.initialize()
                val tools = SampleMcp.client.listTools()
                "Connected to the in-process MCP server, protocol $version. " +
                    "${tools.size} tools available: ${tools.joinToString(", ") { it.name }}."
            }.getOrElse { "The MCP handshake failed: ${it.message}" }
        transcript += ChatEntry.Note(handshake)
        transcript +=
            ChatEntry.Assistant(
                "I can see a page, but only through those tools — I was not written against it and " +
                    "I do not have its markup. Ask me something about it.",
            )
        busy = false
    }

    val send: (String) -> Unit = { text ->
        if (text.isNotBlank() && !busy) {
            draft = ""
            busy = true
            scope.launch {
                try {
                    agent.send(text) { transcript += it }
                } finally {
                    busy = false
                }
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Scaffold(
        // The bottom inset, taken once, for the whole screen. `safeDrawing` is the *union* of the
        // keyboard and the navigation bar rather than their sum, so the composer clears whichever
        // is actually there; padding it here rather than inside the composer keeps the top bar put
        // and lets the page and the transcript give up the height between them.
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Agent chat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "mocked model · real MCP tools",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(ArrowBackIcon, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { resetId++ }, enabled = controller != null && !busy) {
                            Icon(RefreshIcon, contentDescription = "Reload the page and start over")
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                )
                // A tool call runs a real step against a real WebView, so "nothing is happening
                // yet" needs saying — otherwise the pause after sending reads as a dropped message.
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = { Composer(draft = draft, enabled = !busy, onDraft = { draft = it }, onSend = send) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(PAGE_WEIGHT)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                VitreWebView(state = webViewState, modifier = Modifier.fillMaxSize())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(CHAT_WEIGHT),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The transcript is append-only, so the index is a stable key.
                itemsIndexed(transcript) { _, entry -> TranscriptRow(entry) }
            }
        }
    }
}

@Composable
private fun TranscriptRow(entry: ChatEntry) {
    when (entry) {
        is ChatEntry.User -> UserBubble(entry.text)
        is ChatEntry.Assistant -> AssistantBubble(entry.text)
        is ChatEntry.Call -> ToolCallCard(entry.call)
        is ChatEntry.Result -> ToolResultCard(entry.name, entry.output.text, entry.output.isError)
        is ChatEntry.Note -> NoteRow(entry.text)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier =
                Modifier
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * The `tool_use` block, shown rather than summarised.
 *
 * The arguments are the interesting half — they are what the model had to get right without having
 * seen the page — so they are printed as the JSON that actually went over the wire.
 */
@Composable
private fun ToolCallCard(call: ToolCall) {
    val arguments = remember(call.id) { PRETTY.encodeToString(JsonObject.serializer(), call.input) }
    ToolCard(
        label = "tool_use",
        name = call.name,
        body = arguments,
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
        onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToolResultCard(
    name: String,
    text: String,
    isError: Boolean,
) {
    // Truncated because a snapshot of a real page is hundreds of lines and the model's answer is
    // what the reader came for. The model itself gets the whole thing.
    val body =
        if (text.length <= MAX_RESULT_CHARS) {
            text
        } else {
            text.take(MAX_RESULT_CHARS) + "\n… ${text.length - MAX_RESULT_CHARS} more characters"
        }
    ToolCard(
        label = if (isError) "tool_result · is_error" else "tool_result",
        name = name,
        body = body,
        container =
            if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        onContainer =
            if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

@Composable
private fun ToolCard(
    label: String,
    name: String,
    body: String,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(container, RoundedCornerShape(12.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = onContainer,
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = onContainer,
        )
    }
}

@Composable
private fun NoteRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Suggestions above the field, because the scripted model only knows a handful of questions. */
@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // No inset padding here: the screen root already took the bottom one. Taking it twice is
        // what pushes the composer up by two keyboards.
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MockModel.SUGGESTIONS.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onSend(suggestion) },
                        enabled = enabled,
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about the page") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend(draft) }),
                )
                IconButton(onClick = { onSend(draft) }, enabled = enabled && draft.isNotBlank()) {
                    Icon(SendIcon, contentDescription = "Send")
                }
            }
        }
    }
}

/** The page keeps the larger share: watching it change is half of what the screen shows. */
private const val PAGE_WEIGHT = 0.45f
private const val CHAT_WEIGHT = 0.55f
private const val MAX_RESULT_CHARS = 700
private val BUBBLE_MAX_WIDTH = 320.dp
private val PRETTY = Json { prettyPrint = true }
