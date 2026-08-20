package dev.ggoggam.vitre.sample.ui

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform's own "go back" gesture while [enabled].
 *
 * Android has a system back button/gesture that must leave the runner before it leaves the app;
 * iOS has no equivalent for a non-navigation-controller screen, so the actual there does nothing
 * and the app bar's back button is the only affordance.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
