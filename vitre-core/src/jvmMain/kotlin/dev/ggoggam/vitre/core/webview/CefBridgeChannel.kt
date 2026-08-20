package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.BridgeReady
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import kotlinx.coroutines.CancellableContinuation
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Everything that travels from the page back to Kotlin on this platform, over one CEF query channel.
 *
 * CEF has no equivalent of `addWebMessageListener` or a `WKScriptMessageHandler`; what it has is
 * [CefMessageRouter], which registers a global JavaScript function in every frame's context and
 * delivers whatever string that function is called with. So both of the things the other platforms
 * keep on separate rails — page traffic for [WebViewInbox], and the value of a script the caller is
 * waiting on — come back the same way, and are told apart by a one-character prefix on the wire.
 *
 * That is not the arrangement the other platforms use, because they did not have to: Android and
 * iOS get a script's value handed straight back from their own evaluate call. CEF does not. Its
 * `executeJavaScript` is fire-and-forget with no result and no callback, which is why the script's
 * value has to make the return trip as a message like everything else. See [CefWebViewController].
 *
 * ### Why not KCEF's own `evaluateJavaScript`
 *
 * KCEF ships one, and it is the wrong shape for this library: it reports the result by string
 * concatenation (`"..." + result`), so a JS `true` arrives as `true` and an object arrives as
 * `[object Object]`. `WebViewController.evaluateJs` promises JSON on every platform — that promise
 * is what lets `WaitFor` compare against `"true"` and `Extract` decode a row array — so the wrapper
 * here does `JSON.stringify` in the page instead, which is the same encoding Android's
 * `evaluateJavascript` produces and the one `IosWebViewController` normalises WebKit onto.
 */
internal class CefBridgeChannel(
    /**
     * Distinguishes this channel's global function from any other controller's on the same client.
     *
     * The router registers `window.<name>` into every frame, so two channels sharing a name would
     * be one channel. The name is generated rather than fixed for that reason alone; nothing on the
     * page is expected to know it, because the page is given [BRIDGE_NAME] to call instead.
     */
    private val queryFunction: String = "vitreQuery${CHANNELS.incrementAndGet()}",
) {
    val router: CefMessageRouter =
        CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig(queryFunction, "${queryFunction}Cancel"))

    /** Waiting `evaluateJs` callers, keyed by the id their wrapped script carries. */
    private val pending = ConcurrentHashMap<Long, CancellableContinuation<String>>()
    private val ids = AtomicLong(0)

    /**
     * Installs `window.vitre` in a frame, wrapping this channel's query function.
     *
     * The shape is the one `BridgeReady` documents and iOS installs verbatim — Android's object
     * arrives from `addWebMessageListener` rather than from a script — so page code that checks
     * `if (window.vitre)` and posts with `window.vitre.postMessage(…)` is unchanged
     * across all three.
     *
     * **Where CEF is weaker, and it is worth knowing:** on Android and iOS this object is in place
     * *before any page script runs*, which is what makes the existence check in `BridgeReady`
     * authoritative there. CEF exposes no equivalent hook to an application — the closest thing,
     * `CefRenderProcessHandler::OnContextCreated`, lives in the render process and is not reachable
     * from Java — so here it is injected from `onLoadStart`, and that genuinely races the
     * document's own first inline script. Measured, it lands on both sides of that script across
     * runs of the same page: sometimes the `vitre:ready` event fires into a listener that was
     * registered in time, sometimes the object is simply already there and the event went out
     * before anyone was listening.
     *
     * Both orders are fine *if* the page follows the check-then-listen pattern `BridgeReady`
     * documents, which is written to handle exactly this. On the other two platforms that pattern
     * is belt-and-braces; here it is the only thing that works, so a page written against the
     * desktop target should not skip it.
     *
     * Nothing this library does depends on the difference: a settled-promise report is posted by a
     * script *we* evaluate, long after injection, and inbound page traffic is buffered by
     * [WebViewInbox] rather than dropped.
     */
    fun installScript(): String =
        """
        (function () {
            if (window.$BRIDGE_NAME) return;
            window.$BRIDGE_NAME = {
                postMessage: function (m) {
                    window.$queryFunction({
                        request: '$MESSAGE_PREFIX' + String(m),
                        onSuccess: function () {},
                        onFailure: function () {}
                    });
                }
            };
            window.dispatchEvent(new Event('${BridgeReady.EVENT_NAME}'));
        })();
        """.trimIndent()

    /**
     * Registers [continuation] against a fresh id and returns the script that will settle it.
     *
     * [expression] must be an expression, as [WebViewController.evaluateJs] requires. `?? null`
     * before the stringify maps `undefined` onto `"null"`, matching Android — `JSON.stringify`
     * hands back a JavaScript `undefined` rather than a string for that input, which would
     * otherwise reach the caller as the four letters `undefined` and decode as nothing.
     */
    fun submit(
        expression: String,
        continuation: CancellableContinuation<String>,
    ): String {
        val id = ids.incrementAndGet()
        pending[id] = continuation
        return """
            (function () {
                function report(prefix, text) {
                    // Nothing left to report a failure to: this call is how a failure is reported.
                    try {
                        window.$queryFunction({ request: prefix + '$id:' + text, onSuccess: function () {}, onFailure: function () {} });
                    } catch (e) {}
                }
                try {
                    report('$RESULT_PREFIX', JSON.stringify((function () { return ($expression); })() ?? null));
                } catch (e) {
                    report('$FAILURE_PREFIX', String((e && e.message) || e));
                }
            })();
            """.trimIndent()
    }

    /**
     * Forgets every waiting evaluation, because the document they were submitted against has gone.
     *
     * The continuations themselves are not resumed: `WebViewSerializer` is already watching for the
     * navigation and will cancel and resubmit the one caller that is still interested. This only
     * stops the table growing by one entry per lost script for the life of the lane.
     */
    fun clearPending() = pending.clear()

    /** The handler to register on the router; see [onQuery] for the routing rules. */
    fun handler(
        inbox: WebViewInbox,
        scriptResults: ScriptResults,
        ownedBy: () -> Int,
    ): CefMessageRouterHandlerAdapter =
        object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser?,
                frame: CefFrame?,
                queryId: Long,
                request: String?,
                persistent: Boolean,
                callback: CefQueryCallback?,
            ): Boolean {
                // A router is registered on the *client*, and a client may have more than one
                // browser, so a query has to be claimed by the controller whose lane it came from
                // or two lanes would answer each other's scripts.
                if (browser == null || browser.identifier != ownedBy()) return false
                val raw = request ?: return false
                val fromMainFrame = frame?.isMain == true
                // Answered before anything else can throw: a query left un-answered stays alive in
                // the router until the frame goes away, and the page's `onSuccess` never fires.
                callback?.success("")
                route(raw, fromMainFrame, frame?.url, inbox, scriptResults)
                return true
            }
        }

    private fun route(
        raw: String,
        fromMainFrame: Boolean,
        frameUrl: String?,
        inbox: WebViewInbox,
        scriptResults: ScriptResults,
    ) {
        when {
            // A script this channel evaluates runs in the main frame, so a settle report is only
            // genuine from the main frame. Without this gate a subframe could resolve or reject an
            // in-flight evaluateJs by guessing the id (a plain counter) and the query-function name:
            // the async settle plane is protected by AsyncScript's nonce, but this synchronous one
            // never was. Android and iOS get the value back off the platform's own evaluate callback,
            // so only the desktop routes it over a channel a subframe can also reach.
            raw.startsWith(RESULT_PREFIX) -> {
                if (fromMainFrame) settle(raw.removePrefix(RESULT_PREFIX)) { it }
            }

            raw.startsWith(FAILURE_PREFIX) -> {
                if (fromMainFrame) settle(raw.removePrefix(FAILURE_PREFIX)) { throw ScriptFailedException(it) }
            }

            raw.startsWith(MESSAGE_PREFIX) -> {
                val payload = raw.removePrefix(MESSAGE_PREFIX)
                // The settle plane gets first refusal, exactly as on the other two platforms: a
                // settled-promise report never reaches the inbox, and only a main-frame report can
                // be credited. See ScriptResults.deliver.
                if (!scriptResults.deliver(payload, fromMainFrame)) {
                    inbox.deliver(payload, fromMainFrame = fromMainFrame, sourceOrigin = frameUrl?.let(::originOfUrl))
                }
            }
        }
    }

    /**
     * Resumes the caller waiting on `<id>:<text>`, if one still is.
     *
     * A missing entry is the ordinary case rather than an error: the caller may have timed out, or
     * `WebViewSerializer` may have given up on this document and resubmitted under a new id.
     */
    private inline fun settle(
        body: String,
        value: (String) -> String,
    ) {
        val id = body.substringBefore(':').toLongOrNull() ?: return
        val text = body.substringAfter(':')
        val continuation = pending.remove(id) ?: return
        val outcome = runCatching { value(text) }
        outcome.fold(
            onSuccess = { if (continuation.isActive) continuation.resume(it) },
            onFailure = { failure -> if (continuation.isActive) continuation.resumeWith(Result.failure(failure)) },
        )
    }

    /** Frees the native router. The client must have removed it first. */
    fun dispose() {
        pending.clear()
        router.dispose()
    }

    companion object {
        const val BRIDGE_NAME: String = "vitre"

        /** One character each, on the front of every message, so the three kinds cannot be confused. */
        private const val MESSAGE_PREFIX = "m:"
        private const val RESULT_PREFIX = "e:"
        private const val FAILURE_PREFIX = "x:"

        private val CHANNELS = AtomicLong(0)
    }
}

/** `https://shop.example:8443/a` → `https://shop.example:8443`, or null if [url] has no authority. */
private fun originOfUrl(url: String): String? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty()) return null
    val authority =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    return if (authority.isEmpty()) null else "$scheme://$authority"
}
