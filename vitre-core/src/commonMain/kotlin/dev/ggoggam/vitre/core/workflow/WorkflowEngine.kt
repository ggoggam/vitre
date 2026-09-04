package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.BridgeTimeoutException
import dev.ggoggam.vitre.core.bridge.awaitMessage
import dev.ggoggam.vitre.core.bridge.jsString
import dev.ggoggam.vitre.core.frame.Lane
import dev.ggoggam.vitre.core.frame.LaneSource
import dev.ggoggam.vitre.core.webview.ScriptTimeoutException
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.webview.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a [Workflow] on lanes borrowed from a [LaneSource].
 *
 * The engine is deliberately *not* confined to the WebView thread. Selector strings, JSON decoding
 * and variable bookkeeping are plain business logic and belong on [context] — `Dispatchers.Default`
 * by default — while the WebView thread is left free to render the page the workflow is driving.
 * Each individual operation crosses over inside the controller and comes back; the engine never
 * sees the WebView thread and never needs to.
 *
 * Pass `EmptyCoroutineContext` to run in the collector's context instead, which is what tests on a
 * virtual-time scheduler want.
 *
 * ### Lanes
 *
 * A run borrows a lane before its first step and keeps it across the steps that follow, so a
 * `Navigate` and the `WaitFor` after it see the same document. It gives the lane back at a
 * [WorkflowStep.ForEach], runs the items on lanes of their own — several at once when the source
 * has several — and borrows again for whatever comes after. The stream reports each borrow as a
 * [WorkflowEvent.LaneLeased]. The secondary constructor wraps a single controller in a source of
 * one lane, which is what a host with one WebView wants and what every caller had before there was
 * a choice.
 */
class WorkflowEngine(
    private val lanes: LaneSource,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    /** Runs everything on [controller], one segment at a time. See [LaneSource.of]. */
    constructor(
        controller: WebViewController,
        context: CoroutineContext = Dispatchers.Default,
    ) : this(LaneSource.of(controller), context)

    fun run(workflow: Workflow): Flow<WorkflowEvent> =
        channelFlow {
            // A channel rather than a plain `flow` because a fan-out's items emit from several
            // coroutines at once, and `FlowCollector.emit` is not safe to call from more than one.
            // `send` is.
            val emitter = Emitter { send(it) }
            val holder = LaneHolder(workflow.name, emitter)
            emitter.execute(holder, workflow.steps, mutableMapOf(), flushOnExit = false) { StepPath.root(it) }
        }.flowOn(context)

    /** Where events go. A function rather than a `FlowCollector` so items can share one channel. */
    private fun interface Emitter {
        suspend fun emit(event: WorkflowEvent)
    }

    /**
     * Runs one list of steps as a self-contained run — the workflow's own, or one item of a
     * fan-out — on lanes [holder] borrows, and reports how it ended.
     *
     * Borrows a lane up front rather than at the first step that needs one, so that the run's
     * first event is its [WorkflowEvent.LaneLeased]: a caller queueing several workflows on a
     * narrow pool sees nothing from a workflow until it is actually on a lane, which is what
     * "queued" should mean. The lane is given back on every way out, and on the successful way out
     * of an item the cookie jar is nudged first ([flushOnExit] — see [LaneHolder.release]). The
     * workflow's own run skips that: nothing follows it that another lane will pick up, and an
     * agent running one-step workflows against a single WebView should not pay a round trip per
     * step for a jar nobody else is about to read.
     */
    private suspend fun Emitter.execute(
        holder: LaneHolder,
        steps: List<WorkflowStep>,
        variables: MutableMap<String, String>,
        flushOnExit: Boolean,
        pathOf: (Int) -> StepPath,
    ): Outcome {
        try {
            holder.lane(pathOf(0))
            runSteps(steps, variables, holder, pathOf)
            holder.release(flushCookies = flushOnExit)
            emit(WorkflowEvent.Completed(variables.toMap()))
            return Outcome.Done
        } catch (cancellation: CancellationException) {
            // Every timeout the library imposes on itself is converted to a plain exception at
            // the point it expires — PageLoadException, ScriptTimeoutException, the
            // AwaitMessage branch below — precisely so that a CancellationException arriving
            // here can only mean the collector gave up on us. Reporting that as a workflow
            // failure would both lie and break the caller's structured concurrency.
            throw cancellation
        } catch (failure: StepFailure) {
            emit(WorkflowEvent.Failed(failure.path, failure.reason))
            return Outcome.Failed(failure.reason)
        } finally {
            holder.releaseQuietly()
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome

        data class Failed(
            val reason: String,
        ) : Outcome
    }

    /**
     * The lane one run is currently on, if any.
     *
     * A run is a sequence of *segments* — stretches of steps that share a document — separated by
     * fan-outs. This holds the lane for the current segment: [lane] borrows one on first use and
     * hands the same one back until [release], after which the next [lane] borrows afresh. What it
     * is *not* is a lock: nothing here stops a caller driving the same WebView, which is what
     * [WebViewController.exclusively] is for.
     */
    private inner class LaneHolder(
        val label: String,
        /** Where [WorkflowEvent.LaneLeased] goes. An item's wraps it; see [fanOut]. */
        private val emitter: Emitter,
    ) {
        private var held: Lane? = null

        /** The lane the most recent lease gave, kept after release so an item's last events can name it. */
        var lastLaneId: String? = null
            private set

        /**
         * The current lane, borrowing one if the segment has none yet.
         *
         * A source that cannot make a lane ready fails the step at [path] — the one that needed
         * the lane — with the source's own reason. For the eager lease at the start of a run that
         * is step 0, for want of anywhere truer to point: the workflow has no path that names a
         * step taken on its behalf. What is accurate either way is that nothing ran.
         */
        suspend fun lane(path: StepPath): Lane =
            held ?: attempt(path) { lanes.acquire(label) }.also {
                held = it
                lastLaneId = it.id
                emitter.emit(WorkflowEvent.LaneLeased(it.id))
            }

        /**
         * Gives the lane back, with the cookie jar flushed first when [flushCookies] is set.
         *
         * The flush is one read of the jar for the page the lane is on, and it is there for a
         * reason that is easier to state than to verify: on iOS each lane is its own content
         * process, and whether a `document.cookie` write on one is visible from another *promptly*
         * is a question WebKit does not answer in writing and this repo could not measure (see
         * `SharedCookieJarTest`). Asking the shared store for its cookies is what makes WebKit
         * gather them from the processes, so an item that logged in leaves the session where the
         * next lane's page will find it. On Android the jar is process-wide and the read is merely
         * cheap. Nothing about it may fail the run — a page with no resolvable host, or one that
         * navigated away mid-question, is a flush that did nothing.
         */
        suspend fun release(flushCookies: Boolean) {
            val lane = held ?: return
            if (flushCookies) lane.controller.flushCookieJar()
            releaseQuietly()
        }

        /** [release] with no flush, for the way out of a failed or cancelled run. Idempotent. */
        fun releaseQuietly() {
            held?.let { lanes.release(it) }
            held = null
        }
    }

    /**
     * Runs one list of steps, which is the run's own or a branch of a [WorkflowStep.If], and emits
     * an event pair for each.
     *
     * [pathOf] turns a position in *this* list into the path that names it from the workflow's root,
     * so a branch's steps report `2.then.0` without this function knowing where it sits.
     *
     * A composite step's own [WorkflowEvent.StepCompleted] is emitted after its branch has finished,
     * so the stream nests the way the steps do: `If` started, child started, child completed, `If`
     * completed. A caller that only wants the leaves can ignore the composites; a caller drawing a
     * tree gets the structure for free.
     */
    private suspend fun Emitter.runSteps(
        steps: List<WorkflowStep>,
        variables: MutableMap<String, String>,
        holder: LaneHolder,
        pathOf: (Int) -> StepPath,
    ) {
        for ((index, step) in steps.withIndex()) {
            val path = pathOf(index)
            emit(WorkflowEvent.StepStarted(path, step))
            when (step) {
                is WorkflowStep.If -> {
                    val taken = evaluate(step.condition, variables, holder, path)
                    val branch = if (taken) StepPath.Branch.Then else StepPath.Branch.Else
                    val body = if (taken) step.then else step.otherwise
                    runSteps(body, variables, holder) { path.child(branch, it) }
                }

                is WorkflowStep.ForEach -> {
                    fanOut(step, variables, holder, path)
                }

                else -> {
                    attempt(path) { dispatch(step, variables, holder.lane(path).controller, path) }
                }
            }
            emit(WorkflowEvent.StepCompleted(path))
        }
    }

    /**
     * Runs [step]'s body once per item, on lanes borrowed per item, and stores the results.
     *
     * The order of operations is the deadlock argument from [LaneSource] made concrete: the array
     * is read and the lane is **released** before the first item is launched, so a parent never
     * holds a lane while its children wait for one. Items are launched in index order, and both
     * sources hand lanes out in the order they were asked for, so a single WebView runs them in
     * sequence and a pool starts them in order and finishes them in whatever order the pages allow.
     *
     * Each item gets its own copy of the variables, its own [LaneHolder] and its own view of the
     * emitter, wrapping everything it says in a [WorkflowEvent.FanOutItem]. The parent's variables
     * are touched only after every item is done, so nothing here races.
     */
    private suspend fun Emitter.fanOut(
        step: WorkflowStep.ForEach,
        variables: MutableMap<String, String>,
        holder: LaneHolder,
        path: StepPath,
    ) {
        val raw = variables.require(step.over, path)
        val items =
            (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray)
                ?: throw StepFailure(
                    path,
                    "`${step.over}` does not hold a JSON array — ForEach iterates over what ExtractRows " +
                        "stored, and this holds: ${raw.take(80)}",
                )
        val taken = items.take(step.limit)

        holder.release(flushCookies = true)

        val results = arrayOfNulls<FanOutResult>(taken.size)
        coroutineScope {
            taken.forEachIndexed { index, item ->
                launch {
                    val bound = variables.toMutableMap().apply { bindItem(step.item, item) }
                    val start = bound.toMap()
                    // The item's emitter names the lane its holder is on, and its holder reports
                    // leases through that emitter — so the two refer to each other, and the
                    // holder is assigned after the emitter that will read it.
                    var itemHolder: LaneHolder? = null
                    val itemEmitter =
                        Emitter { event ->
                            this@fanOut.emit(WorkflowEvent.FanOutItem(path, index, taken.size, itemHolder?.lastLaneId, event))
                        }
                    val holderForItem = LaneHolder("${holder.label} · ${step.item} ${index + 1}/${taken.size}", itemEmitter)
                    itemHolder = holderForItem
                    val outcome =
                        itemEmitter.execute(holderForItem, step.body, bound, flushOnExit = true) {
                            path.child(StepPath.Branch.Each, it)
                        }
                    results[index] =
                        FanOutResult(
                            index = index,
                            item = item,
                            // What the body set: everything that differs from how the item began.
                            variables = bound.filter { (name, value) -> start[name] != value },
                            error = (outcome as? Outcome.Failed)?.reason,
                        )
                }
            }
        }

        variables[step.into] = WorkflowJson.encodeToString(ListSerializer(FanOutResult.serializer()), results.map { requireNotNull(it) })
    }

    /**
     * Runs [body], and labels anything it throws with the path of the step that threw.
     *
     * The path has to be attached here rather than reconstructed at the top, because by the time an
     * exception reaches `run` the recursion that knew where it came from has already unwound. An
     * exception that is already a [StepFailure] passes through untouched, so the *innermost* step is
     * the one reported rather than every enclosing [WorkflowStep.If] overwriting it on the way out.
     */
    private suspend fun <T> attempt(
        path: StepPath,
        body: suspend () -> T,
    ): T =
        try {
            body()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: StepFailure) {
            throw failure
        } catch (t: Throwable) {
            throw StepFailure(path, t.message ?: "unknown error")
        }

    /**
     * Answers [condition] against the page and the variables set so far.
     *
     * Everything that can go wrong here is a workflow bug rather than a page state — an unset
     * variable, an uncompilable regex, a script that throws — so all of it fails the step. The one
     * question with a legitimate negative answer is [Condition.Exists], and it is the only branch
     * that turns "not there" into `false` instead of an error. See [Condition.Exists] for why that
     * makes a stale handle behave differently here than in every other step.
     *
     * Only the two conditions that look at the page borrow a lane. A workflow that branches on a
     * variable straight after a fan-out should not have to pay for a page it will not look at.
     */
    private suspend fun evaluate(
        condition: Condition,
        variables: Map<String, String>,
        holder: LaneHolder,
        path: StepPath,
    ): Boolean =
        when (condition) {
            is Condition.Exists -> {
                attempt(path) { holder.lane(path).controller.evaluate("${LocatorJs.first(condition.locator)}!==null") }
            }

            is Condition.VariableEquals -> {
                variables.require(condition.name, path).equals(condition.value, ignoreCase = condition.ignoreCase)
            }

            is Condition.VariableMatches -> {
                val regex =
                    runCatching { Regex(condition.regex) }
                        .getOrElse { throw StepFailure(path, "Not a valid regex: /${condition.regex}/") }
                regex.containsMatchIn(variables.require(condition.name, path))
            }

            is Condition.JsTruthy -> {
                // Wrapped rather than decoded loosely: the page decides truthiness, so `0`, `""` and
                // `undefined` come back as the `false` JS says they are instead of this side having
                // to reimplement the rules and get one of them wrong.
                attempt(path) { holder.lane(path).controller.evaluate("!!(${condition.script})") }
            }

            is Condition.Not -> {
                !evaluate(condition.of, variables, holder, path)
            }

            is Condition.AllOf -> {
                condition.of.all { evaluate(it, variables, holder, path) }
            }

            is Condition.AnyOf -> {
                condition.of.any { evaluate(it, variables, holder, path) }
            }
        }

    /**
     * Fills in [template]'s variables from [variables].
     *
     * Routed through [require] so that a template naming something no step set fails exactly the
     * way [Condition.VariableEquals] does, with the same list of what *was* set. Substituting an
     * empty string instead would produce a URL that is syntactically fine and points somewhere
     * nobody asked for, which is the failure this whole type exists to avoid.
     */
    private fun Template.resolve(
        variables: Map<String, String>,
        path: StepPath,
    ): String =
        when (this) {
            is Template.Literal -> value
            is Template.Variable -> variables.require(name, path)
            is Template.Parts -> of.joinToString("") { it.resolve(variables, path) }
        }

    private fun Map<String, String>.require(
        name: String,
        path: StepPath,
    ): String =
        this[name] ?: throw StepFailure(
            path,
            "No variable `$name`. The workflow set: ${keys.sorted().joinToString(", ").ifEmpty { "nothing" }}",
        )

    private suspend fun dispatch(
        step: WorkflowStep,
        variables: MutableMap<String, String>,
        controller: WebViewController,
        path: StepPath,
    ) {
        controller.checkHandles(step)
        when (step) {
            is WorkflowStep.Navigate -> {
                controller.navigate(step.url.resolve(variables, path))
            }

            is WorkflowStep.LoadHtml -> {
                controller.loadHtml(step.html, step.baseUrl)
            }

            is WorkflowStep.WaitFor -> {
                // Bounded on wall-clock rather than by counting poll intervals: each poll is a
                // round trip to the WebView thread, and on a page slow enough to need waiting for
                // that round trip is the dominant cost. Counting only the delays let a nominal 10s
                // timeout run for a minute.
                withTimeoutOrNull(step.timeoutMs) {
                    while (!controller.matches(step.locator)) {
                        delay(POLL_INTERVAL_MS)
                    }
                } ?: error("Timeout waiting for ${step.locator.describe()}")
            }

            is WorkflowStep.Click -> {
                controller.evaluateJs("${LocatorJs.first(step.locator)}?.click()")
            }

            is WorkflowStep.Input -> {
                controller.evaluateJs(
                    "(function(){var el=${LocatorJs.first(step.locator)};" +
                        "if(el){el.value=${jsString(step.text.resolve(variables, path))};" +
                        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "el.dispatchEvent(new Event('change',{bubbles:true}));}})()",
                )
            }

            is WorkflowStep.Extract -> {
                val expr = LocatorJs.read(LocatorJs.first(step.locator), step.from)
                variables[step.into] = controller.evaluateJs(expr).decodeJsResult()
            }

            is WorkflowStep.ExtractRows -> {
                val fields =
                    step.columns.entries.joinToString(",") { (name, column) ->
                        // Resolved against `r`, the row — not the document. That is what makes an
                        // omitted field an empty string in one record instead of shifting every
                        // later record onto the wrong row.
                        "${jsString(name)}:${LocatorJs.read(LocatorJs.first(column.locator, "r"), column.from)}"
                    }
                val expr =
                    "(function(){return ${LocatorJs.all(step.rows)}" +
                        ".slice(0,${step.limit}).map(function(r){return {$fields};});})()"
                // Left as the JSON array the page produced: a list of records has no more faithful
                // rendering as a single string, and whatever consumes it will parse it anyway.
                variables[step.into] = controller.evaluateJs(expr).decodeJsResult()
            }

            is WorkflowStep.Snapshot -> {
                variables[step.into] =
                    controller
                        .evaluateJs(SnapshotJs.snapshot(step.maxNodes, step.nameLimit))
                        .decodeJsResult()
            }

            is WorkflowStep.EvaluateJs -> {
                val result = controller.evaluateJs(step.script)
                if (step.into != null) variables[step.into] = result.decodeJsResult()
            }

            is WorkflowStep.PostMessage -> {
                controller.bridge.postToWebView(step.message)
            }

            is WorkflowStep.If, is WorkflowStep.ForEach -> {
                // Unreachable: runSteps handles a composite step itself, because dispatching one
                // would mean running its children outside the recursion that numbers the steps.
                error("${step::class.simpleName} is executed by runSteps, not dispatch")
            }

            is WorkflowStep.AwaitMessage -> {
                // Unbounded before: a page that never posts the type being waited for wedged the
                // workflow with no event, no error and no way back. The bound and the decode both
                // live in `bridge.awaitMessage(type)` now; what stays here is the wording of the
                // failure, which MCP agents read.
                variables[step.into] =
                    try {
                        controller.bridge.awaitMessage(step.type, step.timeoutMs).raw
                    } catch (_: BridgeTimeoutException) {
                        error("Timeout waiting for bridge message: ${step.type}")
                    }
            }
        }
    }

    /**
     * One poll of a [WorkflowStep.WaitFor], where "the document went away" means *not yet* rather
     * than *failed*.
     *
     * A [Click] that submits a form returns as soon as `click()` does, so the navigation it starts
     * commits some time later — while this loop is already polling. Both platforms drop a script
     * callback whose document is replaced without ever invoking it, so whichever poll happens to be
     * in flight at that moment comes back as [ScriptTimeoutException]. Letting that escape aborts
     * the run at the exact point the awaited page is arriving, which is the worst possible reading
     * of it: the element is on the document now loading, and the next poll is the one that finds
     * it. The wall-clock bound above is what still ends a wait that genuinely never resolves.
     *
     * The result is decoded rather than compared against `"true"`. `!==null` yields a JS boolean,
     * so a page that answers this with anything else has broken an invariant worth failing on —
     * whereas the string comparison read every such answer as "not yet" and polled until the
     * timeout, reporting a missing element rather than a page behaving impossibly.
     */
    private suspend fun WebViewController.matches(locator: Locator): Boolean =
        try {
            evaluate("${LocatorJs.first(locator)}!==null")
        } catch (_: ScriptTimeoutException) {
            false
        }

    /**
     * Fails the step if it addresses an element by a handle the page cannot resolve.
     *
     * Costs one extra round trip per handle, and buys the difference between an agent being told
     * *"handle `e7` refers to an element that has since been removed"* and an agent watching a click
     * land on nothing. Every generated expression resolves a missing handle to `null`, so without
     * this the step would succeed having done nothing at all — the failure mode a handle exists to
     * rule out. Selector-addressed steps skip it entirely.
     */
    private suspend fun WebViewController.checkHandles(step: WorkflowStep) {
        for (locator in step.locators()) {
            if (locator !is Locator.Handle) continue
            val status = evaluateJs(SnapshotJs.statusOf(locator.ref)).decodeJsResult()
            SnapshotJs.explain(locator.ref, status)?.let { error(it) }
        }
    }

    /** See [LaneHolder.release]. Swallows everything but cancellation, by design. */
    private suspend fun WebViewController.flushCookieJar() {
        val jar = cookies ?: return
        try {
            val url = evaluateJs("location.href").decodeJsResult()
            jar.read(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A flush that did nothing. The page had no host, or went away mid-question.
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
    }
}

/**
 * A step's failure, carrying the path of the step it came from.
 *
 * Internal to the engine and never emitted: `run` unwraps it into [WorkflowEvent.Failed]. It exists
 * only because the recursion that knows *where* a step is has unwound by the time the exception
 * reaches the top, so the path has to travel with the throw.
 */
private class StepFailure(
    val path: StepPath,
    val reason: String,
) : Exception(reason)

/** Every element this step addresses, so a handle-aware caller can vet them before acting. */
private fun WorkflowStep.locators(): List<Locator> =
    when (this) {
        is WorkflowStep.WaitFor -> listOf(locator)

        is WorkflowStep.Click -> listOf(locator)

        is WorkflowStep.Input -> listOf(locator)

        is WorkflowStep.Extract -> listOf(locator)

        is WorkflowStep.ExtractRows -> listOf(rows) + columns.values.map { it.locator }

        // Not the branches' or the body's: a handle inside one is only worth vetting if that
        // branch is taken, and the condition's own locator is deliberately exempt — see
        // Condition.Exists.
        is WorkflowStep.If,
        is WorkflowStep.ForEach,
        is WorkflowStep.Navigate,
        is WorkflowStep.LoadHtml,
        is WorkflowStep.Snapshot,
        is WorkflowStep.EvaluateJs,
        is WorkflowStep.PostMessage,
        is WorkflowStep.AwaitMessage,
        -> emptyList()
    }

/**
 * Turns a JSON-encoded script result into the plain string a variable should hold.
 *
 * Stripping the outer quotes by hand — which is what this used to do — leaves the escapes in, so
 * text extracted from any element containing a newline or a quote arrived with a literal `\n` in
 * it. Anything that is not a JSON string (a number, a boolean, an object) keeps its JSON form,
 * since there is no more faithful rendering of it as a string.
 */
private fun String.decodeJsResult(): String =
    runCatching { Json.parseToJsonElement(this) }
        .getOrNull()
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.content
        ?: this
