package dev.ggoggam.vitre.mcp.tools

import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.PageSnapshot
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.describe
import dev.ggoggam.vitre.core.workflow.handle
import dev.ggoggam.vitre.core.workflow.xpath
import dev.ggoggam.vitre.mcp.protocol.ToolDefinition
import dev.ggoggam.vitre.mcp.protocol.ToolResult
import dev.ggoggam.vitre.mcp.protocol.intProp
import dev.ggoggam.vitre.mcp.protocol.locatorProps
import dev.ggoggam.vitre.mcp.protocol.long
import dev.ggoggam.vitre.mcp.protocol.obj
import dev.ggoggam.vitre.mcp.protocol.string
import dev.ggoggam.vitre.mcp.protocol.stringProp
import dev.ggoggam.vitre.mcp.protocol.toolSchema
import dev.ggoggam.vitre.mcp.session.LeaseException
import dev.ggoggam.vitre.mcp.session.NoSuchSessionException
import dev.ggoggam.vitre.mcp.session.SessionLease
import dev.ggoggam.vitre.mcp.session.SessionLeases
import dev.ggoggam.vitre.mcp.session.WebViewSession
import dev.ggoggam.vitre.mcp.session.WebViewSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext

/** A tool call that failed in a way the model can read and correct. Never a protocol error. */
internal class ToolFailure(
    override val message: String,
) : RuntimeException(message)

/**
 * The tools an agent drives a WebView with.
 *
 * Every one of them builds a [WorkflowStep] and hands it to [WorkflowEngine], rather than generating
 * its own JavaScript. That is not tidiness. The library already had two implementations of "talk to
 * a WebView" — the Android and iOS actuals — and they drifted until a boolean meant `true` on one
 * platform and `"1"` on the other, which nothing noticed for as long as it took to write the smoke
 * test. A second implementation of the *step vocabulary*, living here and reached only through an
 * agent, would drift the same way and be even harder to notice, because no unit test drives it.
 *
 * So `click` here and `Click` in a workflow generate one expression, escape strings once, and
 * resolve handles through one guard. The engine's failures become [ToolResult.isError] results,
 * which is where an agent can actually read them.
 */
internal class WebViewTools(
    private val sessions: WebViewSessions,
    private val leases: SessionLeases,
    private val engineContext: CoroutineContext = Dispatchers.Default,
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
        val all = sessions.all()
        if (all.isEmpty()) {
            return ToolResult(
                "No WebView sessions are registered. The host application registers them; until it " +
                    "does there is no page to drive.",
            )
        }
        val text =
            all.joinToString("\n") { session ->
                buildString {
                    append("- ")
                    append(session.id)
                    if (session.description.isNotBlank()) append(" — ${session.description}")
                    if (all.size == 1) append(" (the only session, so `session` may be omitted)")
                }
            }
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
        val maxNodes = args.intInRange("max_nodes", default = DEFAULT_MAX_NODES, min = 1, max = MAX_MAX_NODES)
        val raw =
            args.runStep(
                WorkflowStep.Snapshot(into = OUT, maxNodes = maxNodes),
                expecting = OUT,
            )
        val snapshot =
            runCatching { PageSnapshot.decode(raw) }
                .getOrElse { throw ToolFailure("The page returned a snapshot this build cannot read: ${it.message}") }
        return ToolResult(snapshot.render(), structured = buildJsonObject { put("nodes", snapshot.nodes.size) })
    }

    private suspend fun navigate(args: JsonObject): ToolResult {
        val url = args.requiredString("url")
        val title =
            args.runSteps(
                listOf(
                    WorkflowStep.Navigate(url),
                    WorkflowStep.EvaluateJs("document.title", OUT),
                ),
                expecting = OUT,
            )
        return ToolResult("Loaded $url — \"$title\". Take a `snapshot` to see what is on it.")
    }

    private suspend fun click(args: JsonObject): ToolResult {
        val locator = args.locator()
        val timeout = args.timeoutMs()
        // WaitFor first, and not only for slow pages. `Click` on a locator that matches nothing is a
        // no-op that reports success — the generated expression is `…?.click()` — so without this an
        // agent is told it pressed a button that was never there and carries on from a state that
        // does not exist. The wait turns that into "Timeout waiting for css `#buy`".
        args.runSteps(
            listOf(
                WorkflowStep.WaitFor(locator, timeout),
                WorkflowStep.Click(locator),
            ),
        )
        return ToolResult(
            "Clicked ${locator.describe()}. If it navigated or changed the page, take a new " +
                "`snapshot` — handles from before the click may no longer resolve.",
        )
    }

    private suspend fun type(args: JsonObject): ToolResult {
        val locator = args.locator()
        val text = args.requiredString("text")
        args.runSteps(
            listOf(
                WorkflowStep.WaitFor(locator, args.timeoutMs()),
                WorkflowStep.Input(locator, text),
            ),
        )
        return ToolResult("Typed into ${locator.describe()}.")
    }

    private suspend fun waitFor(args: JsonObject): ToolResult {
        val locator = args.locator()
        args.runStep(WorkflowStep.WaitFor(locator, args.timeoutMs()))
        return ToolResult("${locator.describe()} is present.")
    }

    private suspend fun extract(args: JsonObject): ToolResult {
        val locator = args.locator()
        val value = args.runStep(WorkflowStep.Extract(locator, OUT, args.extractSource()), expecting = OUT)
        if (value.isEmpty()) {
            return ToolResult(
                "${locator.describe()} matched nothing, or matched an element with no value to read. " +
                    "Take a `snapshot` to see what is actually there.",
                isError = true,
            )
        }
        return ToolResult(value)
    }

    private suspend fun extractRows(args: JsonObject): ToolResult {
        val rows = args.locator("rows_")
        val columnSpecs =
            args.obj("columns")?.takeIf { it.isNotEmpty() }
                ?: throw ToolFailure(
                    "`columns` is required: an object mapping each field name to a locator resolved " +
                        "*within* one row, e.g. {\"title\": {\"css\": \"h2\"}, \"price\": {\"css\": \".price\"}}.",
                )
        val columns =
            columnSpecs.mapValues { (name, spec) ->
                val obj =
                    spec as? JsonObject
                        ?: throw ToolFailure("Column `$name` must be an object with a `css` or `xpath` key.")
                WorkflowStep.ExtractRows.Column(obj.locator(inRow = true), obj.extractSource())
            }
        val limit = args.intInRange("limit", default = DEFAULT_ROW_LIMIT, min = 1, max = MAX_ROW_LIMIT)
        val json = args.runStep(WorkflowStep.ExtractRows(rows, columns, OUT, limit), expecting = OUT)
        return ToolResult(json)
    }

    private suspend fun evaluate(args: JsonObject): ToolResult {
        val script = args.requiredString("script")
        val result = args.runStep(WorkflowStep.EvaluateJs(script, OUT), expecting = OUT)
        return ToolResult(result.ifEmpty { "(the expression produced no value)" })
    }

    private suspend fun sendMessage(args: JsonObject): ToolResult {
        val message = args.requiredString("message")
        args.runStep(WorkflowStep.PostMessage(message))
        return ToolResult("Posted to the page.")
    }

    private suspend fun awaitMessage(args: JsonObject): ToolResult {
        val type = args.requiredString("type")
        val received =
            args.runStep(
                WorkflowStep.AwaitMessage(type, OUT, args.timeoutMs()),
                expecting = OUT,
            )
        return ToolResult(received)
    }

    private suspend fun acquireLease(args: JsonObject): ToolResult {
        val session = sessions.resolve(args.string("session"))
        // Clamped like every other duration the agent supplies: the whole point of the TTL is to
        // defend the WebView against a client that stops, and an unbounded `ttl_ms` lets that same
        // client wedge the page for days — while `ttl_ms=0` would return a lease that is already
        // dead. The lower bound keeps a lease alive long enough to be usable.
        val ttl = (args.long("ttl_ms") ?: dev.ggoggam.vitre.mcp.session.DEFAULT_LEASE_TTL_MS).coerceIn(MIN_LEASE_TTL_MS, MAX_LEASE_TTL_MS)
        val lease = leases.acquire(session, ttlMs = ttl)
        return ToolResult(
            "Holding session `${session.id}` as lease `${lease.id}` for up to ${ttl}ms. Pass " +
                "`lease: \"${lease.id}\"` on every call that belongs to this sequence, and " +
                "`release_lease` as soon as it is done — other callers are queued behind you until " +
                "then, and the lease expires by itself if you stop.",
            structured = buildJsonObject { put("lease", lease.id) },
        )
    }

    private fun releaseLease(args: JsonObject): ToolResult {
        val id = args.requiredString("lease")
        return if (leases.release(id)) {
            ToolResult("Released `$id`.")
        } else {
            ToolResult("Lease `$id` was not active — it had already expired or been released.")
        }
    }

    // ── Running steps ──────────────────────────────────────────────────────────────────────────

    private suspend fun JsonObject.runStep(
        step: WorkflowStep,
        expecting: String? = null,
    ): String = runSteps(listOf(step), expecting)

    /**
     * Runs [steps] against the session this call names, under its lease if it quoted one.
     *
     * A call that quotes no lease still queues behind one that is held, because it is simply another
     * caller of an already-serialised WebView. That is the desired shape: the lease holder is not
     * privileged, it is merely uninterrupted.
     */
    private suspend fun JsonObject.runSteps(
        steps: List<WorkflowStep>,
        expecting: String? = null,
    ): String {
        val session = sessions.resolve(string("session"))
        val lease = string("lease")?.let { leases.require(it, session.id) }
        val variables = execute(session, lease, steps)
        return expecting?.let { variables[it] ?: "" } ?: ""
    }

    private suspend fun execute(
        session: WebViewSession,
        lease: SessionLease?,
        steps: List<WorkflowStep>,
    ): Map<String, String> {
        // A leased call runs on the controller the lease holds the lock on, not on whatever is
        // registered under the session id now — those differ exactly when the WebView was rebuilt
        // mid-lease, and running on the new one would bypass the claim. An unleased call uses the
        // current controller, which is what it should.
        val controller = lease?.controller ?: session.controller
        val run: suspend () -> Map<String, String> = { runWorkflow(controller, steps) }
        return if (lease != null) lease.use(run) else run()
    }

    private suspend fun runWorkflow(
        controller: WebViewController,
        steps: List<WorkflowStep>,
    ): Map<String, String> {
        var variables: Map<String, String> = emptyMap()
        var failure: String? = null
        WorkflowEngine(controller, engineContext)
            .run(Workflow(id = "mcp", name = "tool call", steps = steps))
            .collect { event ->
                when (event) {
                    is WorkflowEvent.Completed -> variables = event.variables
                    is WorkflowEvent.Failed -> failure = event.message
                    is WorkflowEvent.StepStarted, is WorkflowEvent.StepCompleted -> Unit
                }
            }
        failure?.let { throw ToolFailure(it) }
        return variables
    }

    // ── Argument reading ───────────────────────────────────────────────────────────────────────

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotEmpty() }
            ?: throw ToolFailure("`$name` is required and must be a non-empty string.")

    private fun JsonObject.timeoutMs(): Long = long("timeout_ms")?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS

    private fun JsonObject.intInRange(
        name: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(min, max) ?: default

    /**
     * Reads the one element this call addresses.
     *
     * Three optional keys with exactly one set, rather than a tagged union, because a `oneOf` schema
     * is the kind models fill in wrongly. The check is explicit for the same reason: "you gave both
     * ref and css" is recoverable, whereas silently preferring one of them is how an agent ends up
     * acting on an element it did not choose.
     */
    private fun JsonObject.locator(
        prefix: String = "",
        inRow: Boolean = false,
    ): Locator {
        val ref = string("${prefix}ref")
        val cssSelector = string("${prefix}css")
        val xpathExpr = string("${prefix}xpath")
        val given = listOfNotNull(ref?.let { "ref" }, cssSelector?.let { "css" }, xpathExpr?.let { "xpath" })
        when {
            given.isEmpty() -> {
                throw ToolFailure(
                    "Give exactly one of `${prefix}ref`, `${prefix}css` or `${prefix}xpath`. If you " +
                        "have not looked at the page yet, call `snapshot` and use a `ref` from it " +
                        "rather than guessing a selector.",
                )
            }

            given.size > 1 -> {
                throw ToolFailure("Give exactly one of `${prefix}ref`, `${prefix}css`, `${prefix}xpath` — got ${given.joinToString(", ")}.")
            }
        }
        if (inRow && ref != null) {
            throw ToolFailure(
                "A column cannot be addressed by `ref`: a handle names one element in the whole " +
                    "document, so every row would report the same value. Use `css` or `xpath` " +
                    "relative to the row (an XPath column must start `.//`).",
            )
        }
        if (inRow && xpathExpr != null && !xpathExpr.startsWith(".")) {
            throw ToolFailure(
                "An XPath column must start with `.//` so it is evaluated inside the row. " +
                    "`$xpathExpr` searches from the document root, so every row would report the " +
                    "first row's value.",
            )
        }
        return when {
            ref != null -> handle(ref)
            cssSelector != null -> css(cssSelector)
            else -> xpath(xpathExpr!!)
        }
    }

    private fun JsonObject.extractSource(): WorkflowStep.Extract.Source =
        when (val from = string("from") ?: "text") {
            "text" -> {
                WorkflowStep.Extract.Source.Text
            }

            "attribute" -> {
                WorkflowStep.Extract.Source.Attribute(
                    string("name") ?: throw ToolFailure("`from: \"attribute\"` also needs `name`, the attribute to read."),
                )
            }

            "property" -> {
                WorkflowStep.Extract.Source.Property(
                    string("name") ?: throw ToolFailure("`from: \"property\"` also needs `name`, the property to read."),
                )
            }

            else -> {
                throw ToolFailure("`from` must be \"text\", \"attribute\" or \"property\" — got \"$from\".")
            }
        }

    private companion object {
        /** The variable every single-step tool reads its result out of. */
        const val OUT = "result"
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MIN_LEASE_TTL_MS = 1_000L
        const val MAX_LEASE_TTL_MS = 600_000L
        const val DEFAULT_MAX_NODES = 200
        const val MAX_MAX_NODES = 2_000
        const val DEFAULT_ROW_LIMIT = 20
        const val MAX_ROW_LIMIT = 200

        private val SESSION_DESCRIPTION =
            "Which WebView to act on. Omit it when there is only one — `list_sessions` says how many " +
                "there are."

        private val LEASE_DESCRIPTION =
            "The lease from `acquire_lease`, if this call is part of a sequence that must not be " +
                "interleaved with another caller's."

        val DEFINITIONS: List<ToolDefinition> =
            listOf(
                ToolDefinition(
                    name = "list_sessions",
                    title = "List WebView sessions",
                    description =
                        "Lists the WebViews this server can drive. Call it first if you do not know " +
                            "whether there is more than one; with a single session every other tool's " +
                            "`session` argument can be omitted.",
                    inputSchema = toolSchema { },
                ),
                ToolDefinition(
                    name = "snapshot",
                    title = "Snapshot the page",
                    description =
                        "Shows what is on the page: the interactive and text-bearing elements, each " +
                            "with a `ref` handle you pass to `click`, `type` and `extract`. This is how " +
                            "you look at a page — start here, and take a fresh one after anything that " +
                            "changes the page, because refs from a previous document stop resolving. " +
                            "Returns an indented outline, not HTML.",
                    inputSchema =
                        toolSchema {
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                            intProp(
                                "max_nodes",
                                "Cap on elements reported (default 200). The result goes into your " +
                                    "context, so raise it only when the outline says it was truncated.",
                            )
                        },
                ),
                ToolDefinition(
                    name = "navigate",
                    title = "Load a URL",
                    description =
                        "Loads a URL in the WebView and waits for the page to finish loading. " +
                            "Discards every `ref` from the previous page.",
                    inputSchema =
                        toolSchema(required = listOf("url")) {
                            stringProp("url", "Absolute URL to load.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                        },
                ),
                ToolDefinition(
                    name = "click",
                    title = "Click an element",
                    description =
                        "Clicks an element, waiting for it to appear first. Fails if it never does, " +
                            "rather than reporting a click that landed on nothing.",
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                            intProp("timeout_ms", "How long to wait for the element (default 10000).")
                        },
                ),
                ToolDefinition(
                    name = "type",
                    title = "Type into a field",
                    description =
                        "Replaces the value of an input or textarea with `text` and fires the input " +
                            "and change events a page listens for. Does not press Enter — click the " +
                            "form's button, which a `snapshot` will show you.",
                    inputSchema =
                        toolSchema(required = listOf("text")) {
                            locatorProps()
                            stringProp("text", "The text to put in the field, replacing what is there.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                            intProp("timeout_ms", "How long to wait for the field (default 10000).")
                        },
                ),
                ToolDefinition(
                    name = "wait_for",
                    title = "Wait for an element",
                    description =
                        "Waits until an element is present. Use it after an action that loads content " +
                            "asynchronously, before reading what it loaded.",
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                            intProp("timeout_ms", "How long to wait (default 10000).")
                        },
                ),
                ToolDefinition(
                    name = "extract",
                    title = "Read one value",
                    description =
                        "Reads the text, an attribute, or a live DOM property of a single element. " +
                            "For a field the user has typed in, use `from: \"property\"` with " +
                            "`name: \"value\"` — the `value` *attribute* holds the markup's original " +
                            "value and does not track typing. For a list of results use `extract_rows`.",
                    inputSchema =
                        toolSchema {
                            locatorProps()
                            stringProp("from", "\"text\" (default), \"attribute\" or \"property\".")
                            stringProp("name", "Which attribute or property, when `from` is one of those.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                        },
                ),
                ToolDefinition(
                    name = "extract_rows",
                    title = "Read a list or table",
                    description =
                        "Reads one record per matching row, with each column resolved inside that row, " +
                            "as a JSON array. Use this for search results and tables rather than many " +
                            "`extract` calls: a row missing a field yields an empty string in that one " +
                            "record instead of shifting every later record onto the wrong row.",
                    inputSchema =
                        toolSchema(required = listOf("columns")) {
                            locatorProps(prefix = "rows_")
                            putJsonObject("columns") {
                                put("type", "object")
                                put(
                                    "description",
                                    "Field name to locator, resolved within each row. XPath columns " +
                                        "must start `.` — use {\"xpath\": \".\"} to read the row " +
                                        "element itself, e.g. its own data attribute. A CSS column " +
                                        "can only reach *inside* the row, so there is no CSS " +
                                        "spelling of \"the row itself\".",
                                )
                                putJsonObject("additionalProperties") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        stringProp("css", "CSS selector, relative to the row.")
                                        stringProp("xpath", "XPath starting `.//`, relative to the row.")
                                        stringProp("from", "\"text\" (default), \"attribute\" or \"property\".")
                                        stringProp("name", "Which attribute or property, when `from` is one of those.")
                                    }
                                }
                            }
                            intProp("limit", "Maximum rows (default 20).")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                        },
                ),
                ToolDefinition(
                    name = "evaluate",
                    title = "Evaluate JavaScript",
                    description =
                        "Evaluates a JavaScript *expression* in the page and returns its value. The " +
                            "escape hatch for what the other tools cannot express; prefer them, since " +
                            "they resolve elements through the same guarded path. Wrap statements in an " +
                            "IIFE — a bare statement list will not parse.",
                    inputSchema =
                        toolSchema(required = listOf("script")) {
                            stringProp("script", "A JavaScript expression, e.g. `document.title`.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                        },
                ),
                ToolDefinition(
                    name = "send_message",
                    title = "Send a message to the page",
                    description =
                        "Delivers a string to the page as a `MessageEvent('vitre')` on `window`. " +
                            "Only useful for a page written to listen for it — the host app's own " +
                            "pages, not a third-party site.",
                    inputSchema =
                        toolSchema(required = listOf("message")) {
                            stringProp("message", "The payload, usually JSON the page knows how to read.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                        },
                ),
                ToolDefinition(
                    name = "await_message",
                    title = "Wait for a message from the page",
                    description =
                        "Waits for the page to post a `{id, type, payload}` message of the given type " +
                            "via `window.vitre.postMessage`, and returns it. Matches one the page " +
                            "already sent as well as one still to come, so it is safe to call after the " +
                            "action that triggers it.",
                    inputSchema =
                        toolSchema(required = listOf("type")) {
                            stringProp("type", "The `type` field of the message to wait for.")
                            stringProp("session", SESSION_DESCRIPTION)
                            stringProp("lease", LEASE_DESCRIPTION)
                            intProp("timeout_ms", "How long to wait (default 10000).")
                        },
                ),
                ToolDefinition(
                    name = "acquire_lease",
                    title = "Hold a session for a sequence",
                    description =
                        "Takes a WebView for several calls in a row, so no other caller — another " +
                            "agent, a workflow, a button in the app — can act on the page in between. " +
                            "Needed when a later call depends on what an earlier one left on screen, " +
                            "e.g. wait then read. Release it as soon as the sequence is done; it also " +
                            "expires on its own so a client that stops cannot wedge the page.",
                    inputSchema =
                        toolSchema {
                            stringProp("session", SESSION_DESCRIPTION)
                            intProp("ttl_ms", "How long to hold it before it expires (default 30000).")
                        },
                ),
                ToolDefinition(
                    name = "release_lease",
                    title = "Release a held session",
                    description = "Gives a leased WebView back, letting queued callers proceed.",
                    inputSchema =
                        toolSchema(required = listOf("lease")) {
                            stringProp("lease", "The lease id from `acquire_lease`.")
                        },
                ),
            )
    }
}
