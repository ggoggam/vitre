package dev.ggoggam.vitre.mcp.testing

import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController

/**
 * A WebView that answers scripts from a lookup table.
 *
 * Written against the same public surface a host app implements, and using the library's own
 * [WebViewOrdering] rather than a lock of its own — a double with weaker ordering than production
 * would make the lease tests below prove nothing.
 */
class FakePageController : WebViewController {
    val navigations = mutableListOf<String>()
    val evaluatedScripts = mutableListOf<String>()

    /** Answers `evaluateJs`. Receives the script, returns the JSON-encoded result. */
    var respond: (String) -> String = { "null" }

    private val inbox = WebViewInbox()
    private val order = WebViewOrdering()

    override val bridge: WebViewBridge = DefaultWebViewBridge(inbox) { script -> evaluateJs(script) }

    override suspend fun navigate(url: String) =
        order.ordered {
            navigations += url
        }

    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) = order.ordered { }

    override suspend fun evaluateJs(script: String): String =
        order.ordered {
            evaluatedScripts += script
            respond(script)
        }

    override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

    /** Nothing is installed on this double, so closing only records that it happened. */
    var closed = false
        private set

    override fun close() {
        closed = true
    }

    fun simulatePageMessage(message: String) = inbox.deliver(message)
}
