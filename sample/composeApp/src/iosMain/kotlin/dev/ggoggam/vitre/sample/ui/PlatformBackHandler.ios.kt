package dev.ggoggam.vitre.sample.ui

import androidx.compose.runtime.Composable

/** No-op: iOS has no system back gesture here, so the app bar's back button is the affordance. */
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
