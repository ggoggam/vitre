package dev.ggoggam.vitre.mcp.tools

import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageDriverException
import dev.ggoggam.vitre.agent.PageTarget
import dev.ggoggam.vitre.agent.PageToolDocs
import dev.ggoggam.vitre.agent.PageToolReplies
import dev.ggoggam.vitre.agent.extractSourceFrom
import dev.ggoggam.vitre.agent.locatorFrom
import dev.ggoggam.vitre.agent.session.DEFAULT_LEASE_TTL_MS
import dev.ggoggam.vitre.agent.session.LeaseException
import dev.ggoggam.vitre.agent.session.NoSuchSessionException
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.describe
import dev.ggoggam.vitre.mcp.protocol.ToolDefinition
import dev.ggoggam.vitre.mcp.protocol.ToolResult
import dev.ggoggam.vitre.mcp.protocol.intProp
import dev.ggoggam.vitre.mcp.protocol.locatorProps
import dev.ggoggam.vitre.mcp.protocol.long
import dev.ggoggam.vitre.mcp.protocol.obj
import dev.ggoggam.vitre.mcp.protocol.string
import dev.ggoggam.vitre.mcp.protocol.stringProp
import dev.ggoggam.vitre.mcp.protocol.toolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** An argument this server could not read. Never a protocol error — see [ToolResult.isError]. */
internal class ToolFailure(
    override val message: String,
) : RuntimeException(message)

/**
 * The tools an agent drives a WebView with, over MCP.
 *
 * Every one of them reads its arguments out of JSON and hands the result to [PageDriver], rather
 * than building steps of its own. That split is the whole design: what belongs to MCP is the schema
 * a model is shown, the JSON its answer arrives as, and the shape a failure travels in — and
 * nothing else. Which steps a click expands to, how long a timeout may be, and what a stale handle
 * costs are page semantics, and they live in `vitre-agent` so that the Koog adapter next door
 * cannot disagree with this one about them.
 *
 * The [PageDriver]'s failures become [ToolResult.isError] results, which is where an agent can
 * actually read them.
 */
internal class WebViewTools(
    private val driver: PageDriver,
) {
    fun definitions(): List<ToolDefinition> = DEFINITIONS

    suspend fun call(
        name: String,
        args: JsonObject,
    ): ToolResult =
        try {
            dispatch(name, args)
        } catch (failure: ToolFailure) {
            ToolResult.failure(failure.message)
        } catch (failure: PageDriverException) {
            ToolResult.failure(failure.message)
        } catch (missing: NoSuchSessionException) {
            ToolResult.failure(missing.message ?: "No such session")
        } catch (lease: LeaseException) {
            ToolResult.failure(lease.message ?: "Lease unavailable")
        }

    private suspend fun dispatch(
        name: String,
        args: JsonObject,
    ): ToolResult =
        when (name) {
            "list_sessions" -> listSessions()
            "snapshot" -> snapshot(args)
            "navigate" -> navigate(args)
            "click" -> click(args)
            "type" -> type(args)
            "wait_for" -> waitFor(args)
            "extract" -> extract(args)
            "extract_rows" -> extractRows(args)
            "evaluate" -> evaluate(args)
            "send_message" -> sendMessage(args)
            "await_message" -> awaitMessage(args)
            "acquire_lease" -> acquireLease(args)
            "release_lease" -> releaseLease(args)
            else -> throw ToolFailure("Unknown tool `$name`.")
        }

    // ── Tools ──────────────────────────────────────────────────────────────────────────────────

    private fun listSessions(): ToolResult {
        val all = driver.listSessions()
        val text = PageToolReplies.sessions(all)
        if (all.isEmpty()) return ToolResult(text)
        val structured =
            buildJsonObject {
                put(
                    "sessions",
                    buildJsonArray {
                        all.forEach { session ->
                            add(
                                buildJsonObject {
                                    put("id", session.id)
                                    put("description", session.description)
                                },
                            )
                        }
                    },
                )
            }
        return ToolResult(text, structured = structured)
    }

    private suspend fun snapshot(args: JsonObject): ToolResult {
        val snapshot =
            driver.snapshot(
                target = args.target(),
                maxNodes = args.intOr("max_nodes", PageDriver.DEFAULT_MAX_NODES),
            )
        return ToolResult(snapshot.render(), structured = buildJsonObject { put("nodes", snapshot.nodes.size) })
    }

    private suspend fun navigate(args: JsonObject): ToolResult {
        val url = args.requiredString("url")
        val title = driver.navigate(url, args.target())
        return ToolResult(PageToolReplies.navigated(url, title))
    }

    private suspend fun click(args: JsonObject): ToolResult {
        val locator = args.locator()
        driver.click(locator, args.timeoutMs(), args.target())
        return ToolResult(PageToolReplies.clicked(locator))
    }

    private suspend fun type(args: JsonObject): ToolResult {
        val locator = args.locator()
        // `text` may be empty: clearing a field is a real thing to ask for, and refusing it here
        // when the Koog adapter allows it is the two-adapters-one-vocabulary claim breaking.
        driver.input(locator, args.presentString("text"), args.timeoutMs(), args.target())
        return ToolResult(PageToolReplies.typed(locator))
    }

    private suspend fun waitFor(args: JsonObject): ToolResult {
        val locator = args.locator()
        driver.waitFor(locator, args.timeoutMs(), args.target())
        return ToolResult(PageToolReplies.present(locator))
    }

    private suspend fun extract(args: JsonObject): ToolResult =
        ToolResult(driver.extract(args.locator(), args.extractSource(), args.target()))

    private suspend fun extractRows(args: JsonObject): ToolResult {
        // The rows locator is read first, so a call malformed in both places is told about the
        // argument it named rather than the one it left out — and the emptiness of `columns` is the
        // driver's to judge, not this adapter's, so both adapters give the same correction.
        val rows = args.locator("rows_", allowRef = false)
        val columns =
            args.obj("columns").orEmpty().mapValues { (name, spec) ->
                val obj =
                    spec as? JsonObject
                        ?: throw ToolFailure("Column `$name` must be an object with a `css` or `xpath` key.")
                WorkflowStep.ExtractRows.Column(obj.locator(allowRef = false), obj.extractSource())
            }
        return ToolResult(
            driver.extractRows(
                rows = rows,
                columns = columns,
                limit = args.intOr("limit", PageDriver.DEFAULT_ROW_LIMIT),
                target = args.target(),
            ),
        )
    }

    private suspend fun evaluate(args: JsonObject): ToolResult {
        val result = driver.evaluate(args.requiredString("script"), args.target())
        return ToolResult(result.ifEmpty { PageToolReplies.NO_VALUE })
    }

    private suspend fun sendMessage(args: JsonObject): ToolResult {
        // Empty is a legitimate payload, and the Koog adapter accepts one.
        driver.postMessage(args.presentString("message"), args.target())
        return ToolResult(PageToolReplies.POSTED)
    }

    private suspend fun awaitMessage(args: JsonObject): ToolResult =
        ToolResult(driver.awaitMessage(args.requiredString("type"), args.timeoutMs(), args.target()))

    private suspend fun acquireLease(args: JsonObject): ToolResult {
        val grant = driver.acquireLease(args.string("session"), args.long("ttl_ms") ?: DEFAULT_LEASE_TTL_MS)
        return ToolResult(
            PageToolReplies.leaseHeld(grant),
            structured = buildJsonObject { put("lease", grant.id) },
        )
    }

    private fun releaseLease(args: JsonObject): ToolResult {
        val id = args.requiredString("lease")
        return if (driver.releaseLease(id)) {
            ToolResult(PageToolReplies.leaseReleased(id))
        } else {
            ToolResult(PageToolReplies.leaseNotActive(id))
        }
    }

    // ── Argument reading ───────────────────────────────────────────────────────────────────────

    private fun JsonObject.target(): PageTarget = PageTarget(session = string("session"), lease = string("lease"))

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotEmpty() }
            ?: throw ToolFailure("`$name` is required and must be a non-empty string.")

    /**
     * A required string that may be empty.
     *
     * For the arguments that carry a *value* rather than a name: clearing a field with `text: ""` or
     * posting an empty payload are things a page can be asked for, and the Koog adapter's `String`
     * parameter accepts them, so refusing them here would be a divergence rather than a check.
     */
    private fun JsonObject.presentString(name: String): String =
        string(name) ?: throw ToolFailure("`$name` is required and must be a string.")

    private fun JsonObject.timeoutMs(): Long = long("timeout_ms") ?: PageDriver.DEFAULT_TIMEOUT_MS

    /**
     * A count the driver will clamp. Read leniently here on purpose: a model that sends `"20"` for an
     * integer has made a mistake it cannot see the consequences of, and the bounds that actually
     * matter are enforced one layer down where both adapters share them.
     */
    private fun JsonObject.intOr(
        name: String,
        default: Int,
    ): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

    private fun JsonObject.locator(
        prefix: String = "",
        allowRef: Boolean = true,
    ): Locator =
        locatorFrom(
            ref = string("${prefix}ref"),
            css = string("${prefix}css"),
            xpath = string("${prefix}xpath"),
            prefix = prefix,
            allowRef = allowRef,
        )

    private fun JsonObject.extractSource(): WorkflowStep.Extract.Source = extractSourceFrom(string("from"), string("name"))

    private companion object {
        val DEFINITIONS: List<ToolDefinition> =
            listOf(
                ToolDefinition(
                    name = "list_sessions",
                    title = "List WebView sessions",
                    description = PageToolDocs.LIST_SESSIONS,
                    inputSchema = toolSchema { },
                ),
                ToolDefinition(
                    name = "snapshot",
                    title = "Snapshot the page",
                    description = PageToolDocs.SNAPSHOT,
                    inputSchema =
                        toolSchema {
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                            intProp("max_nodes", PageToolDocs.MAX_NODES)
                        },
                ),
                ToolDefinition(
                    name = "navigate",
                    title = "Load a URL",
                    description = PageToolDocs.NAVIGATE,
                    inputSchema =
                        toolSchema(required = listOf("url")) {
                            stringProp("url", PageToolDocs.URL)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                        },
                ),
                ToolDefinition(
                    name = "click",
                    title = "Click an element",
                    description = PageToolDocs.CLICK,
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                            intProp("timeout_ms", PageToolDocs.TIMEOUT)
                        },
                ),
                ToolDefinition(
                    name = "type",
                    title = "Type into a field",
                    description = PageToolDocs.TYPE,
                    inputSchema =
                        toolSchema(required = listOf("text")) {
                            locatorProps()
                            stringProp("text", PageToolDocs.TEXT)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                            intProp("timeout_ms", PageToolDocs.TIMEOUT)
                        },
                ),
                ToolDefinition(
                    name = "wait_for",
                    title = "Wait for an element",
                    description = PageToolDocs.WAIT_FOR,
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                            intProp("timeout_ms", PageToolDocs.TIMEOUT)
                        },
                ),
                ToolDefinition(
                    name = "extract",
                    title = "Read one value",
                    description = PageToolDocs.EXTRACT,
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("from", PageToolDocs.FROM)
                            stringProp("name", PageToolDocs.NAME)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                        },
                ),
                ToolDefinition(
                    name = "extract_rows",
                    title = "Read a list or table",
                    description = PageToolDocs.EXTRACT_ROWS,
                    inputSchema =
                        toolSchema(required = listOf("columns")) {
                            locatorProps(prefix = "rows_", allowRef = false)
                            putJsonObject("columns") {
                                put("type", "object")
                                put("description", PageToolDocs.COLUMNS)
                                putJsonObject("additionalProperties") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        stringProp("css", PageToolDocs.COLUMN_CSS)
                                        stringProp("xpath", PageToolDocs.COLUMN_XPATH)
                                        stringProp("from", PageToolDocs.FROM)
                                        stringProp("name", PageToolDocs.NAME)
                                    }
                                }
                            }
                            intProp("limit", PageToolDocs.LIMIT)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                        },
                ),
                ToolDefinition(
                    name = "evaluate",
                    title = "Evaluate JavaScript",
                    description = PageToolDocs.EVALUATE,
                    inputSchema =
                        toolSchema(required = listOf("script")) {
                            stringProp("script", PageToolDocs.SCRIPT)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                        },
                ),
                ToolDefinition(
                    name = "send_message",
                    title = "Send a message to the page",
                    description = PageToolDocs.SEND_MESSAGE,
                    inputSchema =
                        toolSchema(required = listOf("message")) {
                            stringProp("message", PageToolDocs.MESSAGE)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                        },
                ),
                ToolDefinition(
                    name = "await_message",
                    title = "Wait for a message from the page",
                    description = PageToolDocs.AWAIT_MESSAGE,
                    inputSchema =
                        toolSchema(required = listOf("type")) {
                            stringProp("type", PageToolDocs.MESSAGE_TYPE)
                            stringProp("session", PageToolDocs.SESSION)
                            stringProp("lease", PageToolDocs.LEASE)
                            intProp("timeout_ms", PageToolDocs.WAIT_TIMEOUT)
                        },
                ),
                ToolDefinition(
                    name = "acquire_lease",
                    title = "Hold a session for a sequence",
                    description = PageToolDocs.ACQUIRE_LEASE,
                    inputSchema =
                        toolSchema {
                            stringProp("session", PageToolDocs.SESSION)
                            intProp("ttl_ms", PageToolDocs.TTL)
                        },
                ),
                ToolDefinition(
                    name = "release_lease",
                    title = "Release a held session",
                    description = PageToolDocs.RELEASE_LEASE,
                    inputSchema =
                        toolSchema(required = listOf("lease")) {
                            stringProp("lease", PageToolDocs.LEASE_ID)
                        },
                ),
            )
    }
}
