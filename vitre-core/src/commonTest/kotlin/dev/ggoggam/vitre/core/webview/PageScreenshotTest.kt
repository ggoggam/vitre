package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * "I can see what the page actually looks like, without it costing me a phone's worth of memory."
 *
 * This covers the half of `screenshot` that exists away from a device. The three actuals differ only
 * in how they obtain pixels; the bound they then apply is one shared function, and it is the part a
 * caller's memory and token bill depend on. `docs/PLAN.md` records that the two production bugs
 * which survived every test did so because the fake was laxer than production — so what is shared
 * between platforms is pinned here, and the platform capture itself stays honestly out of reach of
 * this suite.
 */
class PageScreenshotTest {
    @Test
    fun a_large_viewport_is_fitted_inside_the_bound_without_changing_its_shape() {
        val size = ScreenshotOptions(maxWidth = 1568, maxHeight = 1568).fit(sourceWidth = 1080, sourceHeight = 2400)

        assertTrue(size.width <= 1568 && size.height <= 1568, "was ${size.width}x${size.height}")
        assertEquals(1568, size.height, "the long edge should land on the bound, not short of it")
        // A squashed page is worse than a small one: what is being looked at is layout.
        assertEquals(1080.0 / 2400, size.width.toDouble() / size.height, 0.005)
    }

    @Test
    fun a_small_viewport_is_never_scaled_up() {
        // Upscaling adds bytes and no information, and would report a detail level that is not there.
        assertEquals(ScreenshotSize(320, 240), ScreenshotOptions(maxWidth = 4000, maxHeight = 4000).fit(320, 240))
    }

    @Test
    fun whichever_edge_binds_is_the_one_that_sets_the_scale() {
        // Wide and short: the width binds, and the height has to come down with it.
        assertEquals(ScreenshotSize(100, 20), ScreenshotOptions(maxWidth = 100, maxHeight = 1000).fit(2000, 400))
    }

    @Test
    fun an_edge_never_rounds_away_to_nothing() {
        // 1px scaled by 0.05 is 0.05px, and a zero-width bitmap is a crash on two of the three
        // platforms rather than a small picture.
        val size = ScreenshotOptions(maxWidth = 10, maxHeight = 10).fit(200, 1)

        assertEquals(ScreenshotSize(10, 1), size)
    }

    @Test
    fun options_that_could_not_produce_an_image_are_rejected_where_they_are_written() {
        assertFailsWith<IllegalArgumentException> { ScreenshotOptions(maxWidth = 0) }
        assertFailsWith<IllegalArgumentException> { ScreenshotOptions(maxHeight = -1) }
        assertFailsWith<IllegalArgumentException> { ScreenshotOptions(quality = 0) }
        assertFailsWith<IllegalArgumentException> { ScreenshotOptions(quality = 101) }
        assertFailsWith<IllegalArgumentException> { ScreenshotOptions().fit(0, 100) }
    }

    @Test
    fun two_captures_of_the_same_bytes_are_equal() {
        val one = PageScreenshot(byteArrayOf(1, 2, 3), ScreenshotFormat.Png, 10, 20)
        val same = PageScreenshot(byteArrayOf(1, 2, 3), ScreenshotFormat.Png, 10, 20)

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertNotEquals(one, PageScreenshot(byteArrayOf(1, 2, 4), ScreenshotFormat.Png, 10, 20))
    }

    @Test
    fun the_bytes_stay_out_of_toString() {
        assertEquals(
            "PageScreenshot(Jpeg, 640x480, 4096 bytes)",
            PageScreenshot(ByteArray(4096), ScreenshotFormat.Jpeg, 640, 480).toString(),
        )
    }

    @Test
    fun a_controller_with_no_pixel_path_says_so_rather_than_answering_with_nothing() =
        runTest {
            assertFailsWith<ScreenshotUnsupportedException> { NoScreenshots().screenshot() }
        }

    @Test
    fun a_capture_cannot_land_in_the_middle_of_somebody_else_s_navigation() =
        runTest {
            val controller = FakeWebViewController()
            val reachedNavigation = CompletableDeferred<Unit>()
            val releaseNavigation = CompletableDeferred<Unit>()
            controller.onNavigate = {
                reachedNavigation.complete(Unit)
                releaseNavigation.await()
            }

            val navigating = launch { controller.navigate("https://example.test/") }
            runCurrent()
            reachedNavigation.await()

            val capturing = launch { controller.screenshot() }
            runCurrent()

            // A picture taken here would be of neither document — it is an operation on the page,
            // so it queues behind the navigation like every other one.
            assertTrue(controller.screenshotRequests.isEmpty(), "the capture jumped the ordering lock")

            releaseNavigation.complete(Unit)
            navigating.join()
            capturing.join()
            assertEquals(1, controller.screenshotRequests.size)
        }

    @Test
    fun the_options_a_caller_asked_for_reach_the_controller_unchanged() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextScreenshot = { PageScreenshot(byteArrayOf(7, 7), ScreenshotFormat.Jpeg, 3, 4) }
            val asked = ScreenshotOptions(format = ScreenshotFormat.Jpeg, quality = 50, maxWidth = 640)

            val shot = controller.screenshot(asked)

            assertContentEquals(byteArrayOf(7, 7), shot.bytes)
            assertEquals(listOf(asked), controller.screenshotRequests)
        }

    @Test
    fun a_closed_controller_refuses_to_capture() =
        runTest {
            val controller = FakeWebViewController()
            controller.close()

            assertFailsWith<IllegalStateException> { controller.screenshot() }
        }

    /**
     * A controller that implements everything except the new member, which is the position every
     * `WebViewController` written outside this module is in the moment it is added. Written out
     * rather than delegated with `by`, because Kotlin's class delegation forwards members that have
     * an interface default too — it would quietly test the fake instead of the default.
     */
    private class NoScreenshots : WebViewController {
        override val bridge: WebViewBridge get() = error("not reached")

        override suspend fun navigate(url: String) = error("not reached")

        override suspend fun loadHtml(
            html: String,
            baseUrl: String?,
        ) = error("not reached")

        override suspend fun evaluateJs(script: String): String = error("not reached")

        override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = error("not reached")

        override fun close() = Unit
    }
}
