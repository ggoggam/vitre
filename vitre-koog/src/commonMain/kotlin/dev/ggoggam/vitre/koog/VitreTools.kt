package dev.ggoggam.vitre.koog

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.koog.tools.AcquireLeaseTool
import dev.ggoggam.vitre.koog.tools.AwaitMessageTool
import dev.ggoggam.vitre.koog.tools.ClickTool
import dev.ggoggam.vitre.koog.tools.EvaluateTool
import dev.ggoggam.vitre.koog.tools.ExtractRowsTool
import dev.ggoggam.vitre.koog.tools.ExtractTool
import dev.ggoggam.vitre.koog.tools.ListSessionsTool
import dev.ggoggam.vitre.koog.tools.NavigateTool
import dev.ggoggam.vitre.koog.tools.ReadNetworkTool
import dev.ggoggam.vitre.koog.tools.ReleaseLeaseTool
import dev.ggoggam.vitre.koog.tools.SendMessageTool
import dev.ggoggam.vitre.koog.tools.SnapshotTool
import dev.ggoggam.vitre.koog.tools.TypeTool
import dev.ggoggam.vitre.koog.tools.WaitForTool

/**
 * The Vitre page tools, ready to hand to a Koog agent.
 *
 * ```kotlin
 * val sessions = WebViewSessions().apply { register("main", controller, "the shopping tab") }
 * val driver = PageDriver(sessions, scope)
 *
 * val agent = AIAgent(
 *     promptExecutor = executor,
 *     llmModel = OpenAIModels.Chat.GPT4_1, // yours; this module brings no LLM client
 *     systemPrompt = PageToolDocs.INSTRUCTIONS,
 *     toolRegistry = ToolRegistry { vitreWebView(driver) },
 * )
 * ```
 *
 * Two things that snippet leaves out, both of which a real model runs into and a mocked one does
 * not: Koog needs an HTTP transport provider (`ai.koog:http-client-ktor`) on the runtime classpath
 * beside the LLM client, and the default agent loop ends the run on a message that carries text
 * *and* a tool call — which Anthropic models routinely send. See `docs/KOOG.md` for both.
 *
 * @param includeLeaseTools whether the model may take a lease itself. Leave it on for an agent that
 *   shares the WebView with a UI and knows when its own sequences must not be interrupted; turn it
 *   off when [dev.ggoggam.vitre.koog.feature.VitrePageLease] is installed, since the run then
 *   already holds the page and a model that asks for it again is waiting on itself.
 */
fun vitreWebViewTools(
    driver: PageDriver,
    includeLeaseTools: Boolean = true,
): List<ToolBase<*, *>> =
    buildList {
        add(ListSessionsTool(driver))
        add(SnapshotTool(driver))
        add(NavigateTool(driver))
        add(ClickTool(driver))
        add(TypeTool(driver))
        add(WaitForTool(driver))
        add(ExtractTool(driver))
        add(ExtractRowsTool(driver))
        add(EvaluateTool(driver))
        add(SendMessageTool(driver))
        add(AwaitMessageTool(driver))
        add(ReadNetworkTool(driver))
        if (includeLeaseTools) {
            add(AcquireLeaseTool(driver))
            add(ReleaseLeaseTool(driver))
        }
    }

/** Adds [vitreWebViewTools] to a registry being built. */
fun ToolRegistryBuilder.vitreWebView(
    driver: PageDriver,
    includeLeaseTools: Boolean = true,
): ToolRegistryBuilder = tools(vitreWebViewTools(driver, includeLeaseTools))

/** A registry of nothing but the Vitre page tools, for an agent that does only this. */
fun vitreWebViewToolRegistry(
    driver: PageDriver,
    includeLeaseTools: Boolean = true,
): ToolRegistry = ToolRegistry { vitreWebView(driver, includeLeaseTools) }
