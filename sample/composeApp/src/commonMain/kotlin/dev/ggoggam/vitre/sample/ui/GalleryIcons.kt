package dev.ggoggam.vitre.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// The three glyphs the gallery needs, drawn inline.
//
// material-icons-core is not on this project's classpath, and pulling the artifact in just for
// a back arrow, a refresh and a chevron would put a dependency in the sample that says nothing
// about the library. The path data is the standard Material 24dp outline.

private fun galleryIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathBuilder = block)
        .build()

val ArrowBackIcon: ImageVector by lazy {
    galleryIcon("ArrowBack") {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineTo(13.42f, 5.41f)
        lineTo(12f, 4f)
        lineTo(4f, 12f)
        lineTo(12f, 20f)
        lineTo(13.41f, 18.59f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        verticalLineTo(11f)
        close()
    }
}

/** Points up. The sheet header rotates it when the detail is already at full height. */
val ExpandLessIcon: ImageVector by lazy {
    galleryIcon("ExpandLess") {
        moveTo(12f, 8f)
        lineTo(6f, 14f)
        lineTo(7.41f, 15.41f)
        lineTo(12f, 10.83f)
        lineTo(16.59f, 15.41f)
        lineTo(18f, 14f)
        close()
    }
}

val RefreshIcon: ImageVector by lazy {
    galleryIcon("Refresh") {
        moveTo(17.65f, 6.35f)
        curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
        curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -8f, 8f)
        reflectiveCurveToRelative(3.58f, 8f, 8f, 8f)
        curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
        horizontalLineToRelative(-2.08f)
        curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
        curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
        reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
        curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
        lineTo(13f, 11f)
        horizontalLineToRelative(7f)
        verticalLineTo(4f)
        close()
    }
}

/** The chat pane's send button. */
val SendIcon: ImageVector by lazy {
    galleryIcon("Send") {
        moveTo(2.01f, 21f)
        lineTo(23f, 12f)
        lineTo(2.01f, 3f)
        lineTo(2f, 10f)
        lineToRelative(15f, 2f)
        lineToRelative(-15f, 2f)
        close()
    }
}
