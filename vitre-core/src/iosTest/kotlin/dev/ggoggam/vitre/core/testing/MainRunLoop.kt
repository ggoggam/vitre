package dev.ggoggam.vitre.core.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSince1970
import kotlin.test.fail

/**
 * Runs [body] as a coroutine on the main queue, pumping the main run loop until it finishes.
 *
 * `runBlocking` cannot be used for anything that touches WebKit, and the way it fails is a hang
 * rather than an error. Every `WKWebView` callback this library waits on — `didFinishNavigation`,
 * `evaluateJavaScript`'s completion handler, `WKHTTPCookieStore.getAllCookies` — is delivered on
 * the main *queue*, and a `runBlocking` on the main thread parks that thread in its own event loop
 * where no main-queue block will ever be serviced. The load starts, the callback is enqueued behind
 * a thread that is waiting for it, and the test sits there until the harness gives up.
 *
 * So the test body is dispatched rather than blocked on, and this function spins
 * `NSRunLoop.mainRunLoop` — which *does* drain the main queue — until the body reports back.
 *
 * [timeoutSeconds] is generous on purpose: the first `WKWebView` in a process starts a content
 * process and a networking process, and on a cold simulator that is seconds rather than
 * milliseconds. A tight bound here would be a flake rather than a signal.
 */
fun runMainLoopTest(
    timeoutSeconds: Double = 60.0,
    body: suspend CoroutineScope.() -> Unit,
) {
    check(NSThread.isMainThread) { "runMainLoopTest must be called on the main thread" }

    // Null means "still running" — deliberately not a Boolean flag plus a separate throwable, so
    // that a body which completes *and* throws cannot be read as still running.
    var outcome: Result<Unit>? = null
    CoroutineScope(Dispatchers.Main).launch {
        outcome = runCatching { body() }
    }

    val deadline = NSDate().timeIntervalSince1970 + timeoutSeconds
    while (outcome == null) {
        // Bounded rather than `runUntilDate`, so the loop returns to check the deadline even on a
        // run loop with no sources left to fire.
        NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate().dateByAddingTimeInterval(TICK_SECONDS))
        if (NSDate().timeIntervalSince1970 > deadline) {
            fail("Timed out after ${timeoutSeconds}s waiting for the test body")
        }
    }
    // `checkNotNull` rather than a smart cast: `outcome` is captured by a closure that mutates it,
    // so the compiler will not narrow it here however obvious the loop above makes it.
    checkNotNull(outcome).getOrThrow()
}

private const val TICK_SECONDS = 0.02
