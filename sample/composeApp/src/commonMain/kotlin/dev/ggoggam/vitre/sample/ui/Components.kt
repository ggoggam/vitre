package dev.ggoggam.vitre.sample.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        StepState.Failed -> scheme.error to scheme.onError
    }
}

/**
 * Small capsule summarising a run: "Running 2/3", "Completed", "Failed".
 */
@Composable
fun StatusPill(
    state: RunState,
    stepCount: Int,
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
                    "Running ${state.completedCount}/$stepCount",
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
 */
@Composable
fun StepRow(
    index: Int,
    step: WorkflowStep,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    val (fill, ink) = state.badgeColors()
    val badgeColor by animateColorAsState(fill)
    val badgeInk by animateColorAsState(ink)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

/** `key = value` row for an extracted workflow variable. */
@Composable
fun VariableRow(
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 12.dp, vertical = 8.dp),
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
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
