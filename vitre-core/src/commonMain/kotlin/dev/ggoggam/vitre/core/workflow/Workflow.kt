package dev.ggoggam.vitre.core.workflow

data class Workflow(
    val id: String,
    val name: String,
    val steps: List<WorkflowStep>,
)
