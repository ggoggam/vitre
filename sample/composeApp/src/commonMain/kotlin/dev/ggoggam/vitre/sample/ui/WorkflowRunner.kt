package dev.ggoggam.vitre.sample.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.ggoggam.vitre.compose.VitreWebView
import dev.ggoggam.vitre.compose.rememberVitreWebViewState
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.sample.data.SampleMcp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The three heights the run sheet settles on.
 *
 * Three rather than the two a `BottomSheetScaffold` offers, which is the reason this screen drives
 * its own sheet: peek and full alone would make you choose between seeing the page and seeing the
 * timeline, and the middle stop — the one it opens on — is where most runs are read.
 */
private enum class SheetStop { Peek, Half, Full }

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

    val state = runStateOf(workflow, events)
    val scope = rememberCoroutineScope()
    // Which stop the sheet is settled on. The drag reaches all three; the header's tap and its
    // chevron are shortcuts to two of the transitions, for anyone who would rather not drag.
    var stop by remember(workflow.id) { mutableStateOf(SheetStop.Half) }
    // Collapsed, the sheet should show its handle and header and nothing more — a sliver of the
    // first step row reads as a rendering slip rather than an invitation to drag. Both are
    // measured rather than guessed so a larger font scale or a different handle still lands on that
    // seam; the initial values are only what the first frame uses before layout reports the real
    // ones. The navigation bar inset is deliberately not added to the peek: it hangs off the bottom
    // of the sheet, below the screen edge, so counting it would just uncover that much of the
    // timeline. The detail viewport does count it, because there it is on screen — see [RunSheet].
    val density = LocalDensity.current
    var handleHeight by remember { mutableStateOf(48.dp) }
    var headerHeight by remember { mutableStateOf(52.dp) }
    // Measured for the same reason, and used for the same kind of seam: it is where the full stop
    // stops. The bar carries its own status bar inset, so its measured height is the whole of what
    // sits above the sheet and no separate inset has to be worked out here.
    var topBarHeight by remember { mutableStateOf(64.dp) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val chrome = handleHeight + headerHeight
        // The three stops, as sheet heights rather than offsets, because the detail's scroll
        // viewport is the sheet minus its chrome and has to shrink with it. A sheet of fixed height
        // slid up and down would keep a viewport the size of the tallest stop, and the timeline
        // would scroll to rows sitting off the bottom of the screen.
        //
        // Half is what the sheet used to stop at and still opens on: at most half of what the
        // runner is running in, so the page it reports on never disappears behind it, and no more
        // than the flat cap, which on a phone in portrait is roomier than the timeline needs.
        // Full takes everything below the top bar — a long timeline or a long MCP transcript is
        // worth the rest of the screen — but not the bar itself, which names the workflow and the
        // page it is on and holds the only back and re-run there are. A sheet that covered it would
        // also stop reading as a sheet over something.
        val peekHeight = chrome
        val fullHeight = (maxHeight - topBarHeight).coerceAtLeast(peekHeight)
        val halfHeight = (chrome + MAX_SHEET_DETAIL_HEIGHT).coerceAtMost(maxHeight / 2).coerceIn(peekHeight, fullHeight)

        val peekPx = with(density) { peekHeight.toPx() }
        val halfPx = with(density) { halfHeight.toPx() }
        val fullPx = with(density) { fullHeight.toPx() }

        fun pxOf(target: SheetStop) =
            when (target) {
                SheetStop.Peek -> peekPx
                SheetStop.Half -> halfPx
                SheetStop.Full -> fullPx
            }

        // The sheet's live height. An `Animatable` rather than `animateDpAsState` because a drag
        // has to be able to interrupt the animation and take the value over mid-flight, which is
        // the whole difference between a gesture and a button.
        val sheetHeightPx = remember(workflow.id) { Animatable(0f) }

        suspend fun settleTo(target: SheetStop) {
            stop = target
            sheetHeightPx.animateTo(pxOf(target), spring(stiffness = Spring.StiffnessMediumLow))
        }
        // Re-seats the sheet when the geometry moves under it — the first frames, where the chrome
        // is still the guessed height, and a rotation. Keyed on the stop positions, so a drag,
        // which moves the sheet without moving them, is never interrupted by it.
        LaunchedEffect(peekPx, halfPx, fullPx) { sheetHeightPx.snapTo(pxOf(stop)) }
        // Back undoes the last thing that happened rather than two things at once: with the detail
        // up over the page, the first press puts it down and the second leaves the runner. Declared
        // after `App`'s handler and so ahead of it in the dispatcher, which is what lets it take
        // that first press.
        PlatformBackHandler(enabled = stop == SheetStop.Full) {
            scope.launch { settleTo(SheetStop.Half) }
        }

        val dragState =
            rememberDraggableState { delta ->
                // Up is a negative delta and a taller sheet. Clamped to the outer stops so a drag
                // cannot overshoot into empty space and leave the sheet somewhere it has no stop.
                scope.launch { sheetHeightPx.snapTo((sheetHeightPx.value - delta).coerceIn(peekPx, fullPx)) }
            }
        val sheetDrag =
            Modifier.draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val height = sheetHeightPx.value
                    val ascending = listOf(SheetStop.Peek, SheetStop.Half, SheetStop.Full)
                    // A definite flick carries to the next stop the way it was thrown, even from
                    // right beside the one it started at — which is what makes two quick flicks up
                    // reach full height. Anything slower is a considered drag, and settles on
                    // whichever stop it was left nearest.
                    settleTo(
                        when {
                            velocity < -FLING_VELOCITY -> ascending.firstOrNull { pxOf(it) > height + 1f } ?: SheetStop.Full
                            velocity > FLING_VELOCITY -> ascending.lastOrNull { pxOf(it) < height - 1f } ?: SheetStop.Peek
                            else -> ascending.minBy { abs(pxOf(it) - height) }
                        },
                    )
                },
            )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
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

        // A sibling of the scaffold rather than its sheet slot, because the slot comes with the
        // scaffold's own two-anchor drag and that is the thing being replaced. What it gives up
        // with the slot is the scaffold's expand/collapse accessibility actions on the handle; the
        // header is a labelled click target and the chevron a labelled button, so both transitions
        // are still reachable without the gesture. Being a sibling also means the sheet runs to the
        // screen edge rather than to the scaffold's inset content box, which is what lets the peek
        // tuck its chrome against the gesture bar.
        // The corners square off over the last stretch of the climb to full height. Rounded, they
        // leave two slivers of page showing in the gap between the sheet's top edge and the bar
        // above it, which reads as the sheet having failed to reach the top rather than as a
        // radius. Driven off the sheet's own height rather than off `stop`, so it tracks the drag
        // continuously and is already square by the time the finger arrives — a radius that waited
        // for the gesture to end would snap.
        val squareness = ((sheetHeightPx.value - halfPx) / (fullPx - halfPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val sheetCorner = lerp(SHEET_CORNER_RADIUS, 0.dp, squareness)
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { sheetHeightPx.value.toDp() }),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = sheetCorner, topEnd = sheetCorner),
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(sheetDrag)
                            .onSizeChanged { handleHeight = with(density) { it.height.toDp() } },
                    contentAlignment = Alignment.Center,
                ) {
                    BottomSheetDefaults.DragHandle()
                }
                RunSheet(
                    workflow = workflow,
                    state = state,
                    expanded = stop != SheetStop.Peek,
                    fullHeight = stop == SheetStop.Full,
                    mcpTranscript = mcpTranscript,
                    onAskAgent = {
                        scope.launch {
                            mcpTranscript = "…"
                            mcpTranscript = SampleMcp.snapshotThroughMcp()
                        }
                    },
                    onHeaderSized = { headerHeight = with(density) { it.toDp() } },
                    onToggle = {
                        scope.launch { settleTo(if (stop == SheetStop.Peek) SheetStop.Half else SheetStop.Peek) }
                    },
                    // Asking for full height while the sheet is down is asking to see the detail,
                    // so it opens too — otherwise the chevron would look like it did nothing.
                    onToggleFullHeight = {
                        scope.launch { settleTo(if (stop == SheetStop.Full) SheetStop.Half else SheetStop.Full) }
                    },
                    headerModifier = sheetDrag,
                    modifier = Modifier.weight(1f),
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
    mcpTranscript: String?,
    onAskAgent: () -> Unit,
    onHeaderSized: (Int) -> Unit,
    onToggle: () -> Unit,
    onToggleFullHeight: () -> Unit,
    headerModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            state = state,
            expanded = expanded,
            fullHeight = fullHeight,
            onToggle = onToggle,
            onToggleFullHeight = onToggleFullHeight,
            modifier = Modifier.onSizeChanged { onHeaderSized(it.height) }.then(headerModifier),
        )
        // Whatever the sheet has left after its chrome, which is what makes the drag's stops mean
        // something: at each one the timeline scrolls within exactly the space on screen, rather
        // than within the tallest stop's worth with the overflow below the screen edge. The
        // navigation bar inset comes out of the viewport rather than being added to the content, so
        // the last row clears the gesture bar without the sheet growing to accommodate it.
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            workflow.flatSteps().forEach { flat ->
                StepRow(
                    index = flat.path.index,
                    step = flat.step,
                    state = state.stateOf(flat.path),
                    depth = flat.depth,
                    note = state.fanOuts[flat.path]?.summary(),
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
        StatusPill(state = state)
        Spacer(Modifier.width(12.dp))
        Text(
            text =
                state.runningOrdinal?.let { "step ${it + 1} of ${state.stepCount}" }
                    ?: "${state.stepCount} steps",
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

/** The sheet's corner radius everywhere below full height, where it squares off. */
private val SHEET_CORNER_RADIUS = 20.dp

private val MAX_SHEET_DETAIL_HEIGHT = 300.dp

/**
 * Above this, in pixels per second, a drag counts as a flick and carries to the next stop rather
 * than settling on the nearest one. Low enough that an ordinary flick up from the half stop reaches
 * full height, high enough that easing the sheet into place is not read as one.
 */
private const val FLING_VELOCITY = 400f
