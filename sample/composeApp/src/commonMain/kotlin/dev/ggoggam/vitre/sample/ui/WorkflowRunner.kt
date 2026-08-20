package dev.ggoggam.vitre.sample.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.compose.VitreWebView
import dev.ggoggam.vitre.compose.rememberVitreWebViewState
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.sample.data.SampleMcp
import kotlinx.coroutines.launch

/**
 * Runs [workflow] in a hosted WebView and reports progress underneath it.
 *
 * The page gets the bulk of the screen — it is the thing being automated — and the run detail
 * lives in a bottom sheet, so a phone shows the timeline without shrinking the page to a strip and
 * can drag it down to get the page back. When the detail outgrows the sheet's dragged-open height,
 * the chevron in its header takes it up to everything below the top bar. [onBack] is null in the
 * two-pane layout, where the list is already on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowRunner(
    workflow: Workflow,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val webViewState = rememberVitreWebViewState(initialUrl = "about:blank")
    val controller = webViewState.controller
    val events = remember(workflow.id) { mutableStateListOf<WorkflowEvent>() }
    var runId by remember(workflow.id) { mutableStateOf(0) }
    // Shown in the subtitle only. The WebView starts blank and the workflow's own first step loads
    // the page, so the demo shows that step doing something instead of arriving at a page the host
    // had already fetched behind its back — and no workflow pays for the same page twice.
    val origin = remember(workflow.id) { workflow.originUrl() ?: "no page" }

    LaunchedEffect(workflow.id, controller, runId) {
        val current = controller ?: return@LaunchedEffect
        events.clear()
        WorkflowEngine(current).run(workflow).collect { events.add(it) }
    }

    // The whole of the host side of MCP: hand the WebView to the registry while it exists, take it
    // back when it does not. An agent can drive exactly what is registered here and nothing else,
    // and a tool call arriving after this screen closes is told the session is gone rather than
    // driving a WebView that has been torn down. The unregister half is real because the state
    // holder nulls its controller on unmount, so this effect disposes on the way out too.
    DisposableEffect(controller) {
        val current = controller
        if (current != null) {
            SampleMcp.sessions.register(SampleMcp.SESSION_ID, current, "the gallery's WebView")
        }
        onDispose { SampleMcp.sessions.unregister(SampleMcp.SESSION_ID) }
    }

    var mcpTranscript by remember(workflow.id) { mutableStateOf<String?>(null) }

    val state = runStateOf(workflow.steps.size, events)
    val scope = rememberCoroutineScope()
    // Hiding is off: the header is the only handle back to the run detail, so it always stays put.
    val scaffoldState =
        rememberBottomSheetScaffoldState(
            bottomSheetState =
                rememberStandardBottomSheetState(initialValue = SheetValue.Expanded),
        )
    val sheetState = scaffoldState.bottomSheetState
    // The scaffold gives the drag two stops, so the third — detail all the way up — is a chevron in
    // the header rather than a gesture. Off by default: most runs are short enough that the drag's
    // own stop shows the whole timeline, and the page stays worth looking at.
    var fullHeightDetail by remember(workflow.id) { mutableStateOf(false) }
    // Collapsed, the sheet should show its handle and header and nothing more — a sliver of the
    // first step row reads as a rendering slip rather than an invitation to drag. All three are
    // measured rather than guessed so a larger font scale or a different handle still lands on that
    // seam; the initial values are only what the first frame uses before layout reports the real
    // ones. The navigation bar inset is deliberately not added to the peek: it hangs off the bottom
    // of the sheet, below the screen edge, so counting it would just uncover that much of the
    // timeline. The detail cap does count it, because there it is on screen — see [RunSheet].
    val density = LocalDensity.current
    var handleHeight by remember { mutableStateOf(48.dp) }
    var headerHeight by remember { mutableStateOf(52.dp) }
    var topBarHeight by remember { mutableStateOf(64.dp) }

    BoxWithConstraints(modifier = modifier) {
        // Dragged open, the sheet gets at most half of what it is running in, so the page it is
        // reporting on never disappears behind it. On a phone in portrait that half is roomier than
        // the timeline needs and the flat cap wins; in a landscape pane it is the half that binds.
        // At full height it takes everything below the top bar — a long timeline or a long MCP
        // transcript is worth the whole pane — but not the bar itself, so back and re-run stay
        // reachable and the sheet still reads as a sheet over something.
        val chrome = handleHeight + headerHeight
        val targetDetailHeight =
            if (fullHeightDetail) {
                (maxHeight - topBarHeight - chrome).coerceAtLeast(0.dp)
            } else {
                (maxHeight / 2 - chrome).coerceIn(0.dp, MAX_SHEET_DETAIL_HEIGHT)
            }
        // Animated rather than switched: the sheet's expanded stop is wherever its content ends, so
        // growing the content over a few frames is what carries the sheet up smoothly instead of
        // teleporting it.
        val detailMaxHeight by animateDpAsState(targetDetailHeight, label = "sheetDetailHeight")

        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = handleHeight + headerHeight,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            sheetShadowElevation = 12.dp,
            // Wrapped rather than replaced: the scaffold hangs the sheet's expand/collapse
            // accessibility actions off whatever occupies this slot.
            sheetDragHandle = {
                Box(
                    modifier =
                        Modifier.onSizeChanged {
                            handleHeight = with(density) { it.height.toDp() }
                        },
                ) {
                    BottomSheetDefaults.DragHandle()
                }
            },
            sheetContent = {
                RunSheet(
                    workflow = workflow,
                    state = state,
                    expanded = sheetState.targetValue == SheetValue.Expanded,
                    fullHeight = fullHeightDetail,
                    detailMaxHeight = detailMaxHeight,
                    mcpTranscript = mcpTranscript,
                    onAskAgent = {
                        scope.launch {
                            mcpTranscript = "…"
                            mcpTranscript = SampleMcp.snapshotThroughMcp()
                        }
                    },
                    onHeaderSized = { headerHeight = with(density) { it.toDp() } },
                    onToggle = {
                        scope.launch {
                            if (sheetState.targetValue == SheetValue.Expanded) {
                                sheetState.partialExpand()
                            } else {
                                sheetState.expand()
                            }
                        }
                    },
                    // Asking for full height while the sheet is down is asking to see the detail,
                    // so it opens too — otherwise the chevron would look like it did nothing.
                    onToggleFullHeight = {
                        fullHeightDetail = !fullHeightDetail
                        scope.launch { sheetState.expand() }
                    },
                )
            },
            topBar = {
                TopAppBar(
                    modifier =
                        Modifier.onSizeChanged {
                            topBarHeight = with(density) { it.height.toDp() }
                        },
                    title = {
                        Column {
                            Text(
                                text = workflow.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = origin,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
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
                        IconButton(onClick = { runId++ }, enabled = controller != null) {
                            Icon(RefreshIcon, contentDescription = "Re-run workflow")
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                )
            },
        ) { padding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                VitreWebView(
                    state = webViewState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Bottom sheet run detail: status, step timeline, failure message, extracted variables. */
@Composable
private fun RunSheet(
    workflow: Workflow,
    state: RunState,
    expanded: Boolean,
    fullHeight: Boolean,
    detailMaxHeight: Dp,
    mcpTranscript: String?,
    onAskAgent: () -> Unit,
    onHeaderSized: (Int) -> Unit,
    onToggle: () -> Unit,
    onToggleFullHeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            state = state,
            stepCount = workflow.steps.size,
            expanded = expanded,
            fullHeight = fullHeight,
            onToggle = onToggle,
            onToggleFullHeight = onToggleFullHeight,
            modifier = Modifier.onSizeChanged { onHeaderSized(it.height) },
        )
        // Capped so a long workflow cannot push the sheet over the whole page unasked: past the cap
        // the timeline scrolls inside the sheet instead of growing it. The header's chevron is what
        // raises the cap. The navigation bar inset is taken out of the viewport rather than added
        // to the content, so the cap is the whole of what this costs the sheet — at full height
        // that is what leaves the sheet's top edge exactly under the top bar.
        Column(
            modifier =
                Modifier
                    .heightIn(max = detailMaxHeight)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            workflow.steps.forEachIndexed { index, step ->
                StepRow(
                    index = index,
                    step = step,
                    state = state.stepStates.getOrElse(index) { StepState.Pending },
                )
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(10.dp),
                            ).padding(12.dp),
                )
            }
            if (state.variables.isNotEmpty()) {
                SectionLabel("Variables")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.variables.forEach { (name, value) -> VariableRow(name, value) }
                }
            }
            McpSection(transcript = mcpTranscript, onAskAgent = onAskAgent)
        }
    }
}

/**
 * Drives the same WebView through the MCP server instead of the workflow engine.
 *
 * Worth having in the sample because it is the one thing unit tests cannot show: the tools reach a
 * real page, and what comes back is what an agent would be handed. It goes over JSON deliberately —
 * calling the Kotlin directly would skip the layer most likely to be wrong.
 */
@Composable
private fun McpSection(
    transcript: String?,
    onAskAgent: () -> Unit,
) {
    SectionLabel("MCP")
    Text(
        text = "Ask the agent to look at this page",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clickable(onClick = onAskAgent, onClickLabel = "Run an MCP snapshot tool call")
                .padding(vertical = 8.dp),
    )
    transcript?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(10.dp),
                    ).padding(12.dp),
        )
    }
}

@Composable
private fun SheetHeader(
    state: RunState,
    stepCount: Int,
    expanded: Boolean,
    fullHeight: Boolean,
    onToggle: () -> Unit,
    onToggleFullHeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tapping the header does what dragging it does, for anyone who would rather not drag. The top
    // padding is slight because the chevron's 48dp touch target already sets the row's height; the
    // bottom still runs deep because, collapsed, it is all that separates the pill from the
    // gesture bar.
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onToggle,
                    onClickLabel = if (expanded) "Collapse run detail" else "Expand run detail",
                ).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(state = state, stepCount = stepCount)
        Spacer(Modifier.width(12.dp))
        Text(
            text = state.runningStep?.let { "step ${it + 1} of $stepCount" } ?: "$stepCount steps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // One glyph for both directions: up is "there is more of this to see", and it turns over
        // once the detail is already as tall as it goes.
        val chevronRotation by animateFloatAsState(if (fullHeight) 180f else 0f, label = "chevron")
        IconButton(onClick = onToggleFullHeight) {
            Icon(
                imageVector = ExpandLessIcon,
                contentDescription =
                    if (fullHeight) "Shrink run detail" else "Expand run detail to full height",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

private val MAX_SHEET_DETAIL_HEIGHT = 300.dp
