package dev.ggoggam.vitre.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The one thread a WebView may be touched from.
 *
 * Both platforms impose this and neither enforces it usefully: `WKWebView` is UIKit, so every
 * member is main-thread-only and calling one from elsewhere is undefined behaviour rather than an
 * exception; `android.webkit.WebView` must be used on the thread that constructed it, which for a
 * hosted view is always the UI thread. So there is no negotiating a thread model here — the
 * WebView picks it, and everything else has to come to it.
 *
 * Callers therefore never dispatch by hand. Every operation that reaches the platform WebView is
 * wrapped by [WebViewSerializer]. Business logic and workflow evaluation stay on whatever
 * dispatcher their caller chose (`Dispatchers.Default` for the engine) and cross over one
 * suspending call at a time.
 *
 * The platform cookie stores are the one thing that confines to this dispatcher without going
 * through the serializer, and deliberately: they operate on the jar rather than on the document, so
 * they need the thread but not the ordering. See [dev.ggoggam.vitre.core.webview.CookieStore].
 */
internal expect val WebViewDispatcher: CoroutineDispatcher
