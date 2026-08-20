package dev.ggoggam.vitre.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.ggoggam.vitre.core.webview.CefSurface
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.event.KeyEvent
import kotlin.math.roundToInt

/**
 * Draws a lane, and sends it what the user does to it.
 *
 * This is ordinary Compose content — a `Canvas` with an image in it — which is the entire point.
 * The page participates in z-order, clipping, scrolling and animation like anything else the host
 * draws, so a bottom sheet over the page is a sheet over the page rather than a sheet hidden behind
 * a native window. [CefSurface] has the longer version of why that mattered enough to render
 * offscreen.
 *
 * What is given up, and it is worth being explicit: this is a copy per painted frame rather than
 * Chromium compositing straight to the screen, so a lane playing video costs more here than it
 * would windowed. For pages being *automated* — which is what a lane is for — that is close to
 * nothing, because CEF paints only when something changes.
 */
@Composable
internal fun CefSurfaceView(
    surface: CefSurface,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val focusRequester = remember { FocusRequester() }
    val frame by surface.frames.collectAsState()
    val popup by surface.popup.collectAsState()

    // Keyed on the frame, so a redraw caused by anything other than the page — a sibling animating,
    // a window resize — reuses the bitmap instead of rebuilding a full-surface one.
    val pageImage = remember(frame) { frame?.toImageBitmap() }
    val popupImage = remember(popup) { popup?.frame?.toImageBitmap() }

    DisposableEffect(surface) {
        onDispose { surface.setFocus(false) }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Logical pixels: CEF multiplies by the device scale factor itself, so handing
                    // it device pixels makes a HiDPI lane render at twice the size and show a
                    // quarter of the page.
                    surface.resize(
                        width = (coordinates.size.width / density).roundToInt(),
                        height = (coordinates.size.height / density).roundToInt(),
                        deviceScaleFactor = density.toDouble(),
                    )
                    val origin = coordinates.positionInWindow()
                    surface.setScreenOrigin(
                        x = (origin.x / density).roundToInt(),
                        y = (origin.y / density).roundToInt(),
                    )
                }.focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { surface.setFocus(it.isFocused) }
                .onKeyEvent { event ->
                    (event.nativeKeyEvent as? KeyEvent)?.let { surface.dispatch(it) }
                    // Deliberately not consumed: the host's own shortcuts should still see it.
                    false
                }.pointerInput(surface, density) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val x = (change.position.x / density).roundToInt()
                            val y = (change.position.y / density).roundToInt()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    // Clicking a page is asking to type into it.
                                    focusRequester.requestFocus()
                                    surface.pointerPressed(x, y)
                                }

                                PointerEventType.Release -> {
                                    surface.pointerReleased(x, y)
                                }

                                PointerEventType.Move -> {
                                    surface.pointerMoved(x, y, pressed = change.pressed)
                                }

                                PointerEventType.Scroll -> {
                                    val delta = change.scrollDelta
                                    if (delta != Offset.Zero) surface.scrolled(x, y, -delta.y)
                                }

                                else -> {
                                    Unit
                                }
                            }
                        }
                    }
                },
    ) {
        pageImage?.let { fill(it) }
        // Over the page, at the position CEF asked for: a `<select>` dropdown is a surface of its
        // own, and without this it opens, swallows clicks, and is invisible.
        popupImage?.let { image ->
            popup?.bounds?.let { bounds ->
                translate(left = bounds.x * density, top = bounds.y * density) {
                    drawImage(image = image, dstSize = IntSize(image.width, image.height))
                }
            }
        }
    }
}

/** BGRA premultiplied, which is what CEF paints and what Skia wraps without a conversion pass. */
private fun CefSurface.Frame.toImageBitmap(): ImageBitmap =
    Image
        .makeRaster(
            imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
            bytes = pixels,
            rowBytes = width * BYTES_PER_PIXEL,
        ).toComposeImageBitmap()

/**
 * Stretches the frame across the whole lane rather than drawing it 1:1.
 *
 * They are the same size in the steady state. They differ for the frame or two after a resize,
 * where the alternative to stretching is a strip of blank beside a page that has not been
 * repainted yet — which reads as a rendering fault rather than as a resize in progress.
 */
private fun DrawScope.fill(image: ImageBitmap) {
    drawImage(image = image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
}

private const val BYTES_PER_PIXEL = 4
