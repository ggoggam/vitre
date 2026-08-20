package dev.ggoggam.vitre.core.bridge

/**
 * How a page finds out that `window.vitre` is there.
 *
 * Until now there was no documented answer, so a page that wanted to be sure had to poll. It does
 * not have to, and the contract is deliberately small:
 *
 * **Existence is the flag.** `if (window.vitre)` is the authoritative, synchronous readiness
 * check. On Android and iOS the object is installed before any page script runs, for the same
 * reason on each: on iOS a `WKUserScript` at `WKUserScriptInjectionTimeAtDocumentStart`, on Android
 * `addWebMessageListener`, whose injected object is likewise in place before the document's own
 * scripts execute. So by the time any line of page JavaScript can ask the question, the answer is
 * already final.
 *
 * **The desktop cannot promise that, and a page written against it must not assume it.** CEF
 * exposes no document-start hook an application can reach, so `CefWebViewController` injects
 * `CefBridgeChannel.installScript()` from `onLoadStart` and that genuinely races the document's
 * first inline script — see `CefBridgeChannel` for the measurement and why the hook is out of
 * reach. The check is still authoritative whenever it answers yes; what moves is *when* it begins
 * to, which is what makes the listen half below load-bearing there rather than belt-and-braces.
 *
 * There is deliberately no `.ready` boolean. A second flag would only be a second thing that can
 * disagree with the first, and the first cannot be wrong.
 *
 * **[EVENT_NAME] is belt-and-braces on Android and iOS, and load-bearing on the desktop.** A
 * `vitre:ready` DOM event is dispatched on `window` at install, for code that would rather be
 * told than ask. The name is *not* `vitre` — that one is already taken by the `MessageEvent`
 * [DefaultWebViewBridge] uses for native→page delivery, and a listener for one must never be woken
 * by the other.
 *
 * **The documented usage is check-then-listen:**
 * ```js
 * function whenBridgeReady(fn) {
 *   if (window.vitre) { fn(); }
 *   else { window.addEventListener('vitre:ready', fn, { once: true }); }
 * }
 * ```
 *
 * The synchronous branch is not an optimisation — on Android and iOS it is the branch that fires. A
 * page script registering a bare listener and nothing else would hang *by construction* there: the
 * announcement precedes every page script, so the listener is always registered after the event it
 * is waiting for has already been dispatched. On those two the event only ever reaches a listener
 * registered by something that ran even earlier — another document-start script, say — and where an
 * Android WebView is too old to take [announceScript] at all, no event is dispatched for anyone to
 * catch. On the desktop that same bare listener is worse than reliably broken, because it catches
 * the event on some runs and misses it on others. Handling the already-ready case first is what
 * makes a late listener safe on all three.
 */
object BridgeReady {
    /**
     * The DOM event dispatched on `window` once `window.vitre` exists.
     *
     * Distinct from [DefaultWebViewBridge.EVENT_NAME] on purpose; see the class KDoc.
     */
    const val EVENT_NAME: String = "vitre:ready"

    /**
     * The script that announces the bridge, for the one platform that installs the announcement
     * separately from the bridge object itself (Android's `addDocumentStartJavaScript`).
     *
     * Guarded on the object existing rather than dispatching unconditionally: the announcement is a
     * statement about the bridge, and a page that got the event with no `window.vitre` behind
     * it would be told a lie it cannot check. Neither iOS nor the desktop has any need for this
     * string — both dispatch the event inline from their install script, where the object has just
     * been assigned.
     */
    val announceScript: String = "if (window.vitre) { window.dispatchEvent(new Event('$EVENT_NAME')); }"
}
