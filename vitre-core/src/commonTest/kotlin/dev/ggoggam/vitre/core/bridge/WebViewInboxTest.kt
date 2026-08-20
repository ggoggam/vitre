package dev.ggoggam.vitre.core.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewInboxTest {
    @Test
    fun matches_a_message_that_arrived_before_anyone_was_waiting() =
        runTest {
            val inbox = WebViewInbox()

            // The race this whole class exists for: the page posts the instant the previous step's
            // script returns, which is before the AwaitMessage step has begun to listen.
            inbox.deliver("ready")

            assertEquals("ready", inbox.awaitMatching { it == "ready" })
        }

    @Test
    fun matches_a_message_that_arrives_after_the_wait_begins() =
        runTest {
            val inbox = WebViewInbox()
            var received: String? = null

            val waiting = launch { received = inbox.awaitMatching { it == "ready" } }
            runCurrent()
            assertNull(received, "matched before the message was sent")

            inbox.deliver("ready")
            waiting.join()

            assertEquals("ready", received)
        }

    @Test
    fun leaves_unmatched_messages_for_a_later_waiter() =
        runTest {
            val inbox = WebViewInbox()

            inbox.deliver("first")
            inbox.deliver("second")

            assertEquals("second", inbox.awaitMatching { it == "second" })
            assertEquals("first", inbox.awaitMatching { it == "first" })
        }

    @Test
    fun consumes_each_message_once_so_two_waiters_get_two_messages() =
        runTest {
            val inbox = WebViewInbox()
            val received = mutableListOf<String>()

            val first = launch { received += inbox.awaitMatching { it.startsWith("tick") } }
            val second = launch { received += inbox.awaitMatching { it.startsWith("tick") } }
            runCurrent()

            inbox.deliver("tick-1")
            inbox.deliver("tick-2")
            first.join()
            second.join()

            // Replay would have handed both waiters "tick-1".
            assertEquals(listOf("tick-1", "tick-2"), received.sorted())
        }

    @Test
    fun a_new_document_discards_the_previous_page_s_unread_messages() =
        runTest {
            val inbox = WebViewInbox()
            inbox.deliver("ready")

            inbox.clear()

            // Otherwise the old page's "ready" satisfies the wait that belongs to the new one.
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1_000) { inbox.awaitMatching { it == "ready" } }
            }
        }

    @Test
    fun observers_see_traffic_without_consuming_it() =
        runTest {
            val inbox = WebViewInbox()
            val observed = mutableListOf<String>()

            val observer = launch { inbox.messages.collect { observed += it } }
            runCurrent()

            inbox.deliver("ready")
            runCurrent()

            assertEquals(listOf("ready"), observed)
            // A debug pane watching the bridge must not starve the workflow driving it.
            assertEquals("ready", inbox.awaitMatching { it == "ready" })
            observer.cancel()
        }

    @Test
    fun a_subframe_message_never_satisfies_an_await() =
        runTest {
            val inbox = WebViewInbox()

            // An embedded ad on the page being driven can post whatever the workflow is waiting
            // for. Answering only counts from the document we are actually driving.
            inbox.deliver("ready", fromMainFrame = false)

            assertFailsWith<TimeoutCancellationException>("an iframe answered a wait armed for the main document") {
                withTimeout(1_000) { inbox.awaitMatching { it == "ready" } }
            }
        }

    @Test
    fun a_subframe_message_still_reaches_observers() =
        runTest {
            val inbox = WebViewInbox()
            val observed = mutableListOf<String>()
            val tagged = mutableListOf<InboundBridgeMessage>()

            // Gating awaits must not blind hosts: a subframe error is information, so it is
            // dropped from the consumable queue only, not from the firehoses.
            val raw = launch { inbox.messages.collect { observed += it } }
            val typed = launch { inbox.inbound.collect { tagged += it } }
            runCurrent()

            inbox.deliver("iframe-error", fromMainFrame = false, sourceOrigin = "https://ads.example")
            runCurrent()

            assertEquals(listOf("iframe-error"), observed, "subframe traffic vanished from messages")
            assertEquals(
                listOf(InboundBridgeMessage("iframe-error", fromMainFrame = false, sourceOrigin = "https://ads.example")),
                tagged,
                "subframe traffic vanished from inbound, or lost its tag",
            )
            raw.cancel()
            typed.cancel()
        }

    @Test
    fun the_tagged_stream_carries_frame_and_origin_for_main_frame_traffic() =
        runTest {
            val inbox = WebViewInbox()
            val tagged = mutableListOf<InboundBridgeMessage>()

            // The tag is what lets a host tell the driven document apart from what it embeds, so
            // main-frame messages have to carry it too — not just the ones being excluded.
            val typed = launch { inbox.inbound.collect { tagged += it } }
            runCurrent()

            inbox.deliver("ready", fromMainFrame = true, sourceOrigin = "https://shop.example:8443")
            runCurrent()

            assertEquals(
                listOf(InboundBridgeMessage("ready", fromMainFrame = true, sourceOrigin = "https://shop.example:8443")),
                tagged,
            )
            typed.cancel()
        }

    @Test
    fun a_main_frame_message_is_still_consumed_exactly_once() =
        runTest {
            val inbox = WebViewInbox()

            // The frame gate is a new branch in front of every delivery, and the default argument
            // is the branch every existing caller takes: consumption must be untouched.
            inbox.deliver("ready")

            assertEquals("ready", inbox.awaitMatching { it == "ready" })
            assertFailsWith<TimeoutCancellationException>("the message was matched twice") {
                withTimeout(1_000) { inbox.awaitMatching { it == "ready" } }
            }
        }
}
