package dev.ggoggam.vitre.core.workflow

sealed class WorkflowStep {
    /**
     * Loads [url], which may be assembled from variables — see [Template].
     *
     * The [String] constructor is the one nearly every workflow wants and it means a *literal* URL,
     * with no interpolation of any kind. Reach for [template] only when the address depends on
     * something an earlier step extracted.
     */
    data class Navigate(
        val url: Template,
    ) : WorkflowStep() {
        constructor(url: String) : this(Template.Literal(url))
    }

    /**
     * Loads [html] directly, without a network round trip.
     *
     * [baseUrl] is the origin the document is treated as coming from, and it is not cosmetic:
     * relative URLs resolve against it, and on Android it is what the bridge's allowed-origin rule
     * matches. Leaving it null gives the document an opaque origin, which is fine for a
     * self-contained fixture and wrong for anything that fetches.
     */
    data class LoadHtml(
        val html: String,
        val baseUrl: String? = null,
    ) : WorkflowStep()

    data class WaitFor(
        val locator: Locator,
        val timeoutMs: Long = 10_000L,
    ) : WorkflowStep() {
        constructor(selector: String, timeoutMs: Long = 10_000L) : this(css(selector), timeoutMs)
    }

    data class Click(
        val locator: Locator,
    ) : WorkflowStep() {
        constructor(selector: String) : this(css(selector))
    }

    /**
     * Types [text] into the first element [locator] matches, which may be assembled from variables
     * — see [Template].
     *
     * Assigns the DOM *property* `el.value` and then fires `input` and `change`, which is what a
     * framework-backed field listens for. See [Extract] for why reading it back needs
     * [Extract.Source.Property] rather than an attribute.
     */
    data class Input(
        val locator: Locator,
        val text: Template,
    ) : WorkflowStep() {
        constructor(locator: Locator, text: String) : this(locator, Template.Literal(text))

        constructor(selector: String, text: String) : this(css(selector), Template.Literal(text))

        constructor(selector: String, text: Template) : this(css(selector), text)
    }

    /**
     * Reads something out of the first element [locator] matches, into the variable [into].
     *
     * What to read is [from], and the distinction it draws is not pedantry. [Input] assigns the DOM
     * *property* `el.value`; an `Extract` reading the `value` *attribute* of the same element gets
     * `""`, because assigning a property does not write through to the attribute it was initialised
     * from. Modelling the two as one nullable `attribute` field made that mistake the easy one.
     */
    data class Extract(
        val locator: Locator,
        val into: String,
        val from: Source = Source.Text,
    ) : WorkflowStep() {
        constructor(selector: String, into: String, from: Source = Source.Text) :
            this(css(selector), into, from)

        sealed interface Source {
            /** `textContent`, trimmed. */
            data object Text : Source

            /** `getAttribute(name)` — the markup's value, which does not track user input. */
            data class Attribute(
                val name: String,
            ) : Source

            /** `el[name]` — the live DOM property, which does. Use this for `value` and `checked`. */
            data class Property(
                val name: String,
            ) : Source
        }
    }

    /**
     * Extracts a *table* — one record per element [rows] matches, with [columns] read relative to
     * each — and stores it in [into] as a JSON array of objects.
     *
     * This is what a list of search results needs and what repeated [Extract] cannot express.
     * [Extract] takes the first match, so pulling ten results with three fields each would mean
     * thirty selectors that each have to encode their own row index, and any row the page omits
     * silently shifts every later one onto the wrong record. Here the row is the unit: fields are
     * resolved *within* it, so a missing price yields an empty string in that record and nothing
     * else moves.
     *
     * Column locators are resolved against the row element, so an XPath column must begin `.`.
     * `xpath(".")` reads something off the row element itself — its `data-asin`, say.
     *
     * There is no CSS equivalent, and `css(":scope")` is not one: a column compiles to
     * `row.querySelector(…)`, and `querySelector` only ever searches *descendants*, so `:scope`
     * matches nothing and the field comes back empty rather than failing.
     *
     * [limit] caps the number of rows. It exists because the natural next thing to do with the
     * result is hand it to a model, and a full page of results is a lot of tokens.
     *
     * **The order of keys within each record is not portable.** iOS emits them in the order given
     * here; Android alphabetises them, because Chromium serialises a JS object through a key-sorted
     * map on the way out of `evaluateJavascript`. Rows keep their document order on both. JSON
     * objects are unordered by definition so nothing is lost — but read fields by name, and do not
     * assert on the serialised string in a cross-platform test.
     */
    data class ExtractRows(
        val rows: Locator,
        val columns: Map<String, Column>,
        val into: String,
        val limit: Int = 20,
    ) : WorkflowStep() {
        /** One field of a record: where to find it within the row, and what to read. */
        data class Column(
            val locator: Locator,
            val from: Extract.Source = Extract.Source.Text,
        )
    }

    /**
     * Captures what is on the page into [into], as a JSON [PageSnapshot].
     *
     * The step an agent reaches for first, and the only one that does not require knowing the page
     * in advance. Every other element-addressing step needs a selector; this one *produces* the
     * addresses, as handles the agent can pass straight back — see [Locator.Handle].
     *
     * [maxNodes] is a budget, not a hint. The natural consumer of a snapshot is a model's context
     * window, and a page with a thousand list items would fill it with list items. When the walk
     * stops early it says so ([PageSnapshot.truncated]) rather than pretending the page ended.
     *
     * [nameLimit] truncates each element's accessible name. Long enough to tell two buttons apart is
     * the whole requirement; a paragraph of it is the same cost with none of the benefit.
     */
    data class Snapshot(
        val into: String,
        val maxNodes: Int = 200,
        val nameLimit: Int = 120,
    ) : WorkflowStep()

    /**
     * Evaluates [script] and optionally stores its result.
     *
     * Must be an expression. Wrap statements in an IIFE — iOS evaluates the script inside a
     * `return (…)` so that both platforms encode the result identically, and a bare statement list
     * will not parse there.
     */
    data class EvaluateJs(
        val script: String,
        val into: String? = null,
    ) : WorkflowStep()

    /**
     * Waits for the page to post a `BridgeMessage` of [type]. Matches one that already arrived and
     * has not been consumed by an earlier step, so it is safe to await a message the page sends the
     * instant a previous step's script returns.
     */
    data class AwaitMessage(
        val type: String,
        val into: String,
        val timeoutMs: Long = 10_000L,
    ) : WorkflowStep()

    /**
     * Sends [message] to the page as a `MessageEvent('vitre')`. The outbound half of the
     * bridge, which until now no step could reach.
     */
    data class PostMessage(
        val message: String,
    ) : WorkflowStep()

    /**
     * Runs [then] when [condition] holds, and [otherwise] when it does not.
     *
     * The first step that contains steps, and the first that makes a workflow something other than
     * a straight line. What it is for is the page that is *sometimes* there — a cookie banner, an
     * interstitial, a "did you mean" page, a login form that only appears when the session lapsed.
     * Before this, the only ways to handle one were to fail the whole run or to smuggle the branch
     * into an [EvaluateJs] blob the engine cannot report on.
     *
     * **This is not the `if` you write in a `workflow { }` block.** Ordinary Kotlin `if` in a
     * builder still runs at *build* time and decides what the workflow contains; this one is part of
     * the workflow and runs against the page, where it can see what an earlier step extracted. The
     * DSL calls it `runIf` so the two cannot be confused at a glance — see [WorkflowScope.runIf].
     *
     * Nesting is unbounded, and step numbering follows it: a failure inside a branch reports a
     * [StepPath] like `2.then.0` rather than a flat index that would mean nothing.
     */
    data class If(
        val condition: Condition,
        val then: List<WorkflowStep>,
        val otherwise: List<WorkflowStep> = emptyList(),
    ) : WorkflowStep()

    /**
     * Runs [body] once per element of the JSON array in the variable [over], and stores what each
     * run produced in [into].
     *
     * The step that turns "here are the search results" into "here is what each result's own page
     * says", which no straight-line workflow can express: the pages to visit are not known when the
     * workflow is written, they came out of the [ExtractRows] one step earlier. [item] is the name
     * the body knows the current element by. An object element binds a variable per field —
     * `{item}.title`, `{item}.url` — and `{item}` itself is the whole element; a primitive element
     * is just `{item}`. Those are ordinary variables, so a [Template] reads them
     * (`navigate(template("{product.url}"))`) and so does a [Condition].
     *
     * ### What the body sees, and what it leaves behind
     *
     * Each item starts from a *copy* of the variables as they stood when the step began, with the
     * bindings added. Whatever the body extracts goes into that copy and nowhere else — items do
     * not see each other, and the workflow after the step does not see them by name. It sees
     * [into], a JSON array with one [FanOutResult] per item in item order: the element, the
     * variables the body set, and the failure message if it failed. `decode<List<FanOutResult>>`
     * reads it back.
     *
     * A failing item is recorded in its result and **does not fail the step**. Twenty product pages
     * and one bot check is the normal case, and a step that threw away nineteen answers over the
     * twentieth would be the wrong tool for the job it exists for. This mirrors [ExtractRows],
     * where a missing column is an empty field rather than a lost row. What *does* fail the step is
     * a workflow bug: [over] not set, or holding something other than a JSON array.
     *
     * ### A fan-out is a page barrier
     *
     * Items run on whatever lanes the engine's `LaneSource` has — several at once on a pool, one
     * after another on a single WebView — and to make that possible without deadlock the workflow
     * gives up its own lane before the first item starts and takes a fresh one afterwards. So the
     * step after a `ForEach` starts on a blank page: the variables survive, the document does not.
     * A `WaitFor` written as though the search results were still on screen will time out, and
     * that is the honest outcome — see `LaneSource` for why the alternative is worse.
     *
     * The events for an item arrive wrapped in [WorkflowEvent.FanOutItem], which says which item
     * and which lane. Body steps keep one [StepPath] however many items run them —
     * `2.each.0` — because a path names a step in the program, not an execution of it.
     *
     * [limit] caps the number of items, for the same reason [ExtractRows.limit] does and one more:
     * each item here is a page load, not a row read.
     */
    data class ForEach(
        val over: String,
        val item: String,
        val into: String,
        val body: List<WorkflowStep>,
        val limit: Int = 20,
    ) : WorkflowStep() {
        init {
            require(item.isNotBlank()) { "ForEach needs a name to bind each item to" }
            require(item.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                "`$item` is not a variable name. Use letters, digits, `_`, `-` or `.`."
            }
            require(limit > 0) { "ForEach limit must be positive, was $limit" }
        }
    }
}
