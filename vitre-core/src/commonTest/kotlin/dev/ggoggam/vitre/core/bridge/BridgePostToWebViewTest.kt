package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgePostToWebViewTest {
    @Test
    fun postToWebView_dispatches_message_event_with_escaped_data() =
        runTest {
            val controller = FakeWebViewController()

            controller.bridge.postToWebView("""hello "world"""")

            val script = controller.evaluatedScripts.single()
            assertTrue("window.dispatchEvent" in script, "missing dispatchEvent: $script")
            assertTrue("MessageEvent('vitre'" in script, "missing event name: $script")
            assertTrue("""hello \"world\"""" in script, "payload not escaped: $script")
        }

    @Test
    fun dispatchScript_helper_matches_expected_shape() {
        val script = DefaultWebViewBridge.dispatchScript("ping")
        assertEquals(
            "window.dispatchEvent(new MessageEvent('vitre',{data:\"ping\"}))",
            script,
        )
    }
}
