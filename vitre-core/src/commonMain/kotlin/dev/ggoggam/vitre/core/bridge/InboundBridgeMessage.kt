package dev.ggoggam.vitre.core.bridge

/**
 * One message the page posted, with the facts the platform knew about who posted it.
 *
 * The raw string alone cannot answer "which frame said this?", and on a third-party page that is
 * the question that matters: an embedded ad in an iframe can post the same `{"type":"ready"}` the
 * document being driven would. [WebViewInbox] uses [fromMainFrame] to keep such a message out of
 * the consumable buffer while still reporting it here, so a host can log or display subframe
 * traffic — a subframe error is information — without it being able to satisfy a workflow's wait.
 *
 * [sourceOrigin] is the posting frame's origin as `scheme://host[:port]`, and is null when the
 * platform has no origin to give: an opaque origin (a sandboxed frame, a `data:` document — which
 * Android reports as the literal string `"null"`) or a document loaded from raw HTML with no base
 * URL. Null means "unknown", never "same origin as the main frame", so it must not be treated as
 * an allow.
 */
data class InboundBridgeMessage(
    val raw: String,
    val fromMainFrame: Boolean,
    val sourceOrigin: String? = null,
)
