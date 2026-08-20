package dev.ggoggam.vitre.core.bridge

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Only the constants are testable here, and that is the whole of what commonMain owns. Whether the
// bridge is really installed before the page's first script is a property of the platform
// injections — a `WKUserScript` at document start on iOS, `addWebMessageListener` on Android —
// which can only be observed with a real WebView, and the sample's `bridge-round-trip` smoke run is
// what covers those two. The desktop is not in that set and cannot be: CEF injects from
// `onLoadStart`, which races the page's first script by design, so a green smoke run there proves
// only that the check-then-listen pattern held on that run. See BridgeReady.
class BridgeReadyTest {
    @Test
    fun ready_event_name_does_not_clash_with_the_message_event_name() {
        // Two events travel on `window`, and a page listens to them for opposite reasons: one says
        // the bridge exists, the other carries native→page data. Sharing a name would wake every
        // `MessageEvent('vitre')` handler with an `Event` that has no `data` at all.
        assertNotEquals(
            DefaultWebViewBridge.EVENT_NAME,
            BridgeReady.EVENT_NAME,
            "the ready event and the delivery event share a name",
        )
    }

    @Test
    fun announce_script_guards_on_the_bridge_object_existing() {
        val script = BridgeReady.announceScript

        // The event is a claim about `window.vitre`. Dispatching it unguarded — from a
        // document-start script that ran somewhere the bridge was never installed — would tell the
        // page something it is then invited to act on and cannot verify.
        assertTrue("if (window.vitre)" in script, "the announcement is not guarded on the bridge: $script")
        assertTrue("new Event('vitre:ready')" in script, "the announcement does not dispatch the ready event: $script")
    }
}
