package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.BridgeTimeoutException
import dev.ggoggam.vitre.core.bridge.awaitMessage
import dev.ggoggam.vitre.core.bridge.jsString
import dev.ggoggam.vitre.core.webview.ScriptTimeoutException
import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.webview.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a [Workflow] against one WebView.
 *
 * The engine is deliberately *not* confined to the WebView thread. Selector strings, JSON decoding
 * and variable bookkeeping are plain business logic and belong on [context] — `Dispatchers.Default`
 * by default — while the WebView thread is left free to render the page the workflow is driving.
 * Each individual operation crosses over inside the controller and comes back; the engine never
 * sees the WebView thread and never needs to.
 *
 * Pass `EmptyCoroutineContext` to run in the collector's context instead, which is what tests on a
 * virtual-time scheduler want.
 */
class WorkflowEngine(
    private val controller: WebViewController,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    fun run(workflow: Workflow): Flow<WorkflowEvent> =
        flow {
            val variables = mutableMapOf<String, String>()
            var index = 0
            try {
                for ((i, step) in workflow.steps.withIndex()) {
                    index = i
                    emit(WorkflowEvent.StepStarted(i, step))
                    dispatch(step, variables)
                    emit(WorkflowEvent.StepCompleted(i))
                }
                emit(WorkflowEvent.Completed(variables.toMap()))
            } catch (cancellation: CancellationException) {
                // Every timeout the library imposes on itself is converted to a plain exception at
                // the point it expires — PageLoadException, ScriptTimeoutException, the
                // AwaitMessage branch below — precisely so that a CancellationException arriving
                // here can only mean the collector gave up on us. Reporting that as a workflow
                // failure would both lie and break the caller's structured concurrency.
                throw cancellation
            } catch (t: Throwable) {
                emit(WorkflowEvent.Failed(index, t.message ?: "unknown error"))
            }
        }.flowOn(context)

    private suspend fun dispatch(
        step: WorkflowStep,
        variables: MutableMap<String, String>,
    ) {
        checkHandles(step)
        when (step) {
            is WorkflowStep.Navigate -> {
                controller.navigate(step.url)
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
                    while (!matches(step.locator)) {
                        delay(POLL_INTERVAL_MS)
                    }
                } ?: error("Timeout waiting for ${step.locator.describe()}")
            }

            is WorkflowStep.Click -> {
                controller.evaluateJs("${LocatorJs.first(step.locator)}?.click()")
            }

            is WorkflowStep.Input -> {
                // The only step whose script reports back on itself, and the reason is the whole
                // point of the family: typing, ticking and choosing each have a way to fail that
                // leaves the DOM looking exactly as it would have looked on success. A step that
                // does not ask cannot tell the two apart, which is how this used to confirm a form
                // the page had never received. One round trip either way — the status comes back
                // from the same call that did the work.
                val status = controller.evaluateJs(InputJs.script(step)).decodeJsResult()
                InputJs.explain(step, status)?.let { error(it) }
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
    private suspend fun matches(locator: Locator): Boolean =
        try {
            controller.evaluate("${LocatorJs.first(locator)}!==null")
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
    private suspend fun checkHandles(step: WorkflowStep) {
        for (locator in step.locators()) {
            if (locator !is Locator.Handle) continue
            val status = controller.evaluateJs(SnapshotJs.statusOf(locator.ref)).decodeJsResult()
            SnapshotJs.explain(locator.ref, status)?.let { error(it) }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
    }
}

/** Every element this step addresses, so a handle-aware caller can vet them before acting. */
private fun WorkflowStep.locators(): List<Locator> =
    when (this) {
        is WorkflowStep.WaitFor -> listOf(locator)

        is WorkflowStep.Click -> listOf(locator)

        is WorkflowStep.Input -> listOf(locator)

        is WorkflowStep.Extract -> listOf(locator)

        is WorkflowStep.ExtractRows -> listOf(rows) + columns.values.map { it.locator }

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
