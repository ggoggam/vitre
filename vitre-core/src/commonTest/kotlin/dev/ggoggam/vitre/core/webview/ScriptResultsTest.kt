package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each test builds a fresh [ScriptResults], so its first evaluate is always cid 1 — the tests
 * lean on that rather than parsing the cid back out of the wrapped script.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptResultsTest {
    private val script = AsyncScript(nonce = "n")
    private val results = ScriptResults(script)

    private suspend fun evaluate(
        expression: String,
        timeoutMs: Long,
        evaluateRaw: suspend (String) -> String,
    ): String =
        results.evaluate(expression, timeoutMs) { wrapped, onSubmitted ->
            onSubmitted()
            evaluateRaw(wrapped)
        }

    @Test
    fun `a plain value returns through the evaluate untouched`() =
        runTest {
            val submitted = mutableListOf<String>()
            val answer =
                evaluate("1 + 1", timeoutMs = 1_000) { wrapped ->
                    submitted += wrapped
                    "2"
                }
            assertEquals("2", answer)
            assertTrue(submitted.single().contains("var v = (1 + 1);"))
        }

    @Test
    fun `a promise's settled value arrives through the bridge`() =
        runTest {
            val answer = async { evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
            runCurrent()
            assertTrue(results.deliver(report(cid = 1, value = "{\\\"a\\\":1}"), fromMainFrame = true))
            assertEquals("""{"a":1}""", answer.await())
        }

    @Test
    fun `a rejected promise fails the evaluate with the page's message`() =
        runTest {
            // Captured inside the coroutine: a failure escaping an async cancels the whole test
            // scope before an assertFailsWith around await() could see it.
            val answer = async { runCatching { evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } } }
            runCurrent()
            assertTrue(
                results.deliver(
                    """{"id":"x","type":"script:result","payload":{"cid":1,"nonce":"n","ok":false,"error":"TypeError: nope"}}""",
                    fromMainFrame = true,
                ),
            )
            val failure = answer.await().exceptionOrNull()
            assertTrue(failure is ScriptFailedException)
            assertEquals("TypeError: nope", failure.message)
        }

    @Test
    fun `a report from a subframe is swallowed and credits nothing`() =
        runTest {
            val answer = async { evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
            runCurrent()
            // Claimed — it must not reach the inbox — but the wait is still open, and the real
            // main-frame answer still wins.
            assertTrue(results.deliver(report(cid = 1, value = "\\\"forged\\\""), fromMainFrame = false))
            runCurrent()
            assertFalse(answer.isCompleted)
            assertTrue(results.deliver(report(cid = 1, value = "\\\"real\\\""), fromMainFrame = true))
            assertEquals("\"real\"", answer.await())
        }

    @Test
    fun `a report naming a guessed nonce is swallowed and credits nothing`() =
        runTest {
            val answer = async { evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
            runCurrent()
            assertTrue(results.deliver(report(cid = 1, nonce = "guess", value = "\\\"forged\\\""), fromMainFrame = true))
            runCurrent()
            assertFalse(answer.isCompleted)
            assertTrue(results.deliver(report(cid = 1, value = "\\\"real\\\""), fromMainFrame = true))
            assertEquals("\"real\"", answer.await())
        }

    @Test
    fun `a page message is left for the inbox`() =
        runTest {
            assertFalse(results.deliver("""{"id":"x","type":"lane:ready","payload":{}}""", fromMainFrame = true))
        }

    @Test
    fun `a report nobody is waiting on any more is still kept off the inbox`() =
        runTest {
            assertTrue(results.deliver(report(cid = 99, value = "\\\"late\\\""), fromMainFrame = true))
        }

    @Test
    fun `navigation fails a submitted promise promptly rather than by timeout`() =
        runTest {
            val answer = async { runCatching { evaluate("fetch('/x')", timeoutMs = 60_000) { script.pendingResult(1) } } }
            runCurrent()
            results.clear()
            // No virtual time has passed, so reaching the failure at all proves it was clear()'s
            // doing and not the 60s timeout.
            assertTrue(answer.await().exceptionOrNull() is ScriptOutcomeUnknownException)
        }

    @Test
    fun `navigation before a pending sentinel invalidates the eventual promise wait`() =
        runTest {
            val returnSentinel = CompletableDeferred<Unit>()
            val answer =
                async {
                    runCatching {
                        evaluate("fetch('/x')", timeoutMs = 5_000) {
                            returnSentinel.await()
                            script.pendingResult(1)
                        }
                    }
                }
            runCurrent()
            results.clear()
            returnSentinel.complete(Unit)
            runCurrent()
            assertTrue(answer.await().exceptionOrNull() is ScriptOutcomeUnknownException)
            // A delayed report is consumed but cannot turn the invalidated wait into success.
            assertTrue(results.deliver(report(cid = 1, value = "\\\"fresh\\\""), fromMainFrame = true))
        }

    @Test
    fun `a caller deadline while awaiting a promise remains cancellation`() =
        runTest {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) }
                }
            }
            // Cancellation removes the pending entry; a late settlement is still kept off inbox.
            assertTrue(results.deliver(report(cid = 1, value = "null"), fromMainFrame = true))
        }

    @Test
    fun `an already settled promise remains known across navigation`() =
        runTest {
            val answer =
                evaluate("Promise.resolve(42)", timeoutMs = 5_000) {
                    results.deliver(report(cid = 1, value = "42"), fromMainFrame = true)
                    results.clear()
                    script.pendingResult(1)
                }
            assertEquals("42", answer)
        }

    @Test
    fun `navigation before submission does not invalidate a queued script`() =
        runTest {
            val submitNow = CompletableDeferred<Unit>()
            val answer =
                async {
                    results.evaluate("Promise.resolve(42)", timeoutMs = 5_000) { _, onSubmitted ->
                        submitNow.await()
                        onSubmitted()
                        results.deliver(report(cid = 1, value = "42"), fromMainFrame = true)
                        script.pendingResult(1)
                    }
                }
            runCurrent()
            results.clear()
            submitNow.complete(Unit)
            assertEquals("42", answer.await())
        }

    @Test
    fun `a promise that never settles times out as a script timeout`() =
        runTest {
            // Direct call: runTest advances virtual time while the evaluate is suspended, so the
            // 1s timeout fires without a wall-clock wait.
            assertFailsWith<ScriptOutcomeUnknownException> {
                evaluate("fetch('/x')", timeoutMs = 1_000) { script.pendingResult(1) }
            }
        }

    private fun report(
        cid: Long,
        nonce: String = "n",
        value: String,
    ): String =
        """{"id":"script:result#$cid","type":"script:result","payload":{"cid":$cid,"nonce":"$nonce","ok":true,"value":"$value","error":null}}"""
}
