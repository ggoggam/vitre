package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each test builds a fresh [ScriptResults], so its first evaluate is always cid 1 — the tests
 * lean on that rather than parsing the cid back out of the wrapped script.
 */
class ScriptResultsTest {
    private val script = AsyncScript(nonce = "n")
    private val results = ScriptResults(script)

    @Test
    fun `a plain value returns through the evaluate untouched`() =
        runTest {
            val submitted = mutableListOf<String>()
            val answer =
                results.evaluate("1 + 1", timeoutMs = 1_000) { wrapped ->
                    submitted += wrapped
                    "2"
                }
            assertEquals("2", answer)
            assertTrue(submitted.single().contains("var v = (1 + 1);"))
        }

    @Test
    fun `a promise's settled value arrives through the bridge`() =
        runTest {
            val answer = async { results.evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
            runCurrent()
            assertTrue(results.deliver(report(cid = 1, value = "{\\\"a\\\":1}"), fromMainFrame = true))
            assertEquals("""{"a":1}""", answer.await())
        }

    @Test
    fun `a rejected promise fails the evaluate with the page's message`() =
        runTest {
            // Captured inside the coroutine: a failure escaping an async cancels the whole test
            // scope before an assertFailsWith around await() could see it.
            val answer = async { runCatching { results.evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } } }
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
            val answer = async { results.evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
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
            val answer = async { results.evaluate("fetch('/x')", timeoutMs = 5_000) { script.pendingResult(1) } }
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
    fun `navigation fails an armed wait promptly rather than by timeout`() =
        runTest {
            val answer = async { runCatching { results.evaluate("fetch('/x')", timeoutMs = 60_000) { script.pendingResult(1) } } }
            runCurrent()
            results.clear()
            // No virtual time has passed, so reaching the failure at all proves it was clear()'s
            // doing and not the 60s timeout.
            assertTrue(answer.await().exceptionOrNull() is ScriptTimeoutException)
        }

    @Test
    fun `an evaluate still in flight survives navigation for the resubmit to answer`() =
        runTest {
            // WebViewSerializer resubmits a script the page navigated out from under; the second
            // run settles under the same cid, so clear() must not have failed the wait.
            val resubmitting = CompletableDeferred<Unit>()
            val answer =
                async {
                    results.evaluate("fetch('/x')", timeoutMs = 5_000) {
                        resubmitting.await()
                        script.pendingResult(1)
                    }
                }
            runCurrent()
            results.clear()
            resubmitting.complete(Unit)
            runCurrent()
            assertTrue(results.deliver(report(cid = 1, value = "\\\"fresh\\\""), fromMainFrame = true))
            assertEquals("\"fresh\"", answer.await())
        }

    @Test
    fun `a promise that never settles times out as a script timeout`() =
        runTest {
            // Direct call: runTest advances virtual time while the evaluate is suspended, so the
            // 1s timeout fires without a wall-clock wait.
            assertFailsWith<ScriptTimeoutException> {
                results.evaluate("fetch('/x')", timeoutMs = 1_000) { script.pendingResult(1) }
            }
        }

    private fun report(
        cid: Long,
        nonce: String = "n",
        value: String,
    ): String =
        """{"id":"script:result#$cid","type":"script:result","payload":{"cid":$cid,"nonce":"$nonce","ok":true,"value":"$value","error":null}}"""
}
