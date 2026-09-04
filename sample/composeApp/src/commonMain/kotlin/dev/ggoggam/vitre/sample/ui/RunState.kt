package dev.ggoggam.vitre.sample.ui

import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.StepPath
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.describe
import dev.ggoggam.vitre.core.workflow.walk

/** Per-step outcome, derived from the engine's event stream. */
enum class StepState {
    Pending,
    Running,
    Done,

    /**
     * A step in the branch a [WorkflowStep.If] did not take.
     *
     * Not something the engine reports — it says nothing about steps it never ran, which is the
     * right contract for it to keep. It is inferred here instead: once a run has finished, anything
     * still [Pending] was skipped, and rendering that as pending-forever would read as a UI that
     * lost track rather than as a branch not taken.
     */
    Skipped,
    Failed,
}

/** Where the run as a whole got to. Drives the status pill in the runner's app bar. */
enum class RunStatus { Idle, Running, Completed, Failed }

/**
 * One step of a workflow flattened for display: where it is, how deep, and what it is.
 *
 * A timeline is a list and a workflow is a tree, so somebody has to flatten it. Doing it once here
 * keeps [StepPath] — which is a key, not a position — out of the layout code, and gives the runner
 * the row ordinal it needs for "step 3 of 7".
 */
data class FlatStep(
    val path: StepPath,
    val step: WorkflowStep,
) {
    /** `0` for a top-level step; each nested branch adds one. Drives the row's indent. */
    val depth: Int get() = path.depth - 1
}

/** Every step of [this], nested branches included, in the order a timeline should show them. */
fun Workflow.flatSteps(): List<FlatStep> = walk().map { (path, step) -> FlatStep(path, step) }

/**
 * A view of one workflow run, folded out of the [WorkflowEvent]s emitted so far.
 *
 * The engine emits a linear stream; the UI wants random access ("what is step 3 doing?"), so the
 * whole state is recomputed from the event list rather than mutated in place. Runs are short and
 * the lists are tiny, so this stays cheap and keeps the UI a pure function of the events.
 *
 * Keyed by [StepPath] rather than indexed by position, because a workflow with a
 * [WorkflowStep.If] in it is a tree: two steps can both be "step 0", and only the path tells them
 * apart.
 */
data class RunState(
    val status: RunStatus,
    val stepStates: Map<StepPath, StepState>,
    val variables: Map<String, String>,
    val error: String?,
    /** Row ordinal of the innermost step in flight, for "step 3 of 7". Null when nothing is. */
    val runningOrdinal: Int?,
) {
    /** Every step the workflow has, branches included — the denominator of the status pill. */
    val stepCount: Int get() = stepStates.size

    val completedCount: Int get() = stepStates.count { it.value == StepState.Done }

    fun stateOf(path: StepPath): StepState = stepStates[path] ?: StepState.Pending
}

fun runStateOf(
    workflow: Workflow,
    events: List<WorkflowEvent>,
): RunState {
    val order = workflow.flatSteps().map { it.path }
    val states = order.associateWith { StepState.Pending }.toMutableMap()
    var status = if (events.isEmpty()) RunStatus.Idle else RunStatus.Running
    var error: String? = null
    var variables: Map<String, String> = emptyMap()
    // A stack rather than a single "currently running" slot, because a composite step stays running
    // while its branch runs: without this, the child's StepCompleted would report the enclosing
    // `If` as finished too, and the header would go back to counting steps mid-run.
    val inFlight = mutableListOf<StepPath>()

    for (event in events) {
        when (event) {
            is WorkflowEvent.StepStarted -> {
                states[event.path] = StepState.Running
                inFlight += event.path
            }

            is WorkflowEvent.StepCompleted -> {
                states[event.path] = StepState.Done
                inFlight -= event.path
            }

            is WorkflowEvent.Completed -> {
                status = RunStatus.Completed
                variables = event.variables
                inFlight.clear()
            }

            is WorkflowEvent.Failed -> {
                status = RunStatus.Failed
                error = event.message
                states[event.path] = StepState.Failed
                inFlight.clear()
            }
        }
    }

    // Anything still pending once the run is over is a branch that was not taken. While the run is
    // live those same steps are genuinely still pending, so this only applies at the end.
    if (status == RunStatus.Completed || status == RunStatus.Failed) {
        for (path in states.keys.toList()) {
            if (states[path] == StepState.Pending) states[path] = StepState.Skipped
        }
    }

    return RunState(
        status = status,
        stepStates = states,
        variables = variables,
        error = error,
        // The innermost in-flight step: `If` → `Click` should read as the click, not the `If`.
        runningOrdinal = inFlight.lastOrNull()?.let { path -> order.indexOf(path).takeIf { it >= 0 } },
    )
}

/** Short type name for a step, e.g. "Navigate". */
fun WorkflowStep.label(): String =
    when (this) {
        is WorkflowStep.Navigate -> "Navigate"
        is WorkflowStep.LoadHtml -> "Load HTML"
        is WorkflowStep.WaitFor -> "Wait for"
        is WorkflowStep.Click -> "Click"
        is WorkflowStep.Input -> "Input"
        is WorkflowStep.Extract -> "Extract"
        is WorkflowStep.ExtractRows -> "Extract rows"
        is WorkflowStep.Snapshot -> "Snapshot"
        is WorkflowStep.EvaluateJs -> "Evaluate JS"
        is WorkflowStep.PostMessage -> "Post message"
        is WorkflowStep.AwaitMessage -> "Await message"
        is WorkflowStep.If -> "If"
    }

/** The step's arguments, rendered as one monospace-friendly line. */
fun WorkflowStep.detail(): String =
    when (this) {
        is WorkflowStep.Navigate -> {
            url.describe()
        }

        is WorkflowStep.LoadHtml -> {
            "${html.length} bytes @ ${baseUrl ?: "opaque origin"}"
        }

        is WorkflowStep.WaitFor -> {
            "${locator.short()} · ${timeoutMs}ms"
        }

        is WorkflowStep.Click -> {
            locator.short()
        }

        is WorkflowStep.Input -> {
            "${locator.short()} ← \"${text.describe()}\""
        }

        is WorkflowStep.Extract -> {
            "${locator.short()}${from.suffix()} → $into"
        }

        is WorkflowStep.ExtractRows -> {
            "${rows.short()} × ${columns.size} cols, max $limit → $into"
        }

        is WorkflowStep.Snapshot -> {
            "max $maxNodes elements → $into"
        }

        is WorkflowStep.EvaluateJs -> {
            script
                .lineSequence()
                .first { it.isNotBlank() }
                .trim()
                .ellipsize(48) +
                (into?.let { " → $it" } ?: "")
        }

        is WorkflowStep.PostMessage -> {
            message.ellipsize(48)
        }

        is WorkflowStep.AwaitMessage -> {
            "type=$type → $into · ${timeoutMs}ms"
        }

        is WorkflowStep.If -> {
            // The branch sizes rather than the branch contents: those get their own rows directly
            // below, and repeating them here would be the same information twice at half the width.
            condition.describe().ellipsize(48) +
                " ? ${then.size}" +
                if (otherwise.isEmpty()) "" else " : ${otherwise.size}"
        }
    }

/**
 * Where a workflow's page comes from, for the list subtitle. `null` if it never loads one.
 *
 * A fixture workflow has no URL to show — it hands the document to the WebView directly — so it is
 * described by the origin it is given instead. Branches are searched too, since a workflow whose
 * only navigation sits inside a [WorkflowStep.If] still has an origin worth naming.
 */
fun Workflow.originUrl(): String? =
    walk().firstNotNullOfOrNull { (_, step) ->
        when (step) {
            // `describe()` rather than a resolved URL: this runs before the workflow does, so a
            // templated address has no values to fill in yet and the pattern is the honest answer.
            is WorkflowStep.Navigate -> step.url.describe()

            is WorkflowStep.LoadHtml -> step.baseUrl ?: "bundled fixture"

            else -> null
        }
    }

/** `h1` / `xpath:.//h2` — long enough to identify, short enough for one line. */
private fun Locator.short(): String =
    when (this) {
        is Locator.Css -> selector.ellipsize(40)
        is Locator.XPath -> "xpath:${expression.ellipsize(40)}"
        is Locator.Handle -> "ref:$ref"
    }

private fun WorkflowStep.Extract.Source.suffix(): String =
    when (this) {
        WorkflowStep.Extract.Source.Text -> ""
        is WorkflowStep.Extract.Source.Attribute -> "[$name]"
        is WorkflowStep.Extract.Source.Property -> ".$name"
    }

private fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"
