package dev.ggoggam.vitre.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Darwin's main dispatcher targets the main queue, and its `immediate` form skips the dispatch
 * when `NSThread.isMainThread` already holds — the same saving the Android actual documents.
 */
internal actual val WebViewDispatcher: CoroutineDispatcher get() = Dispatchers.Main.immediate
