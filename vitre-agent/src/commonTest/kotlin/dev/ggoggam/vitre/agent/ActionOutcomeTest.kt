package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.SessionLeases
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.WorkflowFailureKind
import dev.ggoggam.vitre.core.workflow.css
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActionOutcomeTest {
    @Test
    fun driver_preserves_rejection_and_unknown_outcome_kinds() =
        runTest {
            for ((answer, kind) in listOf(
                "\"The target is disabled\"" to WorkflowFailureKind.ActionRejected,
                "null" to WorkflowFailureKind.OutcomeUnknown,
            )) {
                val sessions = WebViewSessions().apply { register("page", Page(answer)) }
                val driver = PageDriver(sessions, SessionLeases(backgroundScope), engineContext = EmptyCoroutineContext)
                val failure = assertFailsWith<PageDriverException> { driver.click(css("#buy")) }
                assertEquals(kind, failure.kind)
            }
        }

    private class Page(
        private val answer: String,
    ) : WebViewController {
        private val order = WebViewOrdering()
        override val bridge: WebViewBridge get() = error("unused")

        override suspend fun navigate(url: String) = Unit

        override suspend fun loadHtml(
            html: String,
            baseUrl: String?,
        ) = Unit

        override suspend fun evaluateJs(script: String): String =
            order.ordered {
                if ("el.click();" in script) answer else "true"
            }

        override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

        override fun close() = Unit
    }
}
