package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.css
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageAccessPolicyTest {
    private class Page : WebViewController {
        val effects = mutableListOf<String>()
        private val order = WebViewOrdering()
        override val bridge = DefaultWebViewBridge(WebViewInbox()) { evaluateJs(it) }

        override suspend fun navigate(url: String) = order.ordered { effects += url }

        override suspend fun loadHtml(
            html: String,
            baseUrl: String?,
        ) = order.ordered { effects += html }

        override suspend fun evaluateJs(script: String): String =
            order.ordered {
                effects += script
                "\"result\""
            }

        override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

        override fun close() = Unit
    }

    @Test
    fun all_page_actions_and_lease_acquisition_are_authorized_before_effects() =
        runTest {
            val page = Page()
            val sessions = WebViewSessions().apply { register("main", page) }
            val seen = mutableListOf<PageActionKind>()
            val driver =
                PageDriver(
                    sessions,
                    this,
                    EmptyCoroutineContext,
                    accessPolicy =
                        PageAccessPolicy(
                            authorizer = { request ->
                                assertSame(page, request.controller)
                                assertEquals("main", request.sessionId)
                                seen += request.kind
                                PageActionDecision.Deny("User declined")
                            },
                        ),
                )
            val actions: List<suspend () -> Unit> =
                listOf(
                    { driver.snapshot() },
                    { driver.navigate("https://shop.test/") },
                    { driver.click(css("#buy")) },
                    { driver.input(css("#name"), "value") },
                    { driver.waitFor(css("#name")) },
                    { driver.extract(css("#name")) },
                    { driver.extractRows(css("li"), mapOf("name" to WorkflowStep.ExtractRows.Column(css("span")))) },
                    { driver.evaluate("document.title") },
                    { driver.postMessage("payload") },
                    { driver.awaitMessage("reply") },
                    { driver.acquireLease() },
                )
            for (action in actions) {
                assertTrue("User declined" in assertFailsWith<PageDriverException> { action() }.message)
            }
            assertEquals(PageActionKind.entries.toList(), seen)
            assertTrue(page.effects.isEmpty())
            assertTrue(driver.leases.active.isEmpty())
        }

    @Test
    fun approval_suspends_before_the_page_lock_and_cancellation_has_no_effect() =
        runTest {
            val page = Page()
            val sessions = WebViewSessions().apply { register("main", page) }
            val approval = CompletableDeferred<PageActionDecision>()
            val driver =
                PageDriver(
                    sessions,
                    this,
                    EmptyCoroutineContext,
                    accessPolicy = PageAccessPolicy(authorizer = { approval.await() }),
                )
            val call = launch { driver.navigate("https://shop.test/") }
            runCurrent()
            assertFalse(call.isCompleted)
            assertTrue(page.effects.isEmpty())
            page.evaluateJs("host still owns its page")
            call.cancelAndJoin()
            approval.complete(PageActionDecision.Allow)
            runCurrent()
            assertTrue(call.isCancelled)
            assertEquals(listOf("host still owns its page"), page.effects)
        }

    @Test
    fun an_approval_for_a_replaced_session_cannot_execute() =
        runTest {
            val original = Page()
            val replacement = Page()
            val sessions = WebViewSessions().apply { register("main", original) }
            val approval = CompletableDeferred<PageActionDecision>()
            val driver =
                PageDriver(
                    sessions,
                    this,
                    EmptyCoroutineContext,
                    accessPolicy = PageAccessPolicy(authorizer = { approval.await() }),
                )
            val call = async { assertFailsWith<PageDriverException> { driver.navigate("https://shop.test/") } }
            runCurrent()
            sessions.register("main", replacement)
            approval.complete(PageActionDecision.Allow)
            assertTrue("changed" in call.await().message)
            assertTrue(original.effects.isEmpty() && replacement.effects.isEmpty())
        }

    @Test
    fun session_identity_is_checked_again_after_waiting_for_the_page_lock() =
        runTest {
            val page = Page()
            val sessions = WebViewSessions().apply { register("main", page) }
            val unlock = CompletableDeferred<Unit>()
            val holder = launch { page.exclusively { unlock.await() } }
            runCurrent()
            val driver = PageDriver(sessions, this, EmptyCoroutineContext)
            val call = async { assertFailsWith<PageDriverException> { driver.navigate("https://shop.test/") } }
            runCurrent()
            sessions.unregister("main")
            unlock.complete(Unit)
            holder.join()
            assertTrue("changed" in call.await().message)
            assertTrue(page.effects.isEmpty())
        }

    @Test
    fun approval_uses_the_leases_actual_controller_and_does_not_occupy_its_use_gate() =
        runTest {
            val original = Page()
            val replacement = Page()
            val sessions = WebViewSessions().apply { register("main", original) }
            val approval = CompletableDeferred<PageActionDecision>()
            val requests = mutableListOf<PageActionRequest>()
            val driver =
                PageDriver(
                    sessions,
                    this,
                    EmptyCoroutineContext,
                    accessPolicy =
                        PageAccessPolicy(authorizer = {
                            requests += it
                            if (it.kind == PageActionKind.Navigate) approval.await() else PageActionDecision.Allow
                        }),
                )
            val grant = driver.acquireLease()
            try {
                sessions.register("main", replacement)
                val target = PageTarget(session = "main", lease = grant.id)
                val waiting = launch { driver.navigate("https://shop.test/", target) }
                runCurrent()
                assertFalse(waiting.isCompleted)
                driver.evaluate("document.title", target)
                assertTrue(replacement.effects.isEmpty())
                assertEquals(listOf("document.title"), original.effects)
                approval.complete(PageActionDecision.Allow)
                waiting.join()
                assertTrue("https://shop.test/" in original.effects)
                assertTrue(requests.all { it.controller === original })
                assertEquals(grant.id, requests.last().leaseId)
            } finally {
                driver.releaseLease(grant.id)
            }
        }

    @Test
    fun disabling_arbitrary_javascript_does_not_disable_internal_navigation_title_reads() =
        runTest {
            val page = Page()
            val sessions = WebViewSessions().apply { register("main", page) }
            val driver =
                PageDriver(
                    sessions,
                    this,
                    EmptyCoroutineContext,
                    accessPolicy =
                        PageAccessPolicy(
                            enabledActions = PageActionKind.entries.toSet() - PageActionKind.Evaluate,
                        ),
                )
            assertFailsWith<PageDriverException> { driver.evaluate("document.title") }
            assertEquals("result", driver.navigate("https://shop.test/"))
            assertEquals(listOf("https://shop.test/", "document.title"), page.effects)
            val capabilities = driver.capabilities()
            assertFalse("evaluate" in capabilities.operations)
            assertFalse(capabilities.screenshot || capabilities.cookies || capabilities.nativeRedirectFiltering)
        }

    @Test
    fun authorization_callback_failures_are_actionable() =
        runTest {
            val page = Page()
            val driver =
                PageDriver(
                    WebViewSessions().apply { register("main", page) },
                    this,
                    EmptyCoroutineContext,
                    accessPolicy = PageAccessPolicy(authorizer = { error("Approval UI unavailable") }),
                )
            val failure = assertFailsWith<PageDriverException> { driver.navigate("https://shop.test/") }
            assertTrue("Host authorization failed" in failure.message)
            assertTrue("Approval UI unavailable" in failure.message)
            assertTrue(page.effects.isEmpty())
        }
}
