package dev.ggoggam.vitre.core.workflow

/**
 * Where a step sits in a workflow — one index per level of nesting.
 *
 * A flat `Int` was enough while every workflow was a flat list, and stopped being enough the moment
 * [WorkflowStep.If] could hold steps of its own: "step 1 failed" no longer says *which* step 1. So
 * events carry a path, and the flat index is gone rather than kept alongside it — a field that is
 * only unambiguous for un-nested workflows is the kind of thing a caller reads once, believes, and
 * is wrong about later.
 *
 * **A path names a step in the program, not an execution of it.** That is what makes it usable as a
 * key: a UI can hold per-step state in a `Map<StepPath, _>` and have it survive whatever the engine
 * does at runtime. When loops arrive, the iteration count will not be smuggled in here — a step
 * inside a loop body has one path however many times it runs.
 */
data class StepPath(
    val segments: List<Segment>,
) {
    init {
        require(segments.isNotEmpty()) { "A step path needs at least one segment" }
    }

    /** One level down: which child list of the enclosing step, and which position within it. */
    data class Segment(
        val index: Int,
        val branch: Branch,
    )

    /**
     * Which list of an enclosing step a segment indexes into.
     *
     * [Root] is the workflow's own `steps`, so it appears exactly once, on the first segment. The
     * rest name a branch of the composite step the previous segment landed on.
     */
    enum class Branch {
        Root,
        Then,
        Else,
    }

    /** How deeply nested this step is. `1` for a top-level step. */
    val depth: Int get() = segments.size

    /** The step's position within its own list — what a UI numbers a row with. */
    val index: Int get() = segments.last().index

    /** This path with one more level appended, for a step inside [branch] of the step it names. */
    fun child(
        branch: Branch,
        index: Int,
    ): StepPath = StepPath(segments + Segment(index, branch))

    /**
     * `2`, `2.then.0`, `2.else.1` — what a [WorkflowEvent.Failed] message is read next to.
     *
     * The root segment contributes only its number, because "step `root.2`" says nothing the "2"
     * did not.
     */
    override fun toString(): String =
        segments.joinToString(".") { segment ->
            when (segment.branch) {
                Branch.Root -> "${segment.index}"
                Branch.Then -> "then.${segment.index}"
                Branch.Else -> "else.${segment.index}"
            }
        }

    companion object {
        /** The path of the workflow's [index]-th top-level step. */
        fun root(index: Int): StepPath = StepPath(listOf(Segment(index, Branch.Root)))
    }
}

/** The step [path] names, or null if it names one this workflow does not have. */
fun Workflow.stepAt(path: StepPath): WorkflowStep? = steps.stepAt(path)

/**
 * The step [path] names within this list, or null if the path leads somewhere that does not exist —
 * off the end of a list, or into a branch of a step that has no branches.
 *
 * Nullable rather than throwing because the natural caller is a UI resolving a path out of an event
 * against a workflow it was handed separately, and a mismatch there should render as nothing rather
 * than crash the runner.
 */
fun List<WorkflowStep>.stepAt(path: StepPath): WorkflowStep? {
    var current: List<WorkflowStep> = this
    var found: WorkflowStep? = null
    for ((position, segment) in path.segments.withIndex()) {
        if (position > 0) {
            val parent = found
            current =
                when {
                    parent !is WorkflowStep.If -> return null
                    segment.branch == StepPath.Branch.Then -> parent.then
                    segment.branch == StepPath.Branch.Else -> parent.otherwise
                    else -> return null
                }
        }
        found = current.getOrNull(segment.index) ?: return null
    }
    return found
}

/**
 * Every step in this workflow, nested ones included, each with the path that names it.
 *
 * Depth-first in source order, so a composite step is immediately followed by its `then` steps and
 * then its `else` steps — which is the order they are written in and the order a timeline should
 * render them.
 */
fun Workflow.walk(): List<Pair<StepPath, WorkflowStep>> = steps.walk()

/** [Workflow.walk] over a bare list of steps. */
fun List<WorkflowStep>.walk(): List<Pair<StepPath, WorkflowStep>> {
    val collected = mutableListOf<Pair<StepPath, WorkflowStep>>()

    fun visit(
        steps: List<WorkflowStep>,
        pathOf: (Int) -> StepPath,
    ) {
        steps.forEachIndexed { index, step ->
            val path = pathOf(index)
            collected += path to step
            if (step is WorkflowStep.If) {
                visit(step.then) { path.child(StepPath.Branch.Then, it) }
                visit(step.otherwise) { path.child(StepPath.Branch.Else, it) }
            }
        }
    }

    visit(this) { StepPath.root(it) }
    return collected
}
