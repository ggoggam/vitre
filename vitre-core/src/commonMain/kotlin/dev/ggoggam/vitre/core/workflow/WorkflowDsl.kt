package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.BridgeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Confines the step functions to the scope that owns them.
 *
 * Without it, [RowScope] would inherit [WorkflowScope] as an outer receiver, and `navigate(…)`
 * would compile inside a column block — appending a step to the enclosing workflow from a position
 * that reads like it is describing a field. The marker turns that into a compile error.
 */
@DslMarker
annotation class WorkflowDsl

/**
 * Builds a [Workflow] out of a block of step calls.
 *
 * Sugar over the [WorkflowStep] constructors and nothing more: the result is the same data that
 * `Workflow(id, name, listOf(…))` produces, and the constructors remain the primary way in. They
 * have to — the MCP server assembles a workflow out of steps that arrived as JSON from an agent,
 * and no lambda helps there.
 *
 * **The block assembles a list of steps; it does not execute one.** A curly-brace block reads like
 * a program, and this one is not: it runs once, up front, to produce a list the engine walks later.
 * Ordinary Kotlin control flow works here and runs at *build* time, so `if (staging) navigate(…)`
 * decides what the workflow **contains** before the workflow ever runs. Nothing in the block can see
 * the page, and nothing can read a variable an earlier step extracted — those exist only while the
 * engine is running, long after this returns.
 *
 * When the decision belongs to the *run* rather than to the build, [WorkflowScope.runIf] is the one
 * to reach for: it appends a [WorkflowStep.If] the engine evaluates against the page. The two sit
 * one line apart and look nothing alike on purpose —
 *
 * ```
 * if (staging) navigate(stagingUrl)             // decided now; the workflow may not contain it
 * runIf(exists("#cookie-banner")) {             // decided later, against the page
 *     click("#cookie-banner .accept")
 * }
 * ```
 *
 * Every function is named for the step it appends, so a `Failed` event naming `Input(…)` points at
 * the `input(…)` line that produced it.
 *
 * ```
 * val workflow = workflow("hn-top-story", "Hacker News top story") {
 *     navigate("https://news.ycombinator.com/")
 *     waitFor(".titleline > a", timeoutMs = 15_000)
 *     extract(".titleline > a", into = "headline")
 *     extract(".titleline > a", into = "url", from = Source.Attribute("href"))
 * }
 * ```
 */
fun workflow(
    id: String,
    name: String,
    build: WorkflowScope.() -> Unit,
): Workflow = Workflow(id = id, name = name, steps = WorkflowScope().apply(build).build())

/**
 * The receiver of a [workflow] block — one function per [WorkflowStep], in the order they appear
 * there.
 *
 * Each locator-taking step comes in two forms, mirroring the constructors: one taking a [Locator],
 * and one taking a bare [String] that means CSS.
 */
@WorkflowDsl
class WorkflowScope internal constructor() {
    private val collected = mutableListOf<WorkflowStep>()

    internal fun build(): List<WorkflowStep> = collected.toList()

    /**
     * Appends a step the DSL has no function for.
     *
     * The escape hatch, and the reason this is sugar rather than a second API to keep in sync: a
     * step held in a variable, or one added since, goes in through here without waiting for a
     * function to be written for it.
     */
    fun step(step: WorkflowStep) {
        collected += step
    }

    /**
     * Appends a [WorkflowStep.If]: run [then] when [condition] holds at that point in the run, and
     * [otherwise] when it does not.
     *
     * Named `runIf` rather than `if` — which Kotlin would not allow anyway — so that the line cannot
     * be misread as the build-time `if` described on [workflow]. This one is part of the workflow,
     * sees the page, and can read variables earlier steps extracted.
     *
     * ```
     * extract("#status", into = "status")
     * runIf(variableEquals("status", "expired")) {
     *     click("#refresh")
     *     waitFor("#status")
     * }
     * ```
     *
     * The else branch is a named argument rather than a second trailing lambda, because Kotlin gives
     * the trailing position to exactly one block and the *then* branch has the better claim on it:
     *
     * ```
     * runIf(exists("#login-form"), otherwise = { click("#continue") }) {
     *     input("#password", secret)
     *     click("#submit")
     * }
     * ```
     */
    fun runIf(
        condition: Condition,
        otherwise: (WorkflowScope.() -> Unit)? = null,
        then: WorkflowScope.() -> Unit,
    ) = step(
        WorkflowStep.If(
            condition = condition,
            then = WorkflowScope().apply(then).build(),
            otherwise = otherwise?.let { WorkflowScope().apply(it).build() }.orEmpty(),
        ),
    )

    /**
     * Appends a [WorkflowStep.ForEach]: run [body] once per element of the JSON array in [over],
     * with the element bound as [item], and collect what each run produced into [into].
     *
     * ```
     * extractRows(rows = "li.result", into = "results") {
     *     column("title", "h3")
     *     column("url", "a", from = Source.Property("href"))
     * }
     * forEach(over = "results", item = "product", into = "details") {
     *     navigate(template("{product.url}"))
     *     waitFor("#price")
     *     extract("#price", into = "price")
     * }
     * ```
     *
     * Runs against the page, not at build time, which is what lets it iterate over something an
     * earlier step extracted — the same distinction [runIf] draws against a Kotlin `if`, and the
     * reason a Kotlin `for` over a fixed list is *not* what this is: that would append the body's
     * steps N times, and could not visit pages it does not know yet. See [WorkflowStep.ForEach]
     * for what the body sees and what a fan-out costs.
     */
    fun forEach(
        over: String,
        item: String,
        into: String,
        limit: Int = 20,
        body: WorkflowScope.() -> Unit,
    ) = step(
        WorkflowStep.ForEach(
            over = over,
            item = item,
            into = into,
            body = WorkflowScope().apply(body).build(),
            limit = limit,
        ),
    )

    fun navigate(url: String) = step(WorkflowStep.Navigate(url))

    /** [navigate], to an address assembled from variables — `navigate(template("…/{sku}"))`. */
    fun navigate(url: Template) = step(WorkflowStep.Navigate(url))

    fun loadHtml(
        html: String,
        baseUrl: String? = null,
    ) = step(WorkflowStep.LoadHtml(html, baseUrl))

    fun waitFor(
        locator: Locator,
        timeoutMs: Long = 10_000L,
    ) = step(WorkflowStep.WaitFor(locator, timeoutMs))

    fun waitFor(
        selector: String,
        timeoutMs: Long = 10_000L,
    ) = step(WorkflowStep.WaitFor(selector, timeoutMs))

    fun click(locator: Locator) = step(WorkflowStep.Click(locator))

    fun click(selector: String) = step(WorkflowStep.Click(selector))

    fun input(
        locator: Locator,
        text: String,
    ) = step(WorkflowStep.Input(locator, text))

    fun input(
        selector: String,
        text: String,
    ) = step(WorkflowStep.Input(selector, text))

    /** [input], with text assembled from variables — `input("#q", template("{brand} {model}"))`. */
    fun input(
        locator: Locator,
        text: Template,
    ) = step(WorkflowStep.Input(locator, text))

    fun input(
        selector: String,
        text: Template,
    ) = step(WorkflowStep.Input(selector, text))

    fun extract(
        locator: Locator,
        into: String,
        from: WorkflowStep.Extract.Source = WorkflowStep.Extract.Source.Text,
    ) = step(WorkflowStep.Extract(locator, into, from))

    fun extract(
        selector: String,
        into: String,
        from: WorkflowStep.Extract.Source = WorkflowStep.Extract.Source.Text,
    ) = step(WorkflowStep.Extract(selector, into, from))

    /**
     * Extracts one record per element [rows] matches, with the fields declared in [columns].
     *
     * The block replaces the `linkedMapOf` the constructor takes, and the difference is not only
     * shape. Column order is the record's field order, so the map has to preserve insertion order;
     * `mapOf` also does today, which is exactly what makes reaching for it out of habit a quiet
     * mistake rather than a loud one. Here the ordering is the builder's job and cannot be got
     * wrong at the call site.
     *
     * Column locators resolve against the row, so an XPath column must begin `.` — see
     * [WorkflowStep.ExtractRows] for why, and for what `limit` is protecting.
     */
    fun extractRows(
        rows: Locator,
        into: String,
        limit: Int = 20,
        columns: RowScope.() -> Unit,
    ) = step(
        WorkflowStep.ExtractRows(
            rows = rows,
            columns = RowScope().apply(columns).build(),
            into = into,
            limit = limit,
        ),
    )

    fun snapshot(
        into: String,
        maxNodes: Int = 200,
        nameLimit: Int = 120,
    ) = step(WorkflowStep.Snapshot(into, maxNodes, nameLimit))

    fun evaluateJs(
        script: String,
        into: String? = null,
    ) = step(WorkflowStep.EvaluateJs(script, into))

    fun awaitMessage(
        type: String,
        into: String,
        timeoutMs: Long = 10_000L,
    ) = step(WorkflowStep.AwaitMessage(type, into, timeoutMs))

    fun postMessage(message: String) = step(WorkflowStep.PostMessage(message))

    /**
     * [postMessage], with the envelope assembled and [payload] serialized from [T].
     *
     * The same step — a `PostMessage` holding a JSON string — with the string produced by
     * `kotlinx.serialization` here rather than typed out by hand at the call site. That is as far as
     * typing reaches on this side, and the asymmetry with `bridge.request` is worth being plain
     * about: there is no `-> R` here because there is no *here* to return it to. This block runs
     * once, up front, to build a list; the message is sent later, by the engine, and its reply lands
     * in a variable. Await it with [awaitMessage] and decode that variable with
     * [WorkflowEvent.Completed.decodePayload].
     *
     * [id] is required rather than generated, for the same reason the raw form makes you write one:
     * a workflow is a value, and one that mints a fresh id every time it is built is not equal to
     * itself. Correlating a reply by id is a programmatic-host concern — see `bridge.request`.
     */
    inline fun <reified T> postMessage(
        type: String,
        payload: T,
        id: String,
    ) = postMessage(
        WorkflowJson.encodeToString(
            BridgeMessage.serializer(),
            BridgeMessage(id = id, type = type, payload = WorkflowJson.encodeToJsonElement(payload)),
        ),
    )
}

/** The receiver of an [WorkflowScope.extractRows] block: the fields of one record. */
@WorkflowDsl
class RowScope internal constructor() {
    private val collected = linkedMapOf<String, WorkflowStep.ExtractRows.Column>()

    internal fun build(): Map<String, WorkflowStep.ExtractRows.Column> = LinkedHashMap(collected)

    /**
     * Declares the field [name], read from the element [locator] matches *within* each row.
     *
     * Declaring the same name twice is an error rather than a silent overwrite. A map literal
     * quietly keeps the last one, which on a wide table is a typo that costs a column and says
     * nothing about it.
     */
    fun column(
        name: String,
        locator: Locator,
        from: WorkflowStep.Extract.Source = WorkflowStep.Extract.Source.Text,
    ) {
        require(name !in collected) { "Duplicate column `$name`" }
        collected[name] = WorkflowStep.ExtractRows.Column(locator, from)
    }
}

/**
 * The codec the typed workflow helpers use.
 *
 * Public because [WorkflowScope.postMessage] and [WorkflowEvent.Completed.decode] are inline, and a
 * caller's compiled code reaches it. `ignoreUnknownKeys` matches the bridge's own reader: a payload
 * class is a view of what the page sent, not an exhaustive description of it.
 */
val WorkflowJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Decodes the variable [name] into [R].
 *
 * The workflow answer to "where is my `R`". A step cannot hand a typed value back to the block that
 * declared it — the block finished building before the engine started running — so the typing moves
 * to the far end, where the values actually are. Variables hold whatever their step produced, as a
 * string: `ExtractRows` leaves a JSON array, so `decode<List<Product>>("results")`; `EvaluateJs`
 * leaves whatever the expression encoded to.
 *
 * @throws IllegalArgumentException if no variable [name] was set — a typo in a variable name is
 *   otherwise a `null` that travels a long way from its cause.
 * @throws kotlinx.serialization.SerializationException if the value is not an [R].
 */
inline fun <reified R> WorkflowEvent.Completed.decode(name: String): R = WorkflowJson.decodeFromString(requireVariable(name))

/**
 * Decodes the *payload* of a bridge message a [WorkflowStep.AwaitMessage] stored in [name].
 *
 * That step stores the whole envelope, because the envelope is what arrived and discarding its
 * `type` or `replyTo` at the point of capture would be lossy. Callers almost always want the
 * payload, so this is the short way to say so; [decode] with a [BridgeMessage] gets the rest.
 *
 * @throws IllegalArgumentException if no variable [name] was set.
 * @throws kotlinx.serialization.SerializationException if the value is not a bridge envelope, or
 *   its payload is not an [R].
 */
inline fun <reified R> WorkflowEvent.Completed.decodePayload(name: String): R =
    WorkflowJson.decodeFromJsonElement(
        WorkflowJson.decodeFromString(BridgeMessage.serializer(), requireVariable(name)).payload,
    )

/** Shared by the inline decoders, so the "no such variable" failure names the variable. */
@PublishedApi
internal fun WorkflowEvent.Completed.requireVariable(name: String): String =
    requireNotNull(variables[name]) {
        "No variable `$name`. The workflow set: ${variables.keys.sorted().joinToString(", ").ifEmpty { "nothing" }}"
    }
