package dev.ggoggam.vitre.core.workflow

sealed class WorkflowStep {
    data class Navigate(
        val url: String,
    ) : WorkflowStep()

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

    data class Input(
        val locator: Locator,
        val text: String,
    ) : WorkflowStep() {
        constructor(selector: String, text: String) : this(css(selector), text)
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
}
