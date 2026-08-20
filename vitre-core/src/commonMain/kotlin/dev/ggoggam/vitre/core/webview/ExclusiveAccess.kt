package dev.ggoggam.vitre.core.webview

import dev.ggoggam.vitre.core.concurrent.WebViewLease
import kotlinx.coroutines.withContext

/**
 * A held claim on one WebView, handed to the block inside [WebViewController.exclusively].
 *
 * Ordering alone stops two callers corrupting each other's *single* operations. It does not stop a
 * second caller's `Click` landing between this caller's `WaitFor` and the `Extract` that depended on
 * it — each operation was properly serialised, and the sequence still came out wrong. Anything
 * multi-step against a page other callers can touch needs the claim held across the whole sequence,
 * which is what this is.
 *
 * Most callers never see it: work done inside the `exclusively` block is already covered, because
 * the claim rides the coroutine context. [use] exists for the case the context cannot reach — an
 * MCP client holding a lease across several tool calls, each arriving on a coroutine of its own,
 * where the claim has to be re-attached by hand. Concurrent [use] calls are *not* serialised against
 * each other; they all bypass the same lock, so a holder running several at once must order them
 * itself.
 */
class ExclusiveAccess internal constructor(
    private val lease: WebViewLease,
) {
    /** Runs [block] under this claim, for callers that arrived on a coroutine without it. */
    suspend fun <T> use(block: suspend () -> T): T = withContext(lease) { block() }
}
