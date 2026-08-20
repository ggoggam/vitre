package dev.ggoggam.vitre.core.bridge

import dev.ggoggam.vitre.core.webview.AsyncScript
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json

/**
 * The bridge implementation shared by every controller. Inbound traffic is delegated to [inbox];
 * outbound messages become a `window.dispatchEvent(new MessageEvent('vitre', { data: … }))`
 * call routed through the controller's `evaluateJs`, and so are ordered against every other
 * WebView operation by the controller's serializer.
 */
class DefaultWebViewBridge(
    private val inbox: WebViewInbox,
    private val evaluateJs: suspend (String) -> String,
) : WebViewBridge {
    override val messages: SharedFlow<String> get() = inbox.messages

    override val inbound: SharedFlow<InboundBridgeMessage> get() = inbox.inbound

    override suspend fun awaitMessage(predicate: (String) -> Boolean): String = inbox.awaitMatching(predicate)

    override suspend fun postToWebView(message: String) {
        // The reserved settle-plane type is refused at this one choke point, because every send door
        // funnels through here: `bridge.post`/`request`, the workflow DSL's `postMessage`, a raw
        // `PostMessage` step, and the MCP `send_message` tool. `ScriptResults` claims any inbound
        // `script:result` before the inbox sees it, so a caller that sent one and then awaited its
        // reply would hang to the timeout with nothing naming the cause. A non-envelope string is not
        // a match and passes through untouched.
        val type = runCatching { RESERVED_JSON.decodeFromString(BridgeMessage.serializer(), message).type }.getOrNull()
        require(type != AsyncScript.RESULT_TYPE) {
            "\"${AsyncScript.RESULT_TYPE}\" is reserved for the settle plane and cannot be sent over the bridge"
        }
        evaluateJs(dispatchScript(message))
    }

    companion object {
        const val EVENT_NAME: String = "vitre"

        fun dispatchScript(message: String): String = "window.dispatchEvent(new MessageEvent('$EVENT_NAME',{data:${jsString(message)}}))"

        private val RESERVED_JSON = Json { ignoreUnknownKeys = true }
    }
}
