package dev.ggoggam.vitre.sample.ui

import androidx.compose.runtime.Composable

/**
 * No-op: a desktop window has no system back gesture, so the app bar's back button is the
 * affordance — the same answer iOS gives, for the same reason.
 *
 * In practice this rarely fires at all on desktop: the gallery only takes turns between the list
 * and the runner below its two-pane breakpoint, and a desktop window is almost always wider.
 */
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
