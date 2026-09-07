package dev.ggoggam.vitre.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StepPathTest {
    private val nested =
        workflow("wf", "nested") {
            navigate("https://a.test")
            runIf(exists("#x"), otherwise = { click("#no") }) {
                click("#yes")
                runIf(exists("#y")) { navigate("https://deep.test") }
            }
        }

    @Test
    fun renders_the_root_segment_as_a_bare_number() {
        assertEquals("1", StepPath.root(1).toString())
    }

    @Test
    fun renders_branches_by_name() {
        val path = StepPath.root(1).child(StepPath.Branch.Then, 0).child(StepPath.Branch.Else, 2)
        assertEquals("1.then.0.else.2", path.toString())
        assertEquals("2.each.0", StepPath.root(2).child(StepPath.Branch.Each, 0).toString())
    }

    private val fanningOut =
        workflow("wf", "fan-out") {
            navigate("https://a.test")
            forEach(over = "rows", item = "row", into = "out") {
                navigate(template("{row.url}"))
                runIf(exists("#x")) { click("#x") }
            }
            extract("#after", into = "after")
        }

    /** A body step has one path however many items run it — the path names the program, not a run. */
    @Test
    fun resolves_and_walks_a_fan_out_body() {
        val body = StepPath.root(1).child(StepPath.Branch.Each, 0)
        assertEquals(WorkflowStep.Navigate(template("{row.url}")), fanningOut.stepAt(body))
        assertEquals(
            WorkflowStep.Click(css("#x")),
            fanningOut.stepAt(StepPath.root(1).child(StepPath.Branch.Each, 1).child(StepPath.Branch.Then, 0)),
        )
        // A ForEach has a body, not branches; an If has branches, not a body.
        assertNull(fanningOut.stepAt(StepPath.root(1).child(StepPath.Branch.Then, 0)))
        assertNull(fanningOut.stepAt(StepPath.root(1).child(StepPath.Branch.Each, 1).child(StepPath.Branch.Each, 0)))
        assertEquals(
            listOf("0", "1", "1.each.0", "1.each.1", "1.each.1.then.0", "2"),
            fanningOut.walk().map { (path, _) -> path.toString() },
        )
        for ((path, step) in fanningOut.walk()) {
            assertEquals(step, fanningOut.stepAt(path), "path $path")
        }
    }

    @Test
    fun an_empty_path_names_nothing_and_is_rejected() {
        assertFailsWith<IllegalArgumentException> { StepPath(emptyList()) }
    }

    @Test
    fun resolves_a_path_to_the_step_it_names() {
        assertEquals(WorkflowStep.Click(css("#yes")), nested.stepAt(StepPath.root(1).child(StepPath.Branch.Then, 0)))
        assertEquals(WorkflowStep.Click(css("#no")), nested.stepAt(StepPath.root(1).child(StepPath.Branch.Else, 0)))
        assertEquals(
            WorkflowStep.Navigate("https://deep.test"),
            nested.stepAt(
                StepPath.root(1).child(StepPath.Branch.Then, 1).child(StepPath.Branch.Then, 0),
            ),
        )
    }

    /** A UI resolving an event's path against the wrong workflow should render nothing, not crash. */
    @Test
    fun resolves_a_path_that_leads_nowhere_to_null() {
        assertNull(nested.stepAt(StepPath.root(9)))
        assertNull(nested.stepAt(StepPath.root(1).child(StepPath.Branch.Then, 9)))
        // Step 0 is a Navigate, which has no branches.
        assertNull(nested.stepAt(StepPath.root(0).child(StepPath.Branch.Then, 0)))
    }

    @Test
    fun walks_every_step_depth_first_in_source_order() {
        assertEquals(
            listOf("0", "1", "1.then.0", "1.then.1", "1.then.1.then.0", "1.else.0"),
            nested.walk().map { (path, _) -> path.toString() },
        )
    }

    /** What `walk` reports and what `stepAt` resolves have to be the same thing. */
    @Test
    fun every_walked_path_resolves_back_to_its_step() {
        for ((path, step) in nested.walk()) {
            assertEquals(step, nested.stepAt(path), "path $path")
        }
    }
}
