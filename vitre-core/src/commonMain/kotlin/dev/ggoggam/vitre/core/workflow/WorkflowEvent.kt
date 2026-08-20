package dev.ggoggam.vitre.core.workflow

sealed class WorkflowEvent {
    data class StepStarted(
        val index: Int,
        val step: WorkflowStep,
    ) : WorkflowEvent()

    data class StepCompleted(
        val index: Int,
    ) : WorkflowEvent()

    data class Completed(
        val variables: Map<String, String>,
    ) : WorkflowEvent()

    data class Failed(
        val stepIndex: Int,
        val message: String,
    ) : WorkflowEvent()
}
