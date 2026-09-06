package dev.ggoggam.vitre.core.concurrent

import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewOrderingTest {
    @Test
    fun sibling_operations_inheriting_a_claim_are_serialized() =
        runTest {
            val order = WebViewOrdering()
            var active = 0
            var maximum = 0
            order.exclusively {
                coroutineScope {
                    repeat(3) {
                        launch {
                            order.ordered {
                                active++
                                maximum = maxOf(maximum, active)
                                try {
                                    delay(100)
                                } finally {
                                    active--
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(1, maximum)
        }

    @Test
    fun externally_attached_claims_share_operation_ordering() =
        runTest {
            val order = WebViewOrdering()
            val granted = CompletableDeferred<ExclusiveAccess>()
            val release = CompletableDeferred<Unit>()
            val finishOperation = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val holder =
                launch {
                    order.exclusively {
                        granted.complete(it)
                        release.await()
                    }
                }
            val access = granted.await()
            val first =
                launch {
                    access.use {
                        order.ordered {
                            events.add("first started")
                            finishOperation.await()
                            events.add("first finished")
                        }
                    }
                }
            runCurrent()
            val second = launch { access.use { order.ordered { events.add("second") } } }
            runCurrent()
            assertEquals(listOf("first started"), events)
            finishOperation.complete(Unit)
            first.join()
            second.join()
            release.complete(Unit)
            holder.join()
            assertEquals(listOf("first started", "first finished", "second"), events)
        }

    @Test
    fun nested_exclusive_claims_can_reach_both_controllers() =
        runTest {
            val first = WebViewOrdering()
            val second = WebViewOrdering()
            val events = mutableListOf<String>()
            first.exclusively {
                first.exclusively {
                    second.exclusively {
                        first.ordered { events.add("first") }
                        second.ordered { events.add("second") }
                        first.exclusively { first.ordered { events.add("first again") } }
                    }
                }
            }
            assertEquals(listOf("first", "second", "first again"), events)
        }
}
