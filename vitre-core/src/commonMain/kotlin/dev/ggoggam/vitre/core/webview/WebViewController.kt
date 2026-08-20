package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.bridge.WebViewBridge

/** How long [WebViewController.navigate] waits for a page to settle before giving up. */
const val DEFAULT_NAVIGATION_TIMEOUT_MS: Long = 30_000L

/**
 * Platform-agnostic facade over a hosted WebView. Workflows drive a page through this.
 *
 * Implementations: `AndroidWebViewController` (wraps `android.webkit.WebView`),
 * `IosWebViewController` (wraps `WKWebView`), and the test-only `FakeWebViewController`.
 *
 * The platform implementations own the WebView's client/navigation delegate — that is how they
 * observe page loads — so hosts must not install one of their own on top.
 */
interface WebViewController {
    val bridge: WebViewBridge

    /**
     * Loads [url] and suspends until the page has finished loading.
     *
     * Awaiting is not a convenience: committing a new document tears down the old JavaScript
     * context, and anything already submitted through [evaluateJs] against the outgoing page is
     * dropped without ever invoking its callback. A fire-and-forget navigate therefore leaves the
     * next step of a workflow waiting on a result that can never arrive.
     *
     * @throws PageLoadException if the main frame reports a load failure.
     */
    suspend fun navigate(url: String)

    /**
     * Loads [html] directly and suspends until it has finished loading, with the same rationale for
     * awaiting as [navigate].
     *
     * This is how a workflow gets a page it controls. `data:` URLs are not a substitute: WebKit
     * refuses top-level navigation to them, so anything built that way works on Android and
     * silently does nothing on iOS.
     *
     * @param baseUrl origin to resolve relative URLs against, and — on Android — the origin the
     *   bridge's allowed-origin rule is matched against. Null gives the document an opaque origin.
     * @throws PageLoadException if the load fails.
     */
    suspend fun loadHtml(
        html: String,
        baseUrl: String? = null,
    )

    /**
     * Evaluates [script] and returns its **settled** result **JSON-encoded** — `true`,
     * `"some text"`, `null`.
     *
     * The encoding is part of the contract because the platforms do not agree on one by default,
     * and the disagreement is silent rather than fatal. Callers can rely on a JS boolean arriving
     * as exactly `"true"` on both.
     *
     * A promise is awaited, not returned: a script built on `fetch` yields its value here the same
     * as a synchronous one, rather than the `{}` a promise serialises to. There is no opt-out —
     * `await` on a plain value is a no-op, so the synchronous case is unchanged, and an opt-in
     * flag is how the two platforms once came to disagree about what the same workflow returns.
     *
     * [script] must be an expression; wrap statements in an IIFE.
     *
     * @throws ScriptTimeoutException if the result does not arrive in time.
     * @throws ScriptFailedException if the script's promise rejected.
     */
    suspend fun evaluateJs(script: String): String

    /**
     * Runs [block] with this WebView to itself — no other caller's operation can land part-way
     * through the sequence it performs.
     *
     * Ordinary calls are already serialised, so this is not about corruption; it is about sequences.
     * `WaitFor` then `Extract` is only meaningful if nothing ran in between, and nothing in a single
     * operation's ordering guarantee says so. Two agent tool calls, a workflow, and a "reload" button
     * are four independent callers, and the gaps between one caller's steps are exactly where the
     * other three run.
     *
     * The claim is reentrant and released when [block] returns, however it returns. Calls made
     * inside [block] need no ceremony — the claim travels with the coroutine context, including
     * through a [dev.ggoggam.vitre.core.workflow.WorkflowEngine] run collected inside it. Only a caller
     * that has to reach in from a *different* coroutine needs [ExclusiveAccess.use].
     */
    suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T

    /**
     * Releases what this controller installed on its WebView — the message listener, the injected
     * scripts, the navigation delegate. Idempotent: a second call does nothing.
     *
     * Call on the main thread, after the WebView has left the view hierarchy. It matters that
     * something calls it: on iOS a `WKUserContentController` holds its script message handler
     * strongly, so a controller that is never closed keeps itself — and the whole page — alive for
     * as long as the WebView's configuration lives.
     *
     * Afterwards, a new [navigate] / [loadHtml] / [evaluateJs] / [exclusively] fails with
     * [IllegalStateException] rather than reaching a WebView whose bridge has been taken off it.
     * Operations already in flight are left alone and settle on their existing timeouts — there is
     * deliberately no shutdown path through the serializer, because every in-flight operation is
     * already time-bounded and a shutdown that cancelled them would only replace one bounded wait
     * with a new class of failure.
     *
     * Destroying the WebView itself stays the owner's job: this class did not create it, and on
     * Android `destroy()` before detach crashes. `FramePool` lanes are not closed by anyone yet.
     */
    fun close()
}

/** A main-frame navigation failed or timed out; [message] describes which. */
class PageLoadException(
    message: String,
) : RuntimeException(message)

/**
 * A script was submitted but no result came back in time.
 *
 * Usually means the document it was evaluated against went away — a redirect, a `meta refresh`, a
 * click that navigated — because both platforms drop the pending callback in that case instead of
 * reporting an error.
 */
class ScriptTimeoutException(
    message: String,
) : RuntimeException(message)

/**
 * A script ran and its result was an error rather than a value.
 *
 * Distinct from [ScriptTimeoutException], which means no answer arrived at all. This one is the
 * page's own failure — a rejected promise — reported with the page's own message, and it exists
 * because the alternative is a step that quietly stores `null` and carries on.
 */
class ScriptFailedException(
    message: String,
) : RuntimeException(message)
