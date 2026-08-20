package dev.ggoggam.vitre.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * `immediate` so a caller already on the main thread — the Compose host, most of the time — runs
 * through without a trip round the Looper. A `WaitFor` step polls every 100ms, and each poll would
 * otherwise pay for two hops it does not need.
 */
internal actual val WebViewDispatcher: CoroutineDispatcher get() = Dispatchers.Main.immediate
