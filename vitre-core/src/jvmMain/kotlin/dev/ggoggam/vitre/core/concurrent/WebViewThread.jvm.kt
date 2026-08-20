package dev.ggoggam.vitre.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.swing.Swing

/**
 * The AWT event dispatch thread, which is this platform's answer to the rule in [WebViewDispatcher].
 *
 * CEF's own browser thread is not the EDT, and most `CefBrowser` members are safe to call from
 * anywhere because they post across to it. The parts that are *not* are the ones that matter here:
 * the browser's `getUIComponent()` is an AWT component, so creating it, sizing it and adding it to
 * a hierarchy are EDT-only in the ordinary Swing sense, and JCEF's own window handling assumes the
 * same. Confining every call keeps that true without each call site having to know which kind it is.
 *
 * `immediate` for the same reason Android uses it: the Compose desktop host already runs on the
 * EDT, and a `WaitFor` step polling every 100ms would otherwise pay for two hops per poll.
 */
internal actual val WebViewDispatcher: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Swing.immediate
