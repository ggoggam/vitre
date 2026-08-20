package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The serializer confines to `Dispatchers.Main`, which unit tests do not have, so every instance
 * here is built on the test scheduler instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewSerializerTest {
    @Test
    fun resumes_once_the_page_it_started_has_finished() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))

            serializer.navigate(timeoutMs = 1_000) {
                serializer.started()
                serializer.finished()
            }
        }

    @Test
    fun ignores_the_tail_of_a_load_that_was_already_in_flight() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))

            // The failure below belongs to the page that was loading when navigate was called;
            // accepting it would throw, so returning normally is the assertion.
            serializer.navigate(timeoutMs = 1_000) {
                serializer.failed("previous page died")
                serializer.started()
                serializer.finished()
            }
        }

    @Test
    fun a_failure_after_the_load_starts_throws() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))

            val failure =
                assertFailsWith<PageLoadException> {
                    serializer.navigate(timeoutMs = 1_000) {
                        serializer.started()
                        serializer.failed("net::ERR_CONNECTION_REFUSED")
                    }
                }

            assertEquals("net::ERR_CONNECTION_REFUSED", failure.message)
        }

    @Test
    fun a_page_that_never_settles_fails_rather_than_cancelling_the_caller() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))

            // PageLoadException, not TimeoutCancellationException: one frame up, a cancellation
            // thrown by us is indistinguishable from the caller having been cancelled.
            val failure =
                assertFailsWith<PageLoadException> {
                    serializer.navigate(timeoutMs = 1_000) { serializer.started() }
                }

            assertTrue("1000ms" in failure.message.orEmpty(), "unhelpful message: ${failure.message}")
        }

    @Test
    fun holds_a_second_navigation_until_the_first_has_finished() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            val startedLoads = mutableListOf<String>()

            val first = launch { serializer.navigate(10_000) { startedLoads += "first" } }
            runCurrent()
            val second = launch { serializer.navigate(10_000) { startedLoads += "second" } }
            runCurrent()

            assertEquals(listOf("first"), startedLoads, "overlapping loads were allowed")

            serializer.started()
            serializer.finished()
            first.join()
            runCurrent()

            assertEquals(listOf("first", "second"), startedLoads)
            second.cancel()
        }

    @Test
    fun a_script_may_not_run_while_a_navigation_is_in_flight() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            val order = mutableListOf<String>()

            val navigating = launch { serializer.navigate(10_000) { order += "navigate" } }
            runCurrent()
            val evaluating =
                launch {
                    serializer.evaluate { cont ->
                        order += "evaluate"
                        cont.resume("\"done\"")
                    }
                }
            runCurrent()

            // Without the shared lock this script would run against whichever document happened to
            // be committed mid-navigation — a result nobody asked for.
            assertEquals(listOf("navigate"), order)

            serializer.started()
            serializer.finished()
            navigating.join()
            evaluating.join()

            assertEquals(listOf("navigate", "evaluate"), order)
        }

    @Test
    fun a_script_whose_callback_never_fires_gives_up_instead_of_stranding_the_caller() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))

            // Both platforms drop a pending script callback when the document goes away, so this
            // is what a page redirecting out from under an evaluate actually looks like.
            val failure =
                assertFailsWith<ScriptTimeoutException> {
                    serializer.evaluate(timeoutMs = 500) { /* callback never arrives */ }
                }

            assertTrue("500ms" in failure.message.orEmpty(), "unhelpful message: ${failure.message}")
        }

    @Test
    fun a_script_the_page_navigated_away_from_is_submitted_once_more() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            var attempts = 0

            val result =
                serializer.evaluate(timeoutMs = 1_000) { cont ->
                    attempts++
                    // The first submission is lost the way a real one is: the page navigates and
                    // the platform drops the callback without ever invoking it.
                    if (attempts == 1) {
                        // The page navigates: the callback for this submission is dropped and never
                        // fires, and the replacement document settles a moment later.
                        serializer.started()
                        serializer.finished()
                    } else {
                        cont.resume("\"second\"")
                    }
                }

            assertEquals(2, attempts)
            assertEquals("\"second\"", result)
        }

    @Test
    fun a_script_lost_twice_is_reported_rather_than_retried_forever() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            var attempts = 0

            val failure =
                assertFailsWith<ScriptTimeoutException> {
                    serializer.evaluate(timeoutMs = 1_000) {
                        attempts++
                        serializer.started()
                        serializer.finished()
                    }
                }

            assertEquals(2, attempts, "a second loss is a fault, not something to keep retrying")
            assertTrue("navigated away" in failure.message.orEmpty(), "unhelpful message: ${failure.message}")
        }

    @Test
    fun a_load_already_in_flight_does_not_look_like_a_lost_script() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            var attempts = 0

            // Emitted before the script goes in. The flow has no replay, so the watcher subscribed
            // for this evaluation must not see it — otherwise every script that follows a
            // navigation runs twice.
            serializer.started()
            serializer.finished()

            val result =
                serializer.evaluate(timeoutMs = 1_000) { cont ->
                    attempts++
                    cont.resume("\"once\"")
                }

            assertEquals(1, attempts)
            assertEquals("\"once\"", result)
        }

    @Test
    fun scripts_run_in_submission_order() =
        runTest {
            val serializer = WebViewSerializer(UnconfinedTestDispatcher(testScheduler))
            val order = mutableListOf<Int>()

            val jobs =
                (1..5).map { n ->
                    launch {
                        serializer.evaluate { cont ->
                            order += n
                            cont.resume("null")
                        }
                    }
                }
            jobs.forEach { it.join() }

            assertEquals(listOf(1, 2, 3, 4, 5), order)
        }
}
