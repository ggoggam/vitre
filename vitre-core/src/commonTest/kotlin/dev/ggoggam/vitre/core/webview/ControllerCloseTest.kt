package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * "The screen closed, so the WebView it was driving stops being drivable."
 *
 * A controller whose host has gone away is not a controller with nothing to do — on Android its
 * message listener has been taken off, on iOS its message handler has, and the WebView underneath
 * it is on its way to being destroyed. What is asserted here is the contract that makes that safe
 * to rely on: the operations fail loudly instead of reaching a WebView in that state, and closing
 * twice is not an error, because the host that closes on unmount and the host that closes again on
 * its own teardown are both correct.
 *
 * What is *not* asserted here is the platform half — that the listener really comes off, that the
 * retain cycle really breaks, that `destroy()` really lands after detach. None of it is reachable
 * without a device, and there is no Compose test source set to mount the composable in either. The
 * sample's mount → run → back → re-mount is the smoke test for that half.
 */
class ControllerCloseTest {
    @Test
    fun operations_after_close_fail_rather_than_touch_a_dead_webview() =
        runTest {
            val controller = FakeWebViewController()
            controller.close()

            // Each of the three is checked separately rather than trusting one to stand for the
            // others: the guard is per-entry-point, so it is per-entry-point that it can be missed.
            assertFailsWith<IllegalStateException> { controller.navigate("https://example.com") }
            assertFailsWith<IllegalStateException> { controller.loadHtml("<p>hi</p>") }
            assertFailsWith<IllegalStateException> { controller.evaluateJs("1 + 1") }
        }

    @Test
    fun close_is_idempotent() =
        runTest {
            val controller = FakeWebViewController()

            // The realistic path to a double close is two owners each doing the right thing — a
            // composable releasing on unmount and a screen model closing what it was handed — so
            // the second call has to be a no-op rather than the price of being thorough.
            controller.close()
            controller.close()

            assertFailsWith<IllegalStateException> { controller.evaluateJs("1 + 1") }
        }
}
