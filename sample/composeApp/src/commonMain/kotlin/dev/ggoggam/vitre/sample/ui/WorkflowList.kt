package dev.ggoggam.vitre.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.sample.data.LaneScenario

/**
 * What the gallery can open. Three shapes, because a pool of four sites is not a workflow — it is
 * four of them plus the query they share — and a conversation is not either.
 */
sealed interface GalleryEntry {
    /** Stable across every kind, so selection highlighting needs no `when`. */
    val id: String

    data class Single(
        val workflow: Workflow,
    ) : GalleryEntry {
        override val id: String get() = "single:${workflow.id}"
    }

    data class Lanes(
        val scenario: LaneScenario,
    ) : GalleryEntry {
        override val id: String get() = "lanes:${scenario.id}"
    }

    /**
     * The chat example. There is only ever one, so it carries no payload — what it opens is a
     * conversation, and the page it starts on is the screen's own business.
     */
    data object Chat : GalleryEntry {
        override val id: String get() = "chat"
    }
}

/**
 * The gallery index: the chat, then the parallel-lane scenarios, then one card per single-page
 * workflow.
 *
 * The scenarios come high because they are the thing that is hard to believe — four cross-origin
 * sites automated at once inside one WebView — and because everything below them is a special case
 * of it with one lane.
 *
 * [selected] is only ever non-null in the two-pane layout, where the card has to show which entry
 * the runner on the right is currently on.
 */
@Composable
fun WorkflowList(
    workflows: List<Workflow>,
    scenarios: List<LaneScenario>,
    selected: GalleryEntry?,
    onSelect: (GalleryEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The chat leads because it is the entry that explains what the rest is for: the same step
        // vocabulary, reached by something that was not written against the page.
        item(key = "header-chat") { ListSectionLabel("Agent chat") }
        item(key = "chat") {
            ChatCard(
                isSelected = selected?.id == GalleryEntry.Chat.id,
                onClick = { onSelect(GalleryEntry.Chat) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (scenarios.isNotEmpty()) {
            item(key = "header-lanes") { ListSectionLabel("Parallel lanes") }
            items(scenarios, key = { "lanes:${it.id}" }) { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    isSelected = selected?.id == "lanes:${scenario.id}",
                    onClick = { onSelect(GalleryEntry.Lanes(scenario)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "header-single") { ListSectionLabel("Single page") }
        }
        items(workflows, key = { "single:${it.id}" }) { workflow ->
            WorkflowCard(
                workflow = workflow,
                isSelected = selected?.id == "single:${workflow.id}",
                onClick = { onSelect(GalleryEntry.Single(workflow)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ListSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

/**
 * The card every gallery entry is drawn in.
 *
 * Selection is a fill rather than an outline, so it survives the card already having one, and the
 * three kinds of entry differ only in what goes inside.
 */
@Composable
private fun GalleryCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) scheme.primaryContainer else scheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = if (isSelected) 0.dp else 1.dp,
                        color = if (isSelected) scheme.primaryContainer else scheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ).clickable(onClick = onClick)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * One rounded label. The pills under a card say what it is made of, in its own vocabulary.
 *
 * A selected card is filled with ink, so its pills are a thin veil of the page colour rather than
 * a lighter grey — anything nearer than that and the label stops clearing 4.5:1 against it.
 */
@Composable
private fun Pill(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .background(
                    color = if (isSelected) scheme.surface.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
                    shape = CircleShape,
                ).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The chat entry, whose pills are tool names rather than step types — because that is the
 * vocabulary it reaches the page in.
 */
@Composable
private fun ChatCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GalleryCard(isSelected = isSelected, onClick = onClick, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Chat with the page",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                text =
                    "A mocked model, a real MCP server. Every action it takes on the page is a " +
                        "tools/call over JSON-RPC, shown as it happens.",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CHAT_TOOLS.forEach { tool -> Pill(text = tool, isSelected = isSelected) }
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: LaneScenario,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GalleryCard(isSelected = isSelected, onClick = onClick, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = scenario.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                text = scenario.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scenario.sites.forEach { site ->
                Pill(
                    text = site.label,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    GalleryCard(isSelected = isSelected, onClick = onClick, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = workflow.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                text = workflow.originUrl() ?: "no navigation",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StepChips(workflow, isSelected)
    }
}

/** The workflow's shape at a glance — its step types in order, truncated if long. */
@Composable
private fun StepChips(
    workflow: Workflow,
    isSelected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val shown = workflow.steps.take(MAX_CHIPS)
    val overflow = workflow.steps.size - shown.size
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { step -> Pill(text = step.label(), isSelected = isSelected) }
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

private const val MAX_CHIPS = 3

/** The tools the scripted exchanges reach for, as the chat card's pills. */
private val CHAT_TOOLS = listOf("snapshot", "extract_rows", "extract", "evaluate")
