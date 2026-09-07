package dev.ggoggam.vitre.core.webview

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView

/**
 * The settings a WebView needs before a workflow can treat it as a browser.
 *
 * Applied by both hosts — the composable in `vitre-compose` and every lane in `AndroidWebViewPool`
 * — because a page that behaves in one and not the other is the most expensive kind of difference
 * to find. A host that builds its own `WebView` around an [AndroidWebViewController] should call
 * this too.
 *
 * Deliberately small. `javaScriptEnabled` and `domStorageEnabled` are what make a modern page work
 * at all; the user agent is the one default that changes what a *server* decides to send. Anything
 * beyond that is left to the host, because a WebView showing a page to a person and a headless lane
 * scraping one want different answers — mixed content being the example, which the pool opts into
 * and the composable does not.
 *
 * Note what is **not** here. `useWideViewPort` and `loadWithOverviewMode` were tried against the
 * zero-height viewport bug described in [applyVitreLayoutParams] and measured to make no difference
 * to it: with both `false`, `100vh` resolves correctly once the layout params are right. They are
 * left at their platform defaults rather than set on a hunch.
 *
 * @see withoutWebViewToken for why the user agent is rewritten.
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyVitreWebSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = withoutWebViewToken(settings.userAgentString)
}

/**
 * Gives the WebView a **definite** layout height, without which CSS viewport-height units are zero.
 *
 * Android WebView reads its `LayoutParams` — not the measure spec its parent hands it — when it
 * tells Blink how large the viewport is. Left at `WRAP_CONTENT`, the height it reports is zero, and
 * every CSS viewport-height unit in every page resolves to zero with it: `100vh`, `100dvh`,
 * `100svh` and `100lvh` all compute to `0`, while `100vw`, `100%` and
 * `document.documentElement.clientHeight` stay correct. The asymmetry is what makes it so hard to
 * spot — the page is not obviously broken, it is broken in one axis.
 *
 * A page laid out in normal flow never notices. A page whose shell is sized in `vh` — anything
 * built as an application rather than a document — collapses to nothing and paints blank **with its
 * DOM fully intact**. That last part is why this is worth a function and a name: every locator
 * still matches, every extraction still returns the right answer, and a workflow reports success
 * over a white screen. Nothing but a person looking at the device catches it.
 *
 * Only the host that puts the WebView in a hierarchy can get this right, so it is applied where the
 * views are created and a caller that arranges its own layout is free to replace it.
 */
fun WebView.applyVitreLayoutParams() {
    layoutParams =
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
}
