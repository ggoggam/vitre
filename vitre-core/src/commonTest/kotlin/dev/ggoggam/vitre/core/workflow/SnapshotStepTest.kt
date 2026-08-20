package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "I can ask what is on the page without knowing anything about it first, and act on what comes
 * back" — the use case every agent-driven run starts with.
 */
class SnapshotStepTest {
    private val pageJson =
        """
        {"url":"https://shop.test/results","title":"Results","truncated":false,"nodes":[
          {"ref":"e1","role":"heading","name":"Search results","tag":"h1","depth":0},
          {"ref":"e2","role":"textbox","name":"Search","tag":"input","depth":1,"value":"keyboard"},
          {"ref":"e3","role":"link","name":"Wireless keyboard","tag":"a","depth":1,
           "href":"/p/1"},
          {"ref":"e4","role":"button","name":"Add to cart","tag":"button","depth":1,
           "disabled":true}
        ]}
        """.trimIndent()

    @Test
    fun snapshot_stores_the_page_as_a_decodable_variable() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { pageJson } }
            val workflow =
                Workflow(
                    id = "wf-snapshot",
                    name = "snapshot",
                    steps = listOf(WorkflowStep.Snapshot(into = "page")),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            val snapshot = PageSnapshot.decode(completed.variables.getValue("page"))
            assertEquals("https://shop.test/results", snapshot.url)
            assertEquals(listOf("e1", "e2", "e3", "e4"), snapshot.nodes.map { it.ref })
            assertEquals("Add to cart", snapshot.nodes.last().name)
        }

    @Test
    fun the_rendered_snapshot_names_every_element_by_the_handle_that_acts_on_it() {
        val rendered = PageSnapshot.decode(pageJson).render()

        // Whatever else the rendering does, every line an agent might act on has to carry the token
        // it would act with. A snapshot an agent cannot address is only a description.
        for (ref in listOf("e1", "e2", "e3", "e4")) {
            assertTrue("[ref=$ref]" in rendered, "handle $ref missing from:\n$rendered")
        }
        assertTrue("Results — https://shop.test/results" in rendered, rendered)
        // Nesting comes from indentation rather than repeated `depth` keys, which is most of why
        // this form is cheaper than the JSON it was decoded from.
        assertTrue("\n  textbox \"Search\"" in rendered, rendered)
        assertTrue("value=\"keyboard\"" in rendered, "a field's typed value is what makes it worth reporting")
        assertTrue("disabled" in rendered, "an agent must be able to see it cannot press the button")
    }

    @Test
    fun a_truncated_walk_says_so_rather_than_looking_like_the_whole_page() {
        val rendered =
            PageSnapshot
                .decode("""{"url":"u","title":"t","truncated":true,"nodes":[]}""")
                .render()

        // Silently stopping at the budget would present the top of a long page as all of it, and the
        // agent would conclude the thing it is looking for is not there.
        assertTrue("truncated" in rendered, rendered)
    }

    @Test
    fun the_generated_script_asks_the_page_for_a_bounded_walk() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { pageJson } }
            val workflow =
                Workflow(
                    id = "wf-budget",
                    name = "budget",
                    steps = listOf(WorkflowStep.Snapshot(into = "page", maxNodes = 25, nameLimit = 40)),
                )

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val script = controller.evaluatedScripts.single()
            assertTrue("MAX=25" in script, "the budget must reach the page, not be trimmed after: $script")
            assertTrue("NAMELEN=40" in script, script)
        }
}
