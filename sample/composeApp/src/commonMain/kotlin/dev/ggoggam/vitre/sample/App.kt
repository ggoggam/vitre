package dev.ggoggam.vitre.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.sample.data.LaneScenarios
import dev.ggoggam.vitre.sample.data.SampleWorkflows
import dev.ggoggam.vitre.sample.ui.ChatScreen
import dev.ggoggam.vitre.sample.ui.GalleryEntry
import dev.ggoggam.vitre.sample.ui.LaneScenarioScreen
import dev.ggoggam.vitre.sample.ui.PlatformBackHandler
import dev.ggoggam.vitre.sample.ui.VitreTheme
import dev.ggoggam.vitre.sample.ui.WorkflowList
import dev.ggoggam.vitre.sample.ui.WorkflowRunner

/**
 * Width at which the gallery stops showing one screen at a time. Below it (every phone in
 * portrait) the list and the runner take turns; above it — tablets, foldables unfolded,
 * desktop — they sit side by side and selecting a workflow swaps the right-hand pane.
 */
private val TwoPaneBreakpoint = 720.dp

@Composable
fun App() {
    VitreTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var selected by remember { mutableStateOf<GalleryEntry?>(null) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= TwoPaneBreakpoint) {
                    TwoPaneGallery(selected = selected, onSelect = { selected = it })
                } else {
                    // System back leaves the runner before it leaves the app.
                    PlatformBackHandler(enabled = selected != null) { selected = null }
                    when (val current = selected) {
                        null -> GalleryScreen(selected = null, onSelect = { selected = it })
                        else -> EntryScreen(entry = current, onBack = { selected = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun TwoPaneGallery(
    selected: GalleryEntry?,
    onSelect: (GalleryEntry) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        GalleryScreen(
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.width(360.dp).fillMaxHeight(),
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (selected) {
            // No onBack: the list is already on screen, so there is nowhere to go back to.
            null -> EmptyRunnerPane(modifier = Modifier.weight(1f).fillMaxHeight())

            else -> EntryScreen(entry = selected, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun GalleryScreen(
    selected: GalleryEntry?,
    onSelect: (GalleryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Vitre",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "WebView workflow gallery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WorkflowList(
            workflows = SampleWorkflows.all,
            scenarios = LaneScenarios.all,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        )
    }
}

/** Every kind of gallery entry opens into its own screen; this is the only place that branches. */
@Composable
private fun EntryScreen(
    entry: GalleryEntry,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    when (entry) {
        is GalleryEntry.Single -> WorkflowRunner(workflow = entry.workflow, modifier = modifier, onBack = onBack)
        is GalleryEntry.Lanes -> LaneScenarioScreen(scenario = entry.scenario, modifier = modifier, onBack = onBack)
        is GalleryEntry.Chat -> ChatScreen(modifier = modifier, onBack = onBack)
    }
}

@Composable
private fun EmptyRunnerPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pick a workflow",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "It runs in an embedded WebView, and each step reports back as it goes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}
