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

    /**
     * Drives one control the way a person would: type into it, tick it, pick from it, press a key
     * at it.
     *
     * A family rather than one step with a string, because *what to put in* is not one kind of
     * thing. `Input(box, "false")` would have to mean **untick**, and every rule that could make it
     * mean that — truthiness, a table of accepted spellings — is a rule the call site cannot see. A
     * `Boolean` argument cannot be got wrong. The same goes for a `<select>`, where the string is
     * matched against two different things, and for a keystroke, which puts nothing in at all.
     *
     * They share a [locator] and a [text] rendering of what they will apply, so anything that
     * already handles `Input` — a step list, an event log — keeps working across all four.
     *
     * Every one of them **reports what happened** instead of resolving to `null` and carrying on.
     * That is the difference this family was introduced for: each of these actions has a way to
     * fail that leaves the DOM looking as though it worked, so a step that cannot see the outcome
     * is a step that reports success on a form the page never received.
     */
    sealed class Input : WorkflowStep() {
        abstract val locator: Locator

        /**
         * What this step will apply, as one line of text.
         *
         * For logs and failure messages only — nothing reads it back. The direction matters: a
         * `Boolean` *rendered* as `"true"` is a legible trace, whereas a `"true"` *parsed* as a
         * boolean is exactly the guess this family exists to remove.
         */
        abstract val text: String

        /**
         * Types [text] into a text field, a `<textarea>`, a `<select>` or a contenteditable
         * element, replacing whatever was there.
         *
         * Assigning `el.value` — what this step used to do — is not what typing does, and against a
         * React-controlled field it is worse than useless. React installs its own `value` setter on
         * the element to track the DOM against its state; a plain assignment goes through it, so
         * the tracker concludes nothing changed, `onChange` never fires, the component's state
         * stays empty and the form submits without the text. Nothing reports an error, and a later
         * [Extract] with [Extract.Source.Property] reads back the value this step wrote — so the
         * workflow *confirms* a value the application never received. `InputJs` documents the
         * setter used instead.
         *
         * A `<select>` is filled by choosing the option [text] names, on the same match
         * [SelectOption] makes; a contenteditable by replacing its contents, not by appending at
         * the caret. Anything else fails rather than having a stray `value` property hung on it.
         */
        data class Fill(
            override val locator: Locator,
            override val text: String,
        ) : Input() {
            constructor(selector: String, text: String) : this(css(selector), text)
        }

        /**
         * Puts a checkbox or radio button into the state [checked], by clicking it if it is not
         * already there.
         *
         * Clicking rather than assigning `el.checked`, and the difference is the whole step.
         * Assignment moves the DOM property and fires nothing at all, so a page listening for
         * `change` — which is every page that does anything when you tick a box — never learns.
         * `click()` runs the browser's own activation behaviour: it flips `checked`, fires `input`
         * and `change`, and reaches a framework's synthetic event system, the way a finger does.
         *
         * Clicking is also why the current state has to be read first. An unconditional click
         * toggles a box that was already right, which is the likeliest way for re-running a
         * workflow to undo it.
         */
        data class SetChecked(
            override val locator: Locator,
            val checked: Boolean,
        ) : Input() {
            constructor(selector: String, checked: Boolean) : this(css(selector), checked)

            override val text: String get() = checked.toString()
        }

        /**
         * Selects the option of a `<select>` identified by [option] — its `value`, or failing that
         * the label a user reads.
         *
         * Both, in that order, because the two halves of this library disagreed about what names an
         * option. `PageSnapshot` renders one by its accessible *name*, so an agent that reads
         * `option "Large"` and passes it straight back was hitting `el.value = 'Large'` — and a
         * `<select>` silently discards a value no option carries, leaving the control blank and the
         * step green. When neither matches, this says so and lists what the control does offer,
         * which is the one thing that makes the failure recoverable without another round trip.
         *
         * [Fill] makes the same match, so `input(select, "Large")` also works. This spelling adds
         * the check that the target *is* a `<select>`, so a locator that has drifted onto a text
         * box fails instead of quietly typing "Large" into it.
         */
        data class SelectOption(
            override val locator: Locator,
            val option: String,
        ) : Input() {
            constructor(selector: String, option: String) : this(css(selector), option)

            override val text: String get() = option
        }

        /**
         * Focuses the element and dispatches [key] as a real keystroke would — `keydown`, then
         * `keypress` for a key that produces a character, then `keyup`, each carrying `key`, `code`
         * and the legacy `keyCode`/`which` that a lot of shipped page code still reads.
         *
         * Nothing else in the vocabulary can do this. [Fill] fires `input` and `change`, so a box
         * whose page listens for `keydown` — every type-ahead, every arrow-key menu, every
         * Enter-to-search — saw the text appear and no keystroke arrive.
         *
         * **It does not perform the browser's own default action**, and cannot: a dispatched event
         * is `isTrusted:false`, so pressing a printable key inserts no character and pressing Enter
         * in a plain `<form>` does not submit it. What runs is the page's own handlers, which is
         * what a scripted page needs and all that is honestly available. To submit a plain form,
         * [Click] its submit button. To type text, use [Fill].
         *
         * [key] is a single character, or a named key spelled as the DOM spells it — `Enter`,
         * `Tab`, `Escape`, `ArrowDown`. `code` is the physical key on a US layout, which is the
         * same convention the DevTools protocol uses. Anything else fails at the step rather than
         * dispatching an event no keyboard could have produced.
         */
        data class Press(
            override val locator: Locator,
            val key: String,
        ) : Input() {
            constructor(selector: String, key: String) : this(css(selector), key)

            override val text: String get() = key
        }

        /**
         * Keeps `Input(selector, text)` and `Input(locator, text)` meaning what they always meant.
         *
         * They were the only spellings before this step became a family, they are still the common
         * case, and every existing caller — the MCP `type` tool included — writes one of them. An
         * `invoke` rather than a constructor because a sealed class has none to offer: the call
         * resolves here and builds a [Fill], so the compatibility is exact rather than approximate.
         */
        companion object {
            operator fun invoke(
                locator: Locator,
                text: String,
            ): Fill = Fill(locator, text)

            operator fun invoke(
                selector: String,
                text: String,
            ): Fill = Fill(css(selector), text)
        }
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
}
