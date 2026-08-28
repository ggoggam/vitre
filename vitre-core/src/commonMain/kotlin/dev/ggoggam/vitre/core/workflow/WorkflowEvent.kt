package dev.ggoggam.vitre.core.workflow

sealed class WorkflowEvent {
    data class StepStarted(
        val path: StepPath,
        val step: WorkflowStep,
    ) : WorkflowEvent()

    data class StepCompleted(
        val path: StepPath,
    ) : WorkflowEvent()

    data class Completed(
        val variables: Map<String, String>,
    ) : WorkflowEvent()

    data class Failed(
        val path: StepPath,
        val message: String,
    ) : WorkflowEvent()
}
