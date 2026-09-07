package dev.ggoggam.vitre.core.webview

import kotlin.math.roundToInt

/**
 * The bound a screenshot's edges are fitted inside by default, in pixels.
 *
 * 1568 is not arbitrary and it is not a display size: it is the longest edge the major vision APIs
 * downscale an image to before a model ever sees it. Anything larger is therefore paid for three
 * times — allocated, encoded, base64'd over the wire — for pixels that are thrown away on arrival.
 *
 * It is a *bound*, not a target. A viewport smaller than this is never scaled up; see
 * [ScreenshotOptions.fit].
 */
const val DEFAULT_SCREENSHOT_MAX_DIMENSION: Int = 1568

/** JPEG quality when [ScreenshotFormat.Jpeg] is asked for. Ignored by PNG, which is lossless. */
const val DEFAULT_SCREENSHOT_QUALITY: Int = 80

/**
 * How a screenshot is encoded on its way out of the WebView.
 *
 * Two entries rather than an open string because these are the only two encodings all three
 * platforms produce without a new dependency — `Bitmap.compress`, `UIImagePNGRepresentation` /
 * `UIImageJPEGRepresentation`, and Chrome DevTools' `Page.captureScreenshot` each offer exactly
 * these. WebP would save real bytes and would mean shipping an encoder to iOS, which is not a
 * dependency a core library should acquire on a caller's behalf.
 */
enum class ScreenshotFormat {
    /**
     * Lossless. The default, because the thing a page mostly contains is text and UI edges, and
     * those are what JPEG's ringing destroys first — a chart's gridlines and a button's label are
     * exactly the detail a screenshot is being taken for.
     */
    Png,

    /**
     * Lossy, and much smaller for a photographic or map-heavy page — often five to ten times.
     * Worth choosing when the bytes are going to a model and the page is imagery rather than text.
     */
    Jpeg,
}

/** A pixel size. Not a platform type, because commonMain has none and this crosses to all three. */
data class ScreenshotSize(
    val width: Int,
    val height: Int,
)

/**
 * What to capture and how large to let it get.
 *
 * The size bound is the part that matters and the reason this is not a bare `screenshot()`. A
 * viewport screenshot is a bitmap the size of a phone screen — a 1080×2400 device is ~10MB at
 * ARGB_8888 — and if it is going anywhere near a model it is then base64'd, which adds a third
 * again. Both costs scale with pixel count, and neither is one a caller can undo afterwards, so the
 * bound is applied *while capturing* on every platform rather than by resampling a full-size image.
 */
data class ScreenshotOptions(
    val format: ScreenshotFormat = ScreenshotFormat.Png,
    /** 1..100. Ignored for [ScreenshotFormat.Png]. */
    val quality: Int = DEFAULT_SCREENSHOT_QUALITY,
    val maxWidth: Int = DEFAULT_SCREENSHOT_MAX_DIMENSION,
    val maxHeight: Int = DEFAULT_SCREENSHOT_MAX_DIMENSION,
) {
    init {
        require(quality in 1..100) { "quality must be in 1..100, was $quality" }
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }

    /**
     * The size a source of [sourceWidth] × [sourceHeight] device pixels should be captured at.
     *
     * **This is the rule, and there is exactly one copy of it.** All three actuals call it, and so
     * does the test double, which is the part that matters: three hand-rolled rounding rules are
     * three chances for one platform to hand back an image a pixel wider than the others, and a
     * fake with a fourth is how a size bug hides from the tests written to catch it — the failure
     * `docs/PLAN.md` records twice. It is public for the same reason
     * [dev.ggoggam.vitre.core.concurrent.WebViewOrdering] is: a `WebViewController` written outside
     * this module would otherwise have to reinvent it to satisfy its own interface.
     *
     * Scaled uniformly so the aspect ratio survives — a squashed page is worse than a small one,
     * because the thing being looked at is layout. **Never upscaled**: enlarging a small viewport
     * adds bytes and no information, and a caller reading [PageScreenshot.width] would be told a
     * detail level the capture does not actually have.
     */
    fun fit(
        sourceWidth: Int,
        sourceHeight: Int,
    ): ScreenshotSize {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "source size must be positive, was ${sourceWidth}x$sourceHeight"
        }
        val scale =
            minOf(
                1.0,
                maxWidth.toDouble() / sourceWidth,
                maxHeight.toDouble() / sourceHeight,
            )
        return ScreenshotSize(
            width = (sourceWidth * scale).roundToInt().coerceIn(1, minOf(sourceWidth, maxWidth)),
            height = (sourceHeight * scale).roundToInt().coerceIn(1, minOf(sourceHeight, maxHeight)),
        )
    }
}

/**
 * A captured picture of the page, encoded, with the size it actually came out at.
 *
 * [bytes] rather than a platform bitmap because commonMain has no image type and the three
 * platforms' own — `Bitmap`, `UIImage`, `BufferedImage` — cannot cross the boundary. Encoded rather
 * than raw pixels because every consumer this exists for wants a file or a base64 blob: writing it
 * to disk, showing it in the sample, or attaching it to a model request. Raw ARGB would make all
 * three of those the caller's problem and would be four times the size on the wire.
 *
 * [width] and [height] are reported rather than left for the caller to decode out of the PNG
 * header, because they are the one thing a caller needs to reason about cost, and because they are
 * how a caller finds out that [ScreenshotOptions.maxWidth] bit.
 */
class PageScreenshot(
    val bytes: ByteArray,
    val format: ScreenshotFormat,
    val width: Int,
    val height: Int,
) {
    // Generated equals/hashCode compare ByteArray by identity, which makes two captures of the same
    // bytes unequal and is never what a caller means. Compared by content instead.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PageScreenshot &&
                    format == other.format &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    /** Deliberately does not print [bytes]: a screenshot in a log line is a megabyte of noise. */
    override fun toString(): String = "PageScreenshot($format, ${width}x$height, ${bytes.size} bytes)"
}
