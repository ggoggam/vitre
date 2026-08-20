package dev.ggoggam.vitre.sample.ui

import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowEvent
import dev.ggoggam.vitre.core.workflow.WorkflowStep

/** Per-step outcome, derived from the engine's event stream. */
enum class StepState { Pending, Running, Done, Failed }

/** Where the run as a whole got to. Drives the status pill in the runner's app bar. */
enum class RunStatus { Idle, Running, Completed, Failed }

/**
 * A view of one workflow run, folded out of the [WorkflowEvent]s emitted so far.
 *
 * The engine emits a linear stream; the UI wants random access ("what is step 3 doing?"), so the
 * whole state is recomputed from the event list rather than mutated in place. Runs are short and
 * the lists are tiny, so this stays cheap and keeps the UI a pure function of the events.
 */
data class RunState(
    val status: RunStatus,
    val stepStates: List<StepState>,
    val variables: Map<String, String>,
    val error: String?,
) {
    /** Index of the step currently executing, or `null` when nothing is in flight. */
    val runningStep: Int? get() = stepStates.indexOf(StepState.Running).takeIf { it >= 0 }

    val completedCount: Int get() = stepStates.count { it == StepState.Done }
}

fun runStateOf(
    stepCount: Int,
    events: List<WorkflowEvent>,
): RunState {
    val states = MutableList(stepCount) { StepState.Pending }
    var status = if (events.isEmpty()) RunStatus.Idle else RunStatus.Running
    var error: String? = null
    var variables: Map<String, String> = emptyMap()

    for (event in events) {
        when (event) {
            is WorkflowEvent.StepStarted -> {
                states.setAt(event.index, StepState.Running)
            }

            is WorkflowEvent.StepCompleted -> {
                states.setAt(event.index, StepState.Done)
            }

            is WorkflowEvent.Completed -> {
                status = RunStatus.Completed
                variables = event.variables
            }

            is WorkflowEvent.Failed -> {
                status = RunStatus.Failed
                error = event.message
                states.setAt(event.stepIndex, StepState.Failed)
            }
        }
    }
    return RunState(status = status, stepStates = states, variables = variables, error = error)
}

private fun MutableList<StepState>.setAt(
    index: Int,
    state: StepState,
) {
    if (index in indices) this[index] = state
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
    }

/** The step's arguments, rendered as one monospace-friendly line. */
fun WorkflowStep.detail(): String =
    when (this) {
        is WorkflowStep.Navigate -> {
            url
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
            "${locator.short()} ← \"$text\""
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
    }

/**
 * Where a workflow's page comes from, for the list subtitle. `null` if it never loads one.
 *
 * A fixture workflow has no URL to show — it hands the document to the WebView directly — so it is
 * described by the origin it is given instead.
 */
fun Workflow.originUrl(): String? =
    steps.firstNotNullOfOrNull { step ->
        when (step) {
            is WorkflowStep.Navigate -> step.url
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
