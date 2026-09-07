package dev.ggoggam.vitre.sample.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.compose.VitreFrameHost
import dev.ggoggam.vitre.core.frame.FramePool
import dev.ggoggam.vitre.core.net.NetworkExchange
import dev.ggoggam.vitre.sample.data.LaneScenario
import dev.ggoggam.vitre.sample.data.asMoney
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private enum class LaneTab { Results, Lanes, Network }

/**
 * Runs one [LaneScenario]: four sites, four engines, one WebView, all at once.
 *
 * The lanes stay on screen while they run because that is the demonstration. Watching four
 * cross-origin shops render and fill in at the same time makes the point in a way the results table
 * underneath cannot, and when a lane comes back empty it is the only way to see whether it was
 * blocked, redirected, or simply shown a different page than the selectors expected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaneScenarioScreen(
    scenario: LaneScenario,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var pool by remember(scenario.id) { mutableStateOf<FramePool?>(null) }
    var unavailable by remember(scenario.id) { mutableStateOf<String?>(null) }
    var query by remember(scenario.id) { mutableStateOf(scenario.defaultQuery) }
    var running by remember(scenario.id) { mutableStateOf(false) }
    var tab by remember(scenario.id) { mutableStateOf(LaneTab.Results) }
    // The detail's second stop. Off by default: the lanes rendering is the demonstration, and the
    // tab underneath them is a reading of it rather than the thing itself.
    var fullHeightDetail by remember(scenario.id) { mutableStateOf(false) }
    // Same reasoning as the run sheet's: expanded, the chevron is the only control still on screen,
    // so back has to mean "put the detail down" before it means "leave the scenario".
    PlatformBackHandler(enabled = fullHeightDetail) { fullHeightDetail = false }

    /** Wall-clock for the whole submission, so a slow run can be told from a stuck one. */
    var elapsedMs by remember(scenario.id) { mutableStateOf<Long?>(null) }
    // Keyed by task index rather than by lane. Once the pool queues, a lane runs several tasks and
    // "which lane" stops being an identity — it is a fact about one moment in one run.
    val runs = remember(scenario.id) { mutableStateMapOf<Int, LaneRun>() }
    val exchanges = remember(scenario.id) { mutableStateListOf<NetworkExchange>() }
    val scope = rememberCoroutineScope()

    // Subscribed for the pool's whole life rather than per run: a shop's page keeps fetching after
    // its workflow has finished, and those requests are often the interesting ones.
    LaunchedEffect(pool) {
        pool?.tap?.exchanges?.collect { exchange ->
            exchanges.add(0, exchange)
            if (exchanges.size > MAX_EXCHANGES) exchanges.removeRange(MAX_EXCHANGES, exchanges.size)
        }
    }

    val ordered = scenario.sites.indices.mapNotNull { runs[it] }
    val status = ordered.overallStatus(running)

    fun start() {
        val current = pool ?: return
        if (running) return
        scope.launch {
            running = true
            runs.clear()
            exchanges.clear()
            elapsedMs = null
            val wallClock = TimeSource.Monotonic.markNow()
            try {
                // Every site is submitted, however many lanes came back. The pool starts what it
                // can and queues the rest, which is what makes a device-sized lane count safe —
                // the old arrangement zipped sites against lanes and silently lost the surplus.
                val workflows = scenario.sites.map { it.workflowFor(query) }
                val taskStarts = mutableMapOf<Int, TimeMark>()
                current.run(workflows).collect { poolEvent ->
                    val index = poolEvent.taskIndex
                    // Started on the task's first event rather than at submission: a task that sat
                    // in the queue for twenty seconds did not take twenty seconds to run, and
                    // charging it for the wait would make a narrow pool look slow per task instead
                    // of slow overall.
                    val since = taskStarts.getOrPut(index) { TimeSource.Monotonic.markNow() }
                    runs[index] =
                        LaneRun(
                            taskIndex = index,
                            laneId = poolEvent.laneId,
                            site = scenario.sites[index],
                            workflow = poolEvent.workflow,
                            events = runs[index]?.events.orEmpty() + poolEvent.event,
                            elapsedMs = since.elapsedNow().inWholeMilliseconds,
                        )
                }
            } finally {
                elapsedMs = wallClock.elapsedNow().inWholeMilliseconds
                running = false
            }
        }
    }

    val tabs = if (scenario.comparesPrices) LaneTab.entries else listOf(LaneTab.Lanes, LaneTab.Network)
    val shownTab = if (tab in tabs) tab else tabs.first()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = scenario.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = laneSubtitle(scenario.sites.size, pool?.laneCount, elapsedMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(ArrowBackIcon, contentDescription = "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = ::start, enabled = pool != null && !running) {
                        Icon(RefreshIcon, contentDescription = "Run again")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            )
        },
    ) { padding ->
        // The expanded detail is drawn over the scaffold's content rather than given a share of the
        // column, which is what keeps it from resizing anything underneath. Four lane WebViews
        // relaid out because a table wanted more room would be a different demo — and a worse one,
        // since a page that reflowed mid-run is exactly the confound the lanes are on screen to
        // rule out.
        //
        // Inside the content slot rather than over the whole screen, so the top bar stays put: the
        // scenario's name, its elapsed time and "Run again" are what tell you which run the table
        // below is a reading of, and they are worth more here than the forty dp they cost.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                ControlBar(
                    scenario = scenario,
                    query = query,
                    onQueryChange = { query = it },
                    running = running,
                    enabled = pool != null,
                    status = status,
                    onRun = ::start,
                )
                LaneViewport(
                    scenario = scenario,
                    unavailable = unavailable,
                    onPoolReady = { pool = it },
                    onUnavailable = { unavailable = it },
                    modifier = Modifier.fillMaxWidth().weight(VIEWPORT_WEIGHT),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LaneTabBar(
                    tabs = tabs,
                    selected = shownTab,
                    runCount = runs.size,
                    exchangeCount = exchanges.size,
                    fullHeight = false,
                    onSelect = { tab = it },
                    onToggleFullHeight = { fullHeightDetail = true },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(DETAIL_WEIGHT)) {
                    LaneTabContent(tab = shownTab, ordered = ordered, exchanges = exchanges)
                }
            }

            // Only composed while it is up, so the collapsed screen is laid out exactly as it was
            // before there was a second stop at all. The tab content appears twice in the source
            // and never twice on screen.
            if (fullHeightDetail) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    LaneTabBar(
                        tabs = tabs,
                        selected = shownTab,
                        runCount = runs.size,
                        exchangeCount = exchanges.size,
                        fullHeight = true,
                        onSelect = { tab = it },
                        onToggleFullHeight = { fullHeightDetail = false },
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LaneTabContent(tab = shownTab, ordered = ordered, exchanges = exchanges)
                    }
                }
            }
        }
    }
}

/**
 * The tab strip, with the chevron that takes the detail over the rest of the screen and back.
 *
 * Shared by both stops rather than written twice, so the tabs cannot drift apart — and so the
 * chevron keeps its place when the detail comes up over the row that was holding it.
 */
@Composable
private fun LaneTabBar(
    tabs: List<LaneTab>,
    selected: LaneTab,
    runCount: Int,
    exchangeCount: Int,
    fullHeight: Boolean,
    onSelect: (LaneTab) -> Unit,
    onToggleFullHeight: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabRow(
            selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f),
        ) {
            tabs.forEach { entry ->
                Tab(
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                    text = { Text(entry.title(runCount, exchangeCount), maxLines = 1) },
                )
            }
        }
        // The same glyph and the same turn as the run sheet's, because it means the same thing.
        val chevronRotation by animateFloatAsState(if (fullHeight) 180f else 0f, label = "laneChevron")
        IconButton(onClick = onToggleFullHeight) {
            Icon(
                imageVector = ExpandLessIcon,
                contentDescription =
                    if (fullHeight) "Shrink lane detail" else "Expand lane detail to full height",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun LaneTabContent(
    tab: LaneTab,
    ordered: List<LaneRun>,
    exchanges: List<NetworkExchange>,
) {
    when (tab) {
        LaneTab.Results -> ResultsTab(ordered)
        LaneTab.Lanes -> LanesTab(ordered)
        LaneTab.Network -> NetworkTab(exchanges)
    }
}

@Composable
private fun ControlBar(
    scenario: LaneScenario,
    query: String,
    onQueryChange: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    status: RunStatus,
    onRun: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = scenario.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scenario.queryLabel != null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(scenario.queryLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (enabled && !running) onRun() }),
                    modifier = Modifier.weight(1f),
                )
            } else {
                StatusPillPlain(status, modifier = Modifier.weight(1f))
            }
            Button(onClick = onRun, enabled = enabled && !running) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(14.dp).width(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) "Running" else "Run")
            }
        }
        if (scenario.queryLabel != null) StatusPillPlain(status)
    }
}

/** [StatusPill] wants a per-workflow [RunState]; a scenario only has an overall status. */
@Composable
private fun StatusPillPlain(
    status: RunStatus,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (label, fg, bg) =
        when (status) {
            RunStatus.Idle -> Triple("Idle", scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
            RunStatus.Running -> Triple("Lanes in flight", scheme.onPrimaryContainer, scheme.primaryContainer)
            RunStatus.Completed -> Triple("Done", scheme.onTertiaryContainer, scheme.tertiaryContainer)
            RunStatus.Failed -> Triple("All lanes failed", scheme.onErrorContainer, scheme.errorContainer)
        }
    Row(modifier = modifier) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LaneViewport(
    scenario: LaneScenario,
    unavailable: String?,
    onPoolReady: (FramePool) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        if (unavailable != null) {
            Text(
                text = unavailable,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            VitreFrameHost(
                laneCount = scenario.sites.size,
                policy = scenario.policy,
                navigationTimeoutMs = scenario.navigationTimeoutMs,
                onPoolReady = onPoolReady,
                onUnavailable = onUnavailable,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ResultsTab(runs: List<LaneRun>) {
    val products = runs.compareByProduct()
    if (products.isEmpty()) {
        EmptyHint(if (runs.isEmpty()) "Run the scenario to compare prices." else "No offers extracted yet.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(products, key = { it.title }) { product -> ProductCard(product) }
    }
}

/**
 * One product, every shop's quote for it, winner first.
 *
 * The sticker price sits beside the delivered total on purpose: for most of this catalogue the two
 * name different winners, and the card is only worth having if it shows that rather than hiding it
 * behind one number.
 */
@Composable
private fun ProductCard(product: ProductComparison) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = product.title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        product.spreadCents?.takeIf { it > 0 }?.let { spread ->
            Text(
                text = "${spread.asMoney()} between the best and worst delivered price",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        product.quotes.forEachIndexed { index, offer ->
            val isBest = index == 0 && offer.totalCents != null
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isBest) scheme.tertiaryContainer else scheme.surfaceContainerLow,
                            shape = RoundedCornerShape(9.dp),
                        ).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = offer.shop,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isBest) scheme.onTertiaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            buildString {
                                append(offer.priceCents?.asMoney() ?: "?")
                                append(" + ")
                                append(
                                    offer.shippingCents?.let { if (it == 0) "free" else it.asMoney() } ?: "?",
                                )
                                if (offer.inStock == false) append("  ·  out of stock")
                            },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isBest) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = offer.totalCents?.asMoney() ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBest) scheme.onTertiaryContainer else scheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LanesTab(runs: List<LaneRun>) {
    if (runs.isEmpty()) {
        EmptyHint("Nothing has run yet.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Keyed by task, not by lane: a queued pool puts several tasks through lane A, and two
        // rows claiming the same key is a hard crash in a LazyColumn rather than a rendering glitch.
        items(runs, key = { it.taskIndex }) { run ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = run.laneId?.uppercase() ?: "–",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = run.site.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(state = run.state)
                }
                Text(
                    text =
                        buildString {
                            append(run.site.origin)
                            run.elapsedMs?.let { append("  ·  ${it}ms") }
                            if (run.offers.isNotEmpty()) append("  ·  ${run.offers.size} offers")
                        },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                run.state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                    )
                }
                // Roomier than the default: a probe's note is a JSON object whose most
                // interesting field is the last one, and four lines cut it off exactly there.
                run.note?.let { VariableRow(name = "note", value = it, maxLines = 12) }
            }
        }
    }
}

@Composable
private fun NetworkTab(exchanges: List<NetworkExchange>) {
    if (exchanges.isEmpty()) {
        EmptyHint("No requests intercepted yet.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(exchanges, key = { it.id }) { exchange ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${exchange.method} ${exchange.status} · ${exchange.outcome} · ${exchange.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = exchange.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The body is the reason this tab exists: a shop that renders from JSON hands over
                // typed prices here, where the DOM would have them split across three spans.
                exchange.body?.takeIf { it.isNotBlank() && exchange.contentType?.contains("json") == true }?.let {
                    Text(
                        text = it.take(MAX_BODY_PREVIEW),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                exchange.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private fun LaneTab.title(
    laneCount: Int,
    exchangeCount: Int,
): String =
    when (this) {
        LaneTab.Results -> "Results"
        LaneTab.Lanes -> if (laneCount > 0) "Lanes ($laneCount)" else "Lanes"
        LaneTab.Network -> if (exchangeCount > 0) "Network ($exchangeCount)" else "Network"
    }

/** The lanes get the larger share: they are the demonstration, the tables are the read-out. */
private const val VIEWPORT_WEIGHT = 1.15f
private const val DETAIL_WEIGHT = 1f
private const val MAX_EXCHANGES = 200
private const val MAX_BODY_PREVIEW = 400

/**
 * Says what the pool actually is, which is not always what was asked for.
 *
 * The lane count is the interesting number: a pool sizes itself to the device, so "4 tasks · 2
 * lanes" is the normal reading on a small phone and the thing that explains why a run took twice as
 * long as it did on the desk.
 */
private fun laneSubtitle(
    tasks: Int,
    lanes: Int?,
    elapsedMs: Long?,
): String =
    buildString {
        append(tasks).append(if (tasks == 1) " task" else " tasks")
        append(" · ").append(lanes?.let { "$it ${if (it == 1) "lane" else "lanes"}" } ?: "no pool")
        elapsedMs?.let { append(" · ").append(it).append("ms") }
    }
