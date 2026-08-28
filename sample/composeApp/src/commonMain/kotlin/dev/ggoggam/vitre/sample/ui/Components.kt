package dev.ggoggam.vitre.sample.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ggoggam.vitre.core.workflow.WorkflowStep

/**
 * Fill and numeral colour for a step's index badge.
 *
 * Pending is the odd one out: its fill is pale enough that the surface-coloured numeral the
 * other states use would be invisible on it, so it gets body-text ink instead.
 */
@Composable
private fun StepState.badgeColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        StepState.Pending -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant

        StepState.Running -> scheme.primary to scheme.onPrimary

        StepState.Done -> scheme.tertiary to scheme.onTertiary

        // Dimmer than pending, because a skipped step is settled rather than still to come.
        StepState.Skipped -> scheme.surfaceContainerHigh to scheme.outline

        StepState.Failed -> scheme.error to scheme.onError
    }
}

/**
 * Small capsule summarising a run: "Running 2/3", "Completed", "Failed".
 */
@Composable
fun StatusPill(
    state: RunState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (label, fg, bg) =
        when (state.status) {
            RunStatus.Idle -> {
                Triple("Idle", scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
            }

            RunStatus.Running -> {
                Triple(
                    "Running ${state.completedCount}/${state.stepCount}",
                    scheme.onPrimaryContainer,
                    scheme.primaryContainer,
                )
            }

            RunStatus.Completed -> {
                Triple("Completed", scheme.onTertiaryContainer, scheme.tertiaryContainer)
            }

            RunStatus.Failed -> {
                Triple("Failed", scheme.onErrorContainer, scheme.errorContainer)
            }
        }

    Row(
        modifier = modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.status == RunStatus.Running) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = fg)
        }
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * One row of the step timeline: a state-coloured index badge, the step type, and its arguments.
 *
 * [depth] is how many `If` branches the step sits inside, and it is rendered as an indent rather
 * than as a label. The badge restarts at 1 inside a branch, which is what the step's own path says
 * and what makes a `0.then.1` in a failure message findable by eye.
 */
@Composable
fun StepRow(
    index: Int,
    step: WorkflowStep,
    state: StepState,
    modifier: Modifier = Modifier,
    depth: Int = 0,
) {
    val (fill, ink) = state.badgeColors()
    val badgeColor by animateColorAsState(fill)
    val badgeInk by animateColorAsState(ink)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp + (depth * 20).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(22.dp).background(badgeColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state == StepState.Failed) "!" else "${index + 1}",
                color = badgeInk,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.label(),
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (state == StepState.Pending) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                text = step.detail(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `key = value` row for an extracted workflow variable.
 *
 * Extracted values are routinely longer than the few lines a row can spare, so the collapsed row
 * caps at [maxLines] and tapping it swaps in the whole value. The tap target and the hint only
 * appear once the text has actually been clipped — a value that already fits has nothing to reveal.
 */
@Composable
fun VariableRow(
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
) {
    var expanded by remember(value) { mutableStateOf(false) }
    var clipped by remember(value) { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp),
                ).let { if (clipped) it.clickable { expanded = !expanded } else it }
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value.ifBlank { "(empty)" },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
            // Latches: once the collapsed measure reports overflow the row stays tappable, so
            // expanding (which by definition no longer overflows) cannot strand it open.
            onTextLayout = { layout -> if (layout.hasVisualOverflow) clipped = true },
        )
        if (clipped) {
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
