package dev.ggoggam.vitre.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageDriverException
import dev.ggoggam.vitre.agent.PageToolDocs
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.webview.AndroidWebViewController
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.handle
import dev.ggoggam.vitre.koog.feature.VitrePageLease
import dev.ggoggam.vitre.koog.tools.ClickTool
import dev.ggoggam.vitre.koog.tools.ExtractTool
import dev.ggoggam.vitre.koog.tools.SnapshotTool
import dev.ggoggam.vitre.koog.tools.TypeTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole stack, on a real device: a Koog agent, its tool registry, the lease feature, and an
 * Android `WebView` running real JavaScript underneath all of it.
 *
 * Everything below the tools is faked in the host tests — a `FakePageController` answering scripts
 * from a lookup table — which is what makes those tests fast and what makes them unable to prove
 * this. Here the snapshot is walked by the snapshot script inside the page, the handles come out of
 * a registry on `window`, and the click is a click.
 *
 * The model is mocked and nothing else is. That is the right place to cut: what this test is for is
 * the agent loop, the feature's metadata, the tool dispatch and the WebView — not an LLM's choice of
 * which tool to call, which would make it a bill and a flake rather than a test.
 */
class KoogAgentOnDeviceTest {
    private lateinit var webView: WebView
    private lateinit var controller: AndroidWebViewController
    private lateinit var sessions: WebViewSessions
    private lateinit var driver: PageDriver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val page =
        """
        <!doctype html>
        <html><body>
          <h1>Vitre fixture</h1>
          <label for="q">Search</label>
          <input id="q" type="text" value="">
          <button id="go" onclick="document.getElementById('out').textContent =
              'searched: ' + document.getElementById('q').value">Search</button>
          <p id="out">nothing yet</p>
        </body></html>
        """.trimIndent()

    @Before
    fun mountAWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // runOnMainSync swallows nothing but reports on the main thread, so a failure here would
        // otherwise surface as `lateinit not initialized` from @After — the wrong stack entirely.
        var setupFailure: Throwable? = null
        instrumentation.runOnMainSync {
            runCatching {
                webView =
                    WebView(instrumentation.targetContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                    }
                controller = AndroidWebViewController(webView)
            }.onFailure { setupFailure = it }
        }
        setupFailure?.let { throw AssertionError("could not mount a WebView: $it", it) }
        sessions = WebViewSessions()
        sessions.register("main", controller, "the instrumented WebView")
        driver = PageDriver(sessions, scope)
    }

    @After
    fun tearDown() {
        // Leases first, and before the controller goes: a claim outliving its test leaves a
        // coroutine parked inside `exclusively` on a WebView that is about to be destroyed, and the
        // next test in the class then queues behind a lock nothing will ever give back.
        if (::driver.isInitialized) {
            driver.leases.active.keys
                .forEach { driver.releaseLease(it) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (::controller.isInitialized) controller.close()
            if (::webView.isInitialized) webView.destroy()
        }
        // The scope outlives the WebView otherwise: a SupervisorJob nobody cancels keeps every lease
        // holder this test launched alive for the rest of the instrumentation run.
        scope.cancel()
    }

    @Test
    fun an_agent_that_has_never_seen_the_page_fills_its_form_and_reads_the_answer() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                loadFixture()

                // The refs the agent will act on are read out of the page first, rather than
                // written into the test. Handles are minted by the document and kept across
                // snapshots, so these are the same ones the agent's own `snapshot` call returns —
                // and a fixture edited later cannot leave this test asserting on stale numbers.
                val preflight = driver.snapshot()
                val field = preflight.nodes.first { it.tag == "input" }.ref
                val button = preflight.nodes.first { it.tag == "button" }.ref

                val executor =
                    getMockExecutor {
                        mockLLMToolCall(callTo<SnapshotTool.Args>("snapshot"), SnapshotTool.Args()) onRequestContains
                            "fill in this form"
                        mockLLMToolCall(
                            callTo<TypeTool.Args>("type"),
                            TypeTool.Args(text = "keyboard", ref = field),
                        ) onRequestContains "Vitre fixture"
                        mockLLMToolCall(callTo<ClickTool.Args>("click"), ClickTool.Args(ref = button)) onRequestContains
                            "Typed into"
                        mockLLMToolCall(callTo<ExtractTool.Args>("extract"), ExtractTool.Args(css = "#out")) onRequestContains
                            "Clicked"
                        mockLLMAnswer("The page now says: searched: keyboard") onRequestContains "searched:"
                    }

                val agent =
                    AIAgent(
                        promptExecutor = executor,
                        llmModel = OpenAIModels.Chat.GPT4_1,
                        systemPrompt = PageToolDocs.INSTRUCTIONS,
                        toolRegistry = ToolRegistry { vitreWebView(driver, includeLeaseTools = false) },
                    ) {
                        install(VitrePageLease) {
                            driver = this@KoogAgentOnDeviceTest.driver
                            ttlMs = TIMEOUT_MS
                        }
                    }

                val answer = agent.run("Please fill in this form with 'keyboard' and tell me what it says.")

                assertTrue("agent answered: $answer", answer.contains("searched: keyboard"))

                // Read back outside the agent, through a second path into the same document, so
                // this cannot pass on a tool that reported success without touching the page. The
                // values are plain: `evaluateJs` hands back the JSON encoding, and the engine
                // decodes it on the way into a variable, on every platform.
                assertEquals("searched: keyboard", driver.evaluate("document.getElementById('out').textContent"))
                assertEquals("keyboard", driver.evaluate("document.getElementById('q').value"))
            }
        }

    @Test
    fun the_handles_an_agent_acts_on_are_minted_by_the_real_document() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                loadFixture()

                val snapshot = driver.snapshot()
                val outline = snapshot.render()

                // The snapshot script ran in the page and walked it. A fake controller answering
                // from a lookup table cannot tell us whether it does.
                assertTrue(outline, "Vitre fixture" in outline)
                assertTrue(outline, "[ref=e" in outline)
                assertNotNull(outline, snapshot.nodes.firstOrNull { it.tag == "button" })

                // A handle from this document resolves.
                driver.waitFor(handle(snapshot.nodes.first { it.tag == "button" }.ref))

                // One this document never issued does not, and says so — rather than resolving
                // against a same-shaped element, which is the failure a handle exists to rule out.
                val failure = runCatching { driver.extract(handle("e9999")) }.exceptionOrNull()
                assertNotNull("a handle the page never issued must fail loudly", failure)
                // Named, not just thrown: a closed controller or a broken snapshot script would
                // satisfy "something threw" while proving nothing about handle resolution.
                assertTrue("failure was $failure", failure is PageDriverException)
                assertTrue("$failure", "e9999" in (failure?.message ?: ""))
            }
        }

    @Test
    fun a_lease_really_holds_the_webview_against_a_second_caller() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                loadFixture()

                val held = driver.acquireLease(ttlMs = 30_000)

                // The claim is on the controller's own ordering lock, not on bookkeeping beside it,
                // so a second lease cannot be granted while the first is alive. The short acquire
                // timeout is what makes this a contention test rather than a patience test: with
                // the default fifteen seconds a waiter simply outlives a shorter-lived holder and
                // is then granted legitimately, which is the lease working, not failing.
                val contended =
                    runCatching {
                        driver.leases.acquire(driver.sessions.resolve(null), ttlMs = 1_000, acquireTimeoutMs = 500)
                    }
                assertTrue("a second lease was granted while one was held", contended.isFailure)

                driver.releaseLease(held.id)

                // And the WebView is usable again the moment it is given back.
                assertEquals("nothing yet", driver.evaluate("document.getElementById('out').textContent"))
                val regranted = runCatching { driver.acquireLease(ttlMs = 1_000) }
                assertTrue("the lease should be grantable once released", regranted.isSuccess)
                // Given back rather than left to expire: @After destroys the WebView, and a holder
                // still parked inside `exclusively` on it is a coroutine with nothing to hold.
                regranted.getOrNull()?.let { driver.releaseLease(it.id) }
            }
        }

    private suspend fun loadFixture() {
        // loadHtml rather than a URL: the point is the WebView and the agent, and a test that needs
        // the network is a test that fails for reasons that have nothing to do with either.
        controller.loadHtml(page, "https://fixture.vitre.test/")
        // Explicitly the test's own budget, not the tools' ten-second default: a cold emulator can
        // spend longer than that on the first loadHtml and the JS engine's warm-up, and a failure
        // there reads as "the fixture has no #go button" rather than "the device was slow".
        driver.waitFor(css("#go"), timeoutMs = TIMEOUT_MS)
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L

        /**
         * A stand-in that exists only to *spell* a tool call.
         *
         * Koog's mock-executor DSL takes a `Tool`, and reads exactly two things off it: the name,
         * and how to encode the arguments. The page tools extend `ToolBase` instead — they have to,
         * because the ambient lease arrives as call metadata and `Tool.execute` discards it — so
         * they do not fit that parameter. This does, and it is enough: the call the mock emits names
         * the real tool, and the agent dispatches it through the registry to the real one. Nothing
         * here is ever executed, which the body says out loud.
         */
        inline fun <reified A> callTo(name: String): Tool<A, String> = CallSpec(name, typeToken<A>())

        class CallSpec<A>(
            name: String,
            argsType: TypeToken,
        ) : Tool<A, String>(argsType, typeToken<String>(), name, "spelled by the test, dispatched by the registry") {
            override suspend fun execute(args: A): String =
                error("$name was dispatched to the test's stand-in; the registry should hold the real one")
        }
    }
}
