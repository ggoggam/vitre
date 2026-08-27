package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.DEFAULT_LEASE_TTL_MS
import dev.ggoggam.vitre.agent.session.LeaseException
import dev.ggoggam.vitre.agent.session.NoSuchSessionException
import dev.ggoggam.vitre.agent.session.SessionLease
import dev.ggoggam.vitre.agent.session.SessionLeases
import dev.ggoggam.vitre.agent.session.WebViewSession
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.net.ExchangeOutcome
import dev.ggoggam.vitre.core.net.NetworkExchange
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.PageSnapshot
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEngine
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import dev.ggoggam.vitre.core.workflow.css as cssLocator
import dev.ggoggam.vitre.core.workflow.handle as handleLocator
import dev.ggoggam.vitre.core.workflow.xpath as xpathLocator

/**
 * An action failed in a way the caller's model can read and correct.
 *
 * Never a protocol or programming fault. "Timeout waiting for css `#buy`" is something an agent can
 * act on — take a snapshot, pick a real handle, try again — so it travels as a *result* the model
 * sees, which is what each adapter turns this into: an `isError` tool result over MCP, a
 * `ToolException.ValidationFailure` under Koog. Throwing it out to the host as an exception would
 * strip the explanation out of the model's reach and leave it retrying blind.
 */
class PageDriverException(
    override val message: String,
) : RuntimeException(message)

/** Which WebView an action runs against, and under whose claim. */
data class PageTarget(
    /** The session id, or null when there is exactly one — see [WebViewSessions.resolve]. */
    val session: String? = null,
    /** A lease id from [PageDriver.acquireLease], when this call belongs to an uninterruptible sequence. */
    val lease: String? = null,
) {
    companion object {
        /** The only session, no lease. What a single-WebView host's calls all look like. */
        val Default: PageTarget = PageTarget()
    }
}

/** A granted lease, with the TTL that was actually applied after clamping. */
data class LeaseGrant(
    val id: String,
    val sessionId: String,
    val ttlMs: Long,
)

/**
 * One captured request/response pair, cut down to what a model should be shown.
 *
 * A projection of [NetworkExchange] rather than the thing itself, because the difference between
 * "what was captured" and "what a tool result can afford" is real: a captured body may be a quarter
 * of a megabyte, which is a page's worth of context spent on one exchange. Doing that cut here, in
 * the shared layer, is what stops one adapter being more generous with a model's context than the
 * other.
 */
data class NetworkExchangeSummary(
    val method: String,
    val url: String,
    /** 0 when the request never got a response — see [error]. */
    val status: Int,
    val outcome: ExchangeOutcome,
    val contentType: String?,
    val durationMs: Long,
    val error: String?,
    /** The response body as text, already cut. Null when withheld or never captured — see [hasBody]. */
    val body: String?,
    /**
     * Whether a body was captured for this exchange at all.
     *
     * Separate from `body != null` because those two are different answers to different questions,
     * and collapsing them is how a caller decides a JSON API returned nothing when in fact it asked
     * not to be shown the JSON. `hasBody && body == null` means withheld — the read's cap was zero,
     * or the response was not textual.
     */
    val hasBody: Boolean,
    /**
     * Whether [body] is less than the whole response.
     *
     * True if any of three caps bit: the capture policy's `maxCapturedBodyBytes`, the log's own
     * retention cap, or this read's per-exchange cap. Which one is not worth distinguishing — the
     * consequence is identical, and it is the consequence a model must not be allowed to miss.
     * Reading a JSON body that was silently cut mid-object is how an agent concludes a shop stocks
     * four items when it listed forty.
     */
    val bodyTruncated: Boolean,
)

/** What one `read_network` call found, and what it was drawn from. */
data class NetworkRead(
    /** The matches, newest first. */
    val exchanges: List<NetworkExchangeSummary>,
    /** The `url_contains` filter that produced them, if any. */
    val filter: String?,
    /** How many matched before the limit was applied. */
    val matched: Int,
    /** How many exchanges the log held in total. */
    val retained: Int,
    /** The log's own bound, so a full log can be reported as "older ones have been dropped". */
    val capacity: Int,
)

/**
 * The page operations an agent gets, as Kotlin functions.
 *
 * ## Why this is not part of an adapter
 *
 * `WebViewTools` in `vitre-mcp` opens with the reason it builds [WorkflowStep]s instead of writing
 * its own JavaScript: the library already had two implementations of "talk to a WebView" — the
 * Android and iOS actuals — and they drifted until a boolean meant `true` on one and `"1"` on the
 * other, which nothing noticed for as long as it took to write the smoke test.
 *
 * A second *protocol* is the same hazard one level up. MCP and Koog both need "click, but fail if
 * the element was never there", both need a handle guarded against a stale document, both need
 * timeouts clamped so a model cannot park a WebView for a day. Written twice, those agree on the
 * day they are written and not much longer — and the divergence is invisible, because each
 * adapter's tests pass against its own copy.
 *
 * So the semantics live here, once, and an adapter is only ever the part that is genuinely its own:
 * how arguments arrive, and how a failure is spelled. Everything below the argument parsing —
 * which steps an action expands to, in what order, with what message when it fails — is shared.
 *
 * ## What it does not own
 *
 * Concurrency. Calls queue on the lock `vitre-core` already holds every WebView behind, and
 * reimplementing that ordering here would put the app's own UI and its workflows *outside* the
 * guarantees. What this class owns is the mapping from a stateless call to a stateful WebView:
 * resolving a session, honouring a lease, and running the steps.
 */
class PageDriver(
    /** The WebViews this driver can reach. The host registers them; the driver never creates one. */
    val sessions: WebViewSessions,
    /** Claims held across several calls. Shared with every adapter pointed at the same [sessions]. */
    val leases: SessionLeases,
    /**
     * Where step evaluation runs. `Dispatchers.Default` is right in production — selector strings and
     * JSON have no business on the WebView thread — and injectable for the same reason
     * [WorkflowEngine] makes it injectable: a test on a virtual-time scheduler needs the work to stay
     * on that scheduler, and one that escapes to a real thread pool turns every ordering assertion
     * into a race.
     */
    private val engineContext: CoroutineContext = Dispatchers.Default,
) {
    /**
     * Builds a driver with a lease registry of its own, for a host with only one adapter.
     *
     * [scope] is the host's, so that tearing it down releases every lease rather than leaving a
     * WebView held by something nobody is talking to any more.
     *
     * A host running more than one adapter over the same WebViews should point both at one driver
     * instead — `McpServer` publishes the one it built, so the Koog tools take `server.driver`. Two
     * registries corrupt nothing — a lease is ultimately a claim on the controller, and the second
     * registry's holder simply waits — but they issue ids the other has never heard of, so a
     * sequence started over MCP cannot be continued from Koog.
     */
    constructor(
        sessions: WebViewSessions,
        scope: CoroutineScope,
        engineContext: CoroutineContext = Dispatchers.Default,
    ) : this(sessions, SessionLeases(scope), engineContext)

    // ── Sessions and leases ────────────────────────────────────────────────────────────────────

    /** Every registered WebView, in registration order. */
    fun listSessions(): List<WebViewSession> = sessions.all()

    /**
     * Takes a WebView for several calls in a row.
     *
     * [ttlMs] is clamped like every other duration an agent supplies — by [SessionLeases.acquire],
     * so the bound holds for anything that reaches the registry rather than only for calls that
     * come through here. The whole point of the TTL is to defend the WebView against a client that
     * stops, and an unbounded one lets that same client wedge the page for days.
     *
     * @throws LeaseException if the WebView is held by somebody else, or has gone away.
     * @throws NoSuchSessionException if [sessionId] names nothing.
     */
    suspend fun acquireLease(
        sessionId: String? = null,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): LeaseGrant {
        val session = sessions.resolve(sessionId)
        val lease = leases.acquire(session, ttlMs = ttlMs)
        return LeaseGrant(id = lease.id, sessionId = session.id, ttlMs = lease.ttlMs)
    }

    /** @return false if the lease had already expired or been released. */
    fun releaseLease(id: String): Boolean = leases.release(id)

    /**
     * Whether [id] still names a live claim.
     *
     * For a caller holding a lease on somebody else's behalf — the Koog lease feature holds one for
     * the length of an agent run — quoting an expired id turns every later call into a hard failure,
     * where dropping it merely gives up the atomicity that has already been lost.
     */
    fun isLeaseActive(id: String): Boolean = leases.isActive(id)

    // ── Page actions ───────────────────────────────────────────────────────────────────────────

    /** What is on the page, as handles an agent can act on without knowing a single selector. */
    suspend fun snapshot(
        target: PageTarget = PageTarget.Default,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): PageSnapshot {
        val raw =
            target.runStep(
                WorkflowStep.Snapshot(into = OUT, maxNodes = maxNodes.coerceIn(1, MAX_MAX_NODES)),
                expecting = OUT,
            )
        return runCatching { PageSnapshot.decode(raw) }
            .getOrElse { throw PageDriverException("The page returned a snapshot this build cannot read: ${it.message}") }
    }

    /**
     * Loads [url] and waits for it to finish loading.
     *
     * @return the new document's title, which is the cheapest confirmation that the page is the one
     *   that was asked for rather than an interstitial.
     */
    suspend fun navigate(
        url: String,
        target: PageTarget = PageTarget.Default,
    ): String =
        target.runSteps(
            listOf(
                WorkflowStep.Navigate(url),
                WorkflowStep.EvaluateJs("document.title", OUT),
            ),
            expecting = OUT,
        )

    /**
     * Clicks the element [locator] names, waiting for it to appear first.
     *
     * The wait is not only for slow pages. A click on a locator that matches nothing is a no-op that
     * reports success — the generated expression is `…?.click()` — so without it an agent is told it
     * pressed a button that was never there and carries on from a state that does not exist. The
     * wait turns that into "Timeout waiting for css `#buy`".
     */
    suspend fun click(
        locator: Locator,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: PageTarget = PageTarget.Default,
    ) {
        target.runSteps(
            listOf(
                WorkflowStep.WaitFor(locator, timeoutMs.clampTimeout()),
                WorkflowStep.Click(locator),
            ),
        )
    }

    /** Replaces a field's value with [text], firing the input and change events a page listens for. */
    suspend fun input(
        locator: Locator,
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: PageTarget = PageTarget.Default,
    ) {
        target.runSteps(
            listOf(
                WorkflowStep.WaitFor(locator, timeoutMs.clampTimeout()),
                WorkflowStep.Input(locator, text),
            ),
        )
    }

    /** Waits until [locator] matches something. */
    suspend fun waitFor(
        locator: Locator,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: PageTarget = PageTarget.Default,
    ) {
        target.runStep(WorkflowStep.WaitFor(locator, timeoutMs.clampTimeout()))
    }

    /**
     * Reads the text, an attribute, or a live DOM property of the first element [locator] matches.
     *
     * @throws PageDriverException when nothing matched, or the match had no value to read. An empty
     *   string returned as a success is indistinguishable from a real empty value, and an agent that
     *   cannot tell those apart carries on from a page it has misread.
     */
    suspend fun extract(
        locator: Locator,
        from: WorkflowStep.Extract.Source = WorkflowStep.Extract.Source.Text,
        target: PageTarget = PageTarget.Default,
    ): String {
        val value = target.runStep(WorkflowStep.Extract(locator, OUT, from), expecting = OUT)
        if (value.isEmpty()) {
            throw PageDriverException(
                "${locator.describe()} matched nothing, or matched an element with no value to read. " +
                    "Take a `snapshot` to see what is actually there.",
            )
        }
        return value
    }

    /**
     * Reads one record per row, each column resolved *within* that row, as a JSON array.
     *
     * The locator rules are enforced here rather than by each adapter because all of them are
     * failure modes that produce plausible-looking wrong answers rather than errors: a handle names
     * one element in the whole document, so a handle as [rows] yields a one-record table and a
     * handle as a column repeats one value down every record; and an XPath column that does not
     * start `.` searches from the document root, so every record reports the first row's value.
     */
    suspend fun extractRows(
        rows: Locator,
        columns: Map<String, WorkflowStep.ExtractRows.Column>,
        limit: Int = DEFAULT_ROW_LIMIT,
        target: PageTarget = PageTarget.Default,
    ): String {
        if (columns.isEmpty()) {
            throw PageDriverException(
                "At least one column is required: each names a field and a locator resolved within " +
                    "one row.",
            )
        }
        if (rows is Locator.Handle) {
            // The quiet one. A handle resolves to exactly one element, so the row set is a single
            // row and the model gets a one-record answer for a forty-row table, with no error to
            // tell it apart from a page that really had one result.
            throw PageDriverException(
                "The rows cannot be addressed by a handle: a handle names one element, so the result " +
                    "would be a single record however many rows the page has. Use a CSS selector or " +
                    "an XPath that matches every row.",
            )
        }
        columns.forEach { (name, column) -> validateColumn(name, column.locator) }
        return target.runStep(
            WorkflowStep.ExtractRows(rows, columns, OUT, limit.coerceIn(1, MAX_ROW_LIMIT)),
            expecting = OUT,
        )
    }

    /** Evaluates a JavaScript *expression* in the page and returns its JSON encoding. */
    suspend fun evaluate(
        script: String,
        target: PageTarget = PageTarget.Default,
    ): String = target.runStep(WorkflowStep.EvaluateJs(script, OUT), expecting = OUT)

    /** Delivers [message] to the page as a `MessageEvent('vitre')`. */
    suspend fun postMessage(
        message: String,
        target: PageTarget = PageTarget.Default,
    ) {
        target.runStep(WorkflowStep.PostMessage(message))
    }

    /**
     * Waits for the page to post a `{id, type, payload}` message of [type] via `window.vitre.postMessage`.
     *
     * Matches one the page already sent as well as one still to come, so it is safe to call *after*
     * the action that triggers it — which is the case that silently loses messages elsewhere.
     */
    suspend fun awaitMessage(
        type: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        target: PageTarget = PageTarget.Default,
    ): String = target.runStep(WorkflowStep.AwaitMessage(type, OUT, timeoutMs.clampTimeout()), expecting = OUT)

    /**
     * Reads the HTTP traffic this session captured, newest first.
     *
     * The point of it is that a page's own API is usually a better source than its DOM: a shop
     * rendering results from `GET /api/search` hands over typed JSON — price a number, stock a
     * boolean — where the rendered page offers `$1,299.00` split across three spans and a tick
     * glyph. The capture machinery has been in `vitre-core` since the lane pool was built; until
     * this method nothing let an agent at it.
     *
     * ### Why it neither suspends nor takes a lease
     *
     * It reads a buffer. It never touches the WebView, so there is nothing to order it against and
     * nothing to hold: taking the session's lock would queue this call behind a sequence it cannot
     * affect, and validating a lease id would turn a stale one into a failure for a read that never
     * needed the claim. This is the one page tool with no `lease` argument, and that is why.
     *
     * ### Coverage is the platform's business, not this method's
     *
     * What reaches the log at all differs by platform and the gap is structural — Android and the
     * desktop watch from below the page and see every request, iOS asks the page to report on
     * itself and therefore sees only its script's own `fetch`/`XMLHttpRequest` calls. A model that
     * does not know this reads an empty result as "the request did not happen". [PageToolDocs] and
     * [PageToolReplies] both say so out loud for exactly that reason.
     *
     * @param maxBodyChars per-exchange cap on response body text. 0 omits bodies entirely, which is
     *   how a caller lists what a page fetched without paying for any of it.
     * @throws NoSuchSessionException if [session] names nothing.
     * @throws PageDriverException if the host registered this session without a
     *   [dev.ggoggam.vitre.core.net.NetworkLog]. Deliberately a failure rather than an empty result:
     *   "no capture is wired here" and "nothing was captured" call for completely different next
     *   moves, and a model told the second when the first is true will keep asking.
     */
    fun readNetwork(
        urlContains: String? = null,
        limit: Int = DEFAULT_NETWORK_LIMIT,
        maxBodyChars: Int = DEFAULT_NETWORK_BODY_CHARS,
        session: String? = null,
    ): NetworkRead {
        val resolved = sessions.resolve(session)
        val log =
            resolved.network ?: throw PageDriverException(
                "Session `${resolved.id}` has no network capture wired, so there is no traffic to " +
                    "read — this is how the host set the WebView up, not something that will change " +
                    "if you try again. Read the page itself with `snapshot`, `extract` and " +
                    "`extract_rows` instead.",
            )
        val cap = maxBodyChars.coerceIn(0, MAX_NETWORK_BODY_CHARS)
        val query = log.query(urlContains?.takeIf { it.isNotBlank() }, limit.coerceIn(1, MAX_NETWORK_LIMIT))
        return NetworkRead(
            exchanges = query.exchanges.map { it.summarise(cap) },
            filter = urlContains?.takeIf { it.isNotBlank() },
            matched = query.matched,
            retained = query.retained,
            capacity = log.maxExchanges,
        )
    }

    /**
     * Cuts one exchange to [maxBodyChars] of body, keeping the truncation flag honest.
     *
     * The `||` is the load-bearing part: an exchange that arrived already truncated stays truncated
     * even when this read's cap did not bite, because the body is still not the whole response and
     * the caller has to know that whichever cap did it.
     */
    private fun NetworkExchange.summarise(maxBodyChars: Int): NetworkExchangeSummary {
        val shown = body?.takeIf { maxBodyChars > 0 }
        return NetworkExchangeSummary(
            method = method,
            url = url,
            status = status,
            outcome = outcome,
            contentType = contentType,
            durationMs = durationMs,
            error = error,
            body = shown?.take(maxBodyChars),
            hasBody = body != null,
            bodyTruncated = shown != null && (bodyTruncated || shown.length > maxBodyChars),
        )
    }

    // ── Running steps ──────────────────────────────────────────────────────────────────────────

    private suspend fun PageTarget.runStep(
        step: WorkflowStep,
        expecting: String? = null,
    ): String = runSteps(listOf(step), expecting)

    /**
     * Runs [steps] against the session this target names, under its lease if it quoted one.
     *
     * A call that quotes no lease still queues behind one that is held, because it is simply another
     * caller of an already-serialised WebView. That is the desired shape: the lease holder is not
     * privileged, it is merely uninterrupted.
     */
    private suspend fun PageTarget.runSteps(
        steps: List<WorkflowStep>,
        expecting: String? = null,
    ): String {
        val resolved = sessions.resolve(session)
        val held = lease?.let { leases.require(it, resolved.id) }
        val variables = execute(resolved, held, steps)
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
            .run(Workflow(id = "agent", name = "page action", steps = steps))
            .collect { event ->
                when (event) {
                    is WorkflowEvent.Completed -> variables = event.variables
                    is WorkflowEvent.Failed -> failure = event.message
                    is WorkflowEvent.StepStarted, is WorkflowEvent.StepCompleted -> Unit
                }
            }
        failure?.let { throw PageDriverException(it) }
        return variables
    }

    private fun Long.clampTimeout(): Long = coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    private fun validateColumn(
        name: String,
        locator: Locator,
    ) {
        when (locator) {
            is Locator.Handle -> {
                throw PageDriverException(
                    "Column `$name` cannot be addressed by a handle: a handle names one element in the " +
                        "whole document, so every row would report the same value. Use a CSS selector or " +
                        "an XPath relative to the row (an XPath column must start `.//`).",
                )
            }

            is Locator.XPath -> {
                if (!locator.expression.startsWith(".")) {
                    throw PageDriverException(
                        "Column `$name`: an XPath column must start with `.//` so it is evaluated inside " +
                            "the row. `${locator.expression}` searches from the document root, so every " +
                            "row would report the first row's value.",
                    )
                }
            }

            is Locator.Css -> {
                Unit
            }
        }
    }

    companion object {
        /** The variable every single-step action reads its result out of. */
        private const val OUT = "result"

        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
        const val MIN_TIMEOUT_MS: Long = 100L
        const val MAX_TIMEOUT_MS: Long = 120_000L
        const val MIN_LEASE_TTL_MS: Long = dev.ggoggam.vitre.agent.session.MIN_LEASE_TTL_MS
        const val MAX_LEASE_TTL_MS: Long = dev.ggoggam.vitre.agent.session.MAX_LEASE_TTL_MS
        const val DEFAULT_MAX_NODES: Int = 200
        const val MAX_MAX_NODES: Int = 2_000
        const val DEFAULT_ROW_LIMIT: Int = 20
        const val MAX_ROW_LIMIT: Int = 200

        /**
         * How many exchanges `read_network` returns by default.
         *
         * Lower than [DEFAULT_ROW_LIMIT] on purpose: a row is a handful of fields and an exchange
         * can carry a whole JSON response, so ten of these cost more context than twenty of those.
         */
        const val DEFAULT_NETWORK_LIMIT: Int = 10
        const val MAX_NETWORK_LIMIT: Int = 100

        /**
         * How much of one response body `read_network` shows by default.
         *
         * Enough for a search API's answer, short of a rendered HTML document. The cap is on what a
         * *model* is shown and is unrelated to `InterceptionPolicy.maxCapturedBodyBytes`, which
         * governs what is captured in the first place — the capture cap can afford a quarter of a
         * megabyte, a tool result cannot.
         */
        const val DEFAULT_NETWORK_BODY_CHARS: Int = 2_000
        const val MAX_NETWORK_BODY_CHARS: Int = 20_000
    }
}

/**
 * Resolves the one element an adapter's three optional arguments name.
 *
 * Three keys with exactly one set, rather than a tagged union, because a `oneOf` schema is the kind
 * models fill in wrongly. The check is explicit for the same reason: "you gave both ref and css" is
 * recoverable, whereas silently preferring one of them is how an agent ends up acting on an element
 * it did not choose.
 *
 * [prefix] names the arguments in the failure message, for adapters that carry more than one locator
 * per call — `rows_css` and friends.
 *
 * [allowRef] is false where a handle is meaningless rather than merely discouraged — the row set and
 * the columns of `extract_rows`, both of which name *many* elements. The message has to know, because
 * an error that tells a model to pass an argument the tool does not accept is advice it can only
 * follow into a second failure.
 *
 * The locator factories are reached through aliases here because this function's parameters are
 * named after the arguments an adapter receives — `ref`, `css`, `xpath` — which would otherwise
 * shadow them.
 *
 * @throws PageDriverException if none or more than one is set.
 */
fun locatorFrom(
    ref: String? = null,
    css: String? = null,
    xpath: String? = null,
    prefix: String = "",
    allowRef: Boolean = true,
): Locator {
    val offered = if (allowRef) listOf("ref", "css", "xpath") else listOf("css", "xpath")
    val choices = offered.joinToString(", ") { "`$prefix$it`" }
    val given =
        listOfNotNull(
            ref?.let { "ref" },
            css?.let { "css" },
            xpath?.let { "xpath" },
        ).filter { allowRef || it != "ref" }
    when {
        !allowRef && ref != null -> throw PageDriverException(
            "`${prefix}ref` is not accepted here: a handle names one element, and this argument has " +
                "to match every one of them. Give $choices instead.",
        )

        given.isEmpty() -> throw PageDriverException(
            "Give exactly one of $choices." +
                if (allowRef) {
                    " If you have not looked at the page yet, take a `snapshot` and use a `ref` from " +
                        "it rather than guessing a selector."
                } else {
                    " If you have not looked at the page yet, take a `snapshot` first rather than " +
                        "guessing a selector."
                },
        )

        given.size > 1 -> throw PageDriverException(
            "Give exactly one of $choices — got ${given.joinToString(", ") { "`$prefix$it`" }}.",
        )
    }
    return when {
        ref != null -> handleLocator(ref)
        css != null -> cssLocator(css)
        else -> xpathLocator(xpath!!)
    }
}

/**
 * Resolves what to read off an element from an adapter's `from`/`name` pair.
 *
 * @throws PageDriverException if [from] is not one of the three, or names an attribute or property
 *   without saying which.
 */
fun extractSourceFrom(
    from: String?,
    name: String?,
): WorkflowStep.Extract.Source =
    when (val source = from ?: "text") {
        "text" -> {
            WorkflowStep.Extract.Source.Text
        }

        "attribute" -> {
            WorkflowStep.Extract.Source.Attribute(
                name ?: throw PageDriverException("`from: \"attribute\"` also needs `name`, the attribute to read."),
            )
        }

        "property" -> {
            WorkflowStep.Extract.Source.Property(
                name ?: throw PageDriverException("`from: \"property\"` also needs `name`, the property to read."),
            )
        }

        else -> {
            throw PageDriverException("`from` must be \"text\", \"attribute\" or \"property\" — got \"$source\".")
        }
    }
