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
     * The cookie jar this WebView's session lives in, or null where there is not one to hand out.
     *
     * Null is a statement about this library, not about the page: it means no [CookieStore] is
     * wired up for the platform yet, not that the WebView has no cookies. It is null on the desktop
     * today, because CEF keeps the browser's cookies in `CefCookieManager` while intercepted
     * requests carry cookies from a `java.net.CookieManager` this library owns — two jars, and a
     * store that answered from one of them would be right about half of a login flow and silently
     * wrong about the other half. See `docs/PARALLEL-LANES.md`. Reconciling them is the work that
     * fills this in; until it is done, no answer is better than half an answer.
     *
     * Test doubles inherit the null default, so a fake only implements a jar if its tests need one.
     */
    val cookies: CookieStore? get() = null

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
     * Captures what the page **currently looks like** and returns it encoded, PNG by default.
     *
     * This is the second channel to a caller that has never seen the page, and it answers what the
     * first cannot. [dev.ggoggam.vitre.core.workflow.PageSnapshot] is a text outline of the DOM, so
     * a canvas, a chart, a map tile, an image-only advert and — most of all — *layout* are simply
     * absent from it. A run against a page that rendered wrongly and a run against a page that
     * rendered correctly produce the same outline.
     *
     * ### The visible viewport, on every platform
     *
     * What comes back is the WebView's current viewport at its current scroll position — the pixels
     * a user looking at that WebView would see, and nothing below the fold. Scroll first if you want
     * something else; the page's own `scrollTo` through [evaluateJs] is the way, and it is what a
     * caller would have to do to *click* the thing anyway.
     *
     * Full-page capture is deliberately **not offered**, and the reason is the repo's usual one
     * rather than effort. Only one of the three platforms can actually do it:
     *
     *  - **Desktop** could. Chrome DevTools' `Page.captureScreenshot` takes `captureBeyondViewport`
     *    and genuinely renders the whole scroll height.
     *  - **Android** cannot, honestly. The `measure`/`layout`-to-content-height trick reflows the
     *    live view a user is looking at, and modern WebView only rasterises tiles near the viewport,
     *    so the part that was never on screen comes back blank. `capturePicture` is deprecated and
     *    has been viewport-only for a decade.
     *  - **iOS** cannot either. `WKSnapshotConfiguration.rect` is clamped to the view's bounds;
     *    the workaround is to resize the `WKWebView` to the content height and put it back, which
     *    fires media queries, re-triggers lazy loading and moves sticky headers — the picture is of
     *    a page in a state that never existed.
     *
     * A parameter that means "the whole page" on one platform and "the top of it" on the other two
     * is the exact shape of the bugs in `docs/CONCURRENCY.md`'s table, so it does not exist. If a
     * full-page capture is ever wanted, it should arrive as a separate, explicitly desktop-only
     * call rather than as a flag on this one.
     *
     * The one place "the viewport" is not the same claim on all three: on Android and iOS the
     * WebView has a size because it is in a view hierarchy, and on the desktop the browser renders
     * offscreen and its viewport is whatever the host last reported through `CefSurface.resize`. A
     * headless lane nobody has sized is 1×1, and its screenshot is one pixel. Size the surface
     * before capturing there.
     *
     * ### Cost
     *
     * Bounded by [ScreenshotOptions.maxWidth] / [ScreenshotOptions.maxHeight], which default to a
     * size a vision model will not downscale further. The bound is applied *during* capture on all
     * three platforms — the bitmap is never allocated at full size and then resampled — because on
     * a phone that allocation is the expensive half.
     *
     * ### Ordering
     *
     * Ordered against [navigate] and [evaluateJs] like every other operation: a picture taken
     * halfway through someone else's navigation is a picture of neither document. The encode
     * afterwards is not an operation on the WebView and deliberately runs outside both the lock and
     * the WebView thread, so a PNG compress does not become UI jank.
     *
     * ### Why a method here rather than a capability object
     *
     * The other cross-platform capability on this interface, the `cookies` jar, is shaped
     * differently — a nullable property handing back a `CookieStore` of its own. That is the same
     * convention rather than a competing one, and what decides between the two shapes is what the
     * capability *is*. A cookie jar is a separate resource: it is not ordered against the WebView,
     * it is shared between lanes, and it outlives [close]. A screenshot is none of those. It is an
     * operation on this document, it takes the same lock [navigate] and [evaluateJs] take, and it
     * dies with the controller — so it belongs beside them rather than behind a handle with a
     * lifetime of its own.
     *
     * ### Default implementation
     *
     * Throws [ScreenshotUnsupportedException]. It exists for the same reason `cookies` defaults to
     * null: adding a member must not break a `WebViewController` written outside this module — the
     * audience [dev.ggoggam.vitre.core.concurrent.WebViewOrdering] is public for. It throws rather
     * than returning a blank image because a silent no-op is how a caller ends up reasoning about a
     * page from a picture of nothing. All three controllers this module ships override it.
     *
     * @throws ScreenshotFailedException if the platform declined, returned nothing, or the WebView
     *   has no size because it has not been laid out yet.
     * @throws ScreenshotUnsupportedException if this controller has no pixel path at all.
     */
    suspend fun screenshot(options: ScreenshotOptions = ScreenshotOptions()): PageScreenshot =
        throw ScreenshotUnsupportedException("${this::class.simpleName} does not implement screenshot()")

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

/**
 * A screenshot was attempted against a WebView that could have answered, and did not.
 *
 * The WebView had no size because nothing has laid it out, the platform's snapshot call reported an
 * error, or the encoder refused the bitmap. Distinct from [ScreenshotUnsupportedException], which
 * means the capability was never there to begin with — this one is a page or a view in a state
 * where the picture cannot be taken *right now*, and retrying after a layout pass may well work.
 */
class ScreenshotFailedException(
    message: String,
) : RuntimeException(message)

/**
 * This controller cannot take screenshots at all.
 *
 * Only reachable through [WebViewController.screenshot]'s default implementation, i.e. from a
 * controller written outside this module that has not overridden it. Thrown rather than answered
 * with a blank image on purpose: a caller reasoning about a page from a picture of nothing is a
 * worse failure than one that stops.
 */
class ScreenshotUnsupportedException(
    message: String,
) : RuntimeException(message)
