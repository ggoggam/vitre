package dev.ggoggam.vitre.koog.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageTarget
import dev.ggoggam.vitre.agent.PageToolDocs
import dev.ggoggam.vitre.agent.PageToolReplies
import dev.ggoggam.vitre.agent.extractSourceFrom
import dev.ggoggam.vitre.agent.locatorFrom
import dev.ggoggam.vitre.agent.session.DEFAULT_LEASE_TTL_MS
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The tools an agent drives a WebView with, under Koog.
//
// Each one reads typed arguments and hands them to PageDriver; none of them builds a step or writes
// a line of JavaScript, and none of them writes the sentence the model reads afterwards. That is the
// same split `vitre-mcp` makes, and for the same reason: what belongs to an adapter is how arguments
// arrive and how a failure travels, and nothing else. Both adapters take their names, their
// descriptions, their replies and their semantics from `vitre-agent`, so an agent that has learned
// Vitre over MCP already knows these.
//
// The names deliberately match the MCP tool names — `snapshot`, `click`, `extract_rows` — so a
// system prompt written for one works unchanged against the other. They are also short enough to
// collide with another toolset in the same registry; `ToolRegistry` fails loudly when that happens,
// which is the right time to find out.

private const val SESSION = PageToolDocs.SESSION
private const val LEASE = PageToolDocs.LEASE

// ── list_sessions ──────────────────────────────────────────────────────────────────────────────

/**
 * Which WebViews are registered.
 *
 * A plain [Tool] rather than a [VitreTool]: it resolves no session and runs no step, so there is no
 * page failure for it to translate.
 */
class ListSessionsTool(
    private val driver: PageDriver,
) : Tool<ListSessionsTool.Args, String>(
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = "list_sessions",
        description = PageToolDocs.LIST_SESSIONS,
    ) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = PageToolReplies.sessions(driver.listSessions())

    override fun encodeResultToString(
        result: String,
        serializer: JSONSerializer,
    ): String = result
}

// ── snapshot ───────────────────────────────────────────────────────────────────────────────────

/** The tool an agent reaches for first, and the only one that needs no knowledge of the page. */
class SnapshotTool(
    driver: PageDriver,
) : VitrePageTool<SnapshotTool.Args, SnapshotTool.Result>(
        driver = driver,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        name = "snapshot",
        description = PageToolDocs.SNAPSHOT,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
        @property:LLMDescription(PageToolDocs.MAX_NODES)
        @SerialName("max_nodes")
        val maxNodes: Int = PageDriver.DEFAULT_MAX_NODES,
    ) : PageToolArgs

    /**
     * The page, as an outline and as counts.
     *
     * Typed rather than a bare string because a host reading a transcript wants "132 elements,
     * truncated" without parsing the outline back, while the model wants only [outline] — which is
     * why that is all [encodeResultToString] sends.
     */
    @Serializable
    data class Result(
        val outline: String,
        val url: String,
        val title: String,
        val nodes: Int,
        val truncated: Boolean,
    )

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): Result {
        val snapshot = driver.snapshot(target, args.maxNodes)
        return Result(
            outline = snapshot.render(),
            url = snapshot.url,
            title = snapshot.title,
            nodes = snapshot.nodes.size,
            truncated = snapshot.truncated,
        )
    }

    override fun encodeResultToString(
        result: Result,
        serializer: JSONSerializer,
    ): String = result.outline
}

// ── navigate ───────────────────────────────────────────────────────────────────────────────────

class NavigateTool(
    driver: PageDriver,
) : VitrePageTextTool<NavigateTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "navigate",
        description = PageToolDocs.NAVIGATE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.URL)
        val url: String,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
    ) : PageToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String {
        val title = driver.navigate(args.url, target)
        return PageToolReplies.navigated(args.url, title)
    }
}

// ── click ──────────────────────────────────────────────────────────────────────────────────────

class ClickTool(
    driver: PageDriver,
) : VitrePageTextTool<ClickTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "click",
        description = PageToolDocs.CLICK,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.REF)
        override val ref: String? = null,
        @property:LLMDescription(PageToolDocs.CSS)
        override val css: String? = null,
        @property:LLMDescription(PageToolDocs.XPATH)
        override val xpath: String? = null,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
        @property:LLMDescription(PageToolDocs.TIMEOUT)
        @SerialName("timeout_ms")
        val timeoutMs: Long = PageDriver.DEFAULT_TIMEOUT_MS,
    ) : LocatorToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String {
        val locator = args.locator()
        driver.click(locator, args.timeoutMs, target)
        return PageToolReplies.clicked(locator)
    }
}

// ── type ───────────────────────────────────────────────────────────────────────────────────────

class TypeTool(
    driver: PageDriver,
) : VitrePageTextTool<TypeTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "type",
        description = PageToolDocs.TYPE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.TEXT)
        val text: String,
        @property:LLMDescription(PageToolDocs.REF)
        override val ref: String? = null,
        @property:LLMDescription(PageToolDocs.CSS)
        override val css: String? = null,
        @property:LLMDescription(PageToolDocs.XPATH)
        override val xpath: String? = null,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
        @property:LLMDescription(PageToolDocs.TIMEOUT)
        @SerialName("timeout_ms")
        val timeoutMs: Long = PageDriver.DEFAULT_TIMEOUT_MS,
    ) : LocatorToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String {
        val locator = args.locator()
        driver.input(locator, args.text, args.timeoutMs, target)
        return PageToolReplies.typed(locator)
    }
}

// ── wait_for ───────────────────────────────────────────────────────────────────────────────────

class WaitForTool(
    driver: PageDriver,
) : VitrePageTextTool<WaitForTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "wait_for",
        description = PageToolDocs.WAIT_FOR,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.REF)
        override val ref: String? = null,
        @property:LLMDescription(PageToolDocs.CSS)
        override val css: String? = null,
        @property:LLMDescription(PageToolDocs.XPATH)
        override val xpath: String? = null,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
        @property:LLMDescription(PageToolDocs.TIMEOUT)
        @SerialName("timeout_ms")
        val timeoutMs: Long = PageDriver.DEFAULT_TIMEOUT_MS,
    ) : LocatorToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String {
        val locator = args.locator()
        driver.waitFor(locator, args.timeoutMs, target)
        return PageToolReplies.present(locator)
    }
}

// ── extract ────────────────────────────────────────────────────────────────────────────────────

class ExtractTool(
    driver: PageDriver,
) : VitrePageTextTool<ExtractTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "extract",
        description = PageToolDocs.EXTRACT,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.REF)
        override val ref: String? = null,
        @property:LLMDescription(PageToolDocs.CSS)
        override val css: String? = null,
        @property:LLMDescription(PageToolDocs.XPATH)
        override val xpath: String? = null,
        @property:LLMDescription(PageToolDocs.FROM)
        val from: String? = null,
        @property:LLMDescription(PageToolDocs.NAME)
        val name: String? = null,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
    ) : LocatorToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String = driver.extract(args.locator(), extractSourceFrom(args.from, args.name), target)
}

// ── extract_rows ───────────────────────────────────────────────────────────────────────────────

class ExtractRowsTool(
    driver: PageDriver,
) : VitrePageTextTool<ExtractRowsTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "extract_rows",
        description = PageToolDocs.EXTRACT_ROWS,
    ) {
    /** One field of a record: where to find it *within* the row, and what to read. */
    @Serializable
    data class Column(
        @property:LLMDescription(PageToolDocs.COLUMN_CSS)
        val css: String? = null,
        @property:LLMDescription(PageToolDocs.COLUMN_XPATH)
        val xpath: String? = null,
        @property:LLMDescription(PageToolDocs.FROM)
        val from: String? = null,
        @property:LLMDescription(PageToolDocs.NAME)
        val name: String? = null,
    )

    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.COLUMNS)
        val columns: Map<String, Column>,
        @property:LLMDescription(PageToolDocs.CSS)
        @SerialName("rows_css")
        val rowsCss: String? = null,
        @property:LLMDescription(PageToolDocs.XPATH)
        @SerialName("rows_xpath")
        val rowsXpath: String? = null,
        @property:LLMDescription(PageToolDocs.LIMIT)
        val limit: Int = PageDriver.DEFAULT_ROW_LIMIT,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
    ) : PageToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String =
        driver.extractRows(
            // No `rowsRef`: a handle names one element, and the point of this tool is a set of them.
            rows = locatorFrom(css = args.rowsCss, xpath = args.rowsXpath, prefix = "rows_", allowRef = false),
            columns =
                args.columns.mapValues { (_, column) ->
                    // A column takes no `ref` either, and the driver says why if one arrives anyway.
                    WorkflowStep.ExtractRows.Column(
                        locatorFrom(css = column.css, xpath = column.xpath, allowRef = false),
                        extractSourceFrom(column.from, column.name),
                    )
                },
            limit = args.limit,
            target = target,
        )
}

// ── evaluate ───────────────────────────────────────────────────────────────────────────────────

class EvaluateTool(
    driver: PageDriver,
) : VitrePageTextTool<EvaluateTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "evaluate",
        description = PageToolDocs.EVALUATE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.SCRIPT)
        val script: String,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
    ) : PageToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String = driver.evaluate(args.script, target).ifEmpty { PageToolReplies.NO_VALUE }
}

// ── send_message / await_message ───────────────────────────────────────────────────────────────

class SendMessageTool(
    driver: PageDriver,
) : VitrePageTextTool<SendMessageTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "send_message",
        description = PageToolDocs.SEND_MESSAGE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.MESSAGE)
        val message: String,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
    ) : PageToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String {
        driver.postMessage(args.message, target)
        return PageToolReplies.POSTED
    }
}

class AwaitMessageTool(
    driver: PageDriver,
) : VitrePageTextTool<AwaitMessageTool.Args>(
        driver = driver,
        argsType = typeToken<Args>(),
        name = "await_message",
        description = PageToolDocs.AWAIT_MESSAGE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.MESSAGE_TYPE)
        val type: String,
        @property:LLMDescription(SESSION)
        override val session: String? = null,
        @property:LLMDescription(LEASE)
        override val lease: String? = null,
        @property:LLMDescription(PageToolDocs.WAIT_TIMEOUT)
        @SerialName("timeout_ms")
        val timeoutMs: Long = PageDriver.DEFAULT_TIMEOUT_MS,
    ) : PageToolArgs

    override suspend fun act(
        args: Args,
        target: PageTarget,
    ): String = driver.awaitMessage(args.type, args.timeoutMs, target)
}

// ── leases ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Takes a WebView for a sequence of calls.
 *
 * Registered only when the host has *not* installed [dev.ggoggam.vitre.koog.feature.VitrePageLease];
 * with the feature on, the run already holds a lease and handing the model a second one is an
 * invitation to deadlock against itself.
 */
class AcquireLeaseTool(
    driver: PageDriver,
) : VitreTool<AcquireLeaseTool.Args, String>(
        driver = driver,
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = "acquire_lease",
        description = PageToolDocs.ACQUIRE_LEASE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(SESSION)
        val session: String? = null,
        @property:LLMDescription(PageToolDocs.TTL)
        @SerialName("ttl_ms")
        val ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    )

    override suspend fun run(
        args: Args,
        metadata: ToolCallMetadata,
    ): String {
        val grant = driver.acquireLease(args.session, args.ttlMs)
        return PageToolReplies.leaseHeld(grant)
    }

    override fun encodeResultToString(
        result: String,
        serializer: JSONSerializer,
    ): String = result
}

class ReleaseLeaseTool(
    driver: PageDriver,
) : VitreTool<ReleaseLeaseTool.Args, String>(
        driver = driver,
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = "release_lease",
        description = PageToolDocs.RELEASE_LEASE,
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription(PageToolDocs.LEASE_ID)
        val lease: String,
    )

    override suspend fun run(
        args: Args,
        metadata: ToolCallMetadata,
    ): String =
        if (driver.releaseLease(args.lease)) {
            PageToolReplies.leaseReleased(args.lease)
        } else {
            PageToolReplies.leaseNotActive(args.lease)
        }

    override fun encodeResultToString(
        result: String,
        serializer: JSONSerializer,
    ): String = result
}
