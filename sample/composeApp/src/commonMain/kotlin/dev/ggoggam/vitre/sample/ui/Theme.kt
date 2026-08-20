package dev.ggoggam.vitre.sample.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One neutral grey ramp carries the chrome — no brand hue, so the gallery stays out of the way and
// the page under test is the only thing on screen wearing colour for its own sake. Emphasis comes
// from ink instead: the loudest surface in the light theme is near-black, and in the dark theme
// near-white — the same element, inverted, rather than a second accent.
//
// The scheme roles carry the run states the sample needs, which keeps the step list free of
// hand-rolled colour constants. Hue is spent only where an outcome has to be read at a glance:
//   primary   → emphasis: the selected card, the user's own turn, "this step is running"
//   tertiary  → "this step succeeded"
//   error     → "this step failed"
//
// Running is the one state told apart by lightness rather than hue — it is the neutral ink, a
// running spinner beside it, and it turns green or red the moment it settles.

private val Grey50 = Color(0xFFFAFAFA)
private val Grey100 = Color(0xFFF4F4F5)
private val Grey150 = Color(0xFFF0F0F1)
private val Grey200 = Color(0xFFE4E4E7)
private val Grey400 = Color(0xFFA1A1AA)
private val Grey600 = Color(0xFF52525B)
private val Grey700 = Color(0xFF3F3F46)
private val Grey800 = Color(0xFF27272A)
private val Grey850 = Color(0xFF202024)
private val Grey900 = Color(0xFF18181B)
private val Grey925 = Color(0xFF131316)
private val Grey950 = Color(0xFF09090B)
private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

private val Green100 = Color(0xFFDCFCE7)
private val Green300 = Color(0xFF86EFAC)
private val Green500 = Color(0xFF22C55E)
private val Green700 = Color(0xFF15803D)
private val Green900 = Color(0xFF14532D)
private val Green950 = Color(0xFF052E16)

private val Red100 = Color(0xFFFEE2E2)
private val Red300 = Color(0xFFFCA5A5)
private val Red600 = Color(0xFFDC2626)
private val Red800 = Color(0xFF991B1B)
private val Red950 = Color(0xFF3B0D0D)

private val LightColors =
    lightColorScheme(
        primary = Grey900,
        onPrimary = Grey50,
        primaryContainer = Grey900,
        onPrimaryContainer = Grey50,
        inversePrimary = Grey50,
        secondary = Grey600,
        onSecondary = White,
        secondaryContainer = Grey200,
        onSecondaryContainer = Grey900,
        tertiary = Green700,
        onTertiary = White,
        tertiaryContainer = Green100,
        onTertiaryContainer = Green900,
        error = Red600,
        onError = White,
        errorContainer = Red100,
        onErrorContainer = Red800,
        background = White,
        onBackground = Grey950,
        surface = White,
        onSurface = Grey950,
        surfaceVariant = Grey100,
        onSurfaceVariant = Grey600,
        surfaceTint = Grey900,
        surfaceBright = White,
        surfaceDim = Grey200,
        surfaceContainerLowest = White,
        surfaceContainerLow = Grey50,
        surfaceContainer = Grey100,
        surfaceContainerHigh = Grey150,
        surfaceContainerHighest = Grey200,
        inverseSurface = Grey900,
        inverseOnSurface = Grey50,
        outline = Grey400,
        outlineVariant = Grey200,
        scrim = Black,
    )

private val DarkColors =
    darkColorScheme(
        primary = Grey50,
        onPrimary = Grey900,
        primaryContainer = Grey50,
        onPrimaryContainer = Grey900,
        inversePrimary = Grey900,
        secondary = Grey400,
        onSecondary = Grey900,
        secondaryContainer = Grey800,
        onSecondaryContainer = Grey50,
        tertiary = Green500,
        onTertiary = Green950,
        tertiaryContainer = Green900,
        onTertiaryContainer = Green300,
        error = Red600,
        onError = White,
        errorContainer = Red950,
        onErrorContainer = Red300,
        background = Grey950,
        onBackground = Grey50,
        surface = Grey950,
        onSurface = Grey50,
        surfaceVariant = Grey800,
        onSurfaceVariant = Grey400,
        surfaceTint = Grey50,
        surfaceBright = Grey700,
        surfaceDim = Grey950,
        surfaceContainerLowest = Grey950,
        surfaceContainerLow = Grey925,
        surfaceContainer = Grey900,
        surfaceContainerHigh = Grey850,
        surfaceContainerHighest = Grey800,
        inverseSurface = Grey50,
        inverseOnSurface = Grey900,
        outline = Grey600,
        outlineVariant = Grey800,
        scrim = Black,
    )

@Composable
fun VitreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
