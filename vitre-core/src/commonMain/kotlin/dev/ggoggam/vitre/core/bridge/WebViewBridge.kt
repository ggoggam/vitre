package dev.ggoggam.vitre.core.bridge

import kotlinx.coroutines.flow.SharedFlow

/**
 * Native ↔ page postMessage channel.
 *
 * Pages call `window.vitre.postMessage(jsonString)` to send inbound; native calls
 * [postToWebView] to dispatch a `MessageEvent('vitre', { data })` on `window`.
 *
 * Inbound traffic is offered two ways on purpose. [messages] observes without consuming — for
 * logging, a debug pane, anything that must not steal a message from the workflow. [awaitMessage]
 * consumes: it takes one buffered-or-future message and removes it, so two steps waiting on the
 * same type get two different messages rather than both matching the first one.
 */
interface WebViewBridge {
    /** Everything the page has posted since the current document loaded. Non-consuming. */
    val messages: SharedFlow<String>

    /**
     * The same traffic as [messages], tagged with the frame and origin that posted it.
     *
     * Read this one to tell an embedded iframe's message from the driven document's — the
     * distinction [awaitMessage] enforces and [messages] cannot express. Non-consuming.
     */
    val inbound: SharedFlow<InboundBridgeMessage>

    /**
     * Suspends until an unread inbound message satisfies [predicate], then consumes and returns it.
     *
     * Matches messages that arrived *before* this call as well as after, which is what makes it
     * safe to await a message the page may already have sent. Bound the wait with `withTimeout`.
     *
     * Matches **main-frame messages only**: a page being driven may embed anything, and a subframe
     * that could satisfy this wait could steal it from the document that was meant to answer.
     * Subframe traffic is not lost — it is observable on [messages] and [inbound].
     */
    suspend fun awaitMessage(predicate: (String) -> Boolean): String

    suspend fun postToWebView(message: String)
}
