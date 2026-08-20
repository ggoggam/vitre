package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocatorStepTest {
    @Test
    fun a_bare_selector_still_means_css() =
        runTest {
            // The shorthand constructors exist so adding XPath did not churn every call site.
            assertEquals(WorkflowStep.WaitFor(css("h1")), WorkflowStep.WaitFor("h1"))
            assertEquals(WorkflowStep.Click(css("#go")), WorkflowStep.Click("#go"))
            assertEquals(WorkflowStep.Input(css("#q"), "hi"), WorkflowStep.Input("#q", "hi"))
            assertEquals(
                WorkflowStep.Extract(css("h1"), into = "t"),
                WorkflowStep.Extract("h1", into = "t"),
            )
        }

    @Test
    fun xpath_steps_evaluate_through_document_evaluate() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "true" } }
            val workflow =
                Workflow(
                    id = "wf-xpath",
                    name = "xpath",
                    steps =
                        listOf(
                            WorkflowStep.WaitFor(xpath("//h2[@aria-label]"), timeoutMs = 1_000),
                            WorkflowStep.Click(xpath("//button[normalize-space()='Go']")),
                        ),
                )

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val scripts = controller.evaluatedScripts
            assertTrue(scripts.all { "document.evaluate" in it }, "not XPath: $scripts")
            assertTrue(scripts.none { "querySelector" in it }, "fell back to CSS: $scripts")
            // FIRST_ORDERED_NODE_TYPE, and the context node the expression is resolved against.
            assertTrue(scripts.all { ",document,null,9,null" in it }, "wrong result type: $scripts")
        }

    @Test
    fun a_failed_wait_names_the_query_language() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "false" } }
            val workflow =
                Workflow(
                    id = "wf-miss",
                    name = "miss",
                    steps = listOf(WorkflowStep.WaitFor(xpath("//nope"), timeoutMs = 300)),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // "no match for //nope" is only actionable if you know it was not read as CSS.
            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertTrue("xpath" in failed.message, "unhelpful message: ${failed.message}")
            assertTrue("//nope" in failed.message, "unhelpful message: ${failed.message}")
        }

    @Test
    fun extract_rows_resolves_columns_against_the_row_not_the_document() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { """[{"sku":"KB-1","title":"One"},{"sku":"KB-2","title":"Two"}]""" }
                }
            val workflow =
                Workflow(
                    id = "wf-rows",
                    name = "rows",
                    steps =
                        listOf(
                            WorkflowStep.ExtractRows(
                                rows = xpath("//li[@data-sku]"),
                                limit = 5,
                                into = "results",
                                columns =
                                    linkedMapOf(
                                        "sku" to
                                            WorkflowStep.ExtractRows.Column(xpath("."), WorkflowStep.Extract.Source.Attribute("data-sku")),
                                        "title" to WorkflowStep.ExtractRows.Column(css("h3")),
                                    ),
                            ),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val script = controller.evaluatedScripts.single()
            // Both column locators must be scoped to `r`. An XPath column resolved against
            // `document` would report the first row's value for every row — plausible-looking and
            // completely wrong.
            assertTrue(",r,null,9,null" in script, "xpath column not scoped to the row: $script")
            assertTrue("r.querySelector" in script, "css column not scoped to the row: $script")
            assertTrue(".slice(0,5)" in script, "limit not applied: $script")

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            // A JSON array, kept as JSON: there is no more faithful way to put a table in a string.
            assertEquals(
                """[{"sku":"KB-1","title":"One"},{"sku":"KB-2","title":"Two"}]""",
                completed.variables["results"],
            )
        }

    @Test
    fun an_attribute_source_survives_a_locator_that_selected_an_attribute_node() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "\"\"" } }
            val workflow =
                Workflow(
                    id = "wf-attrnode",
                    name = "attr-node",
                    steps =
                        listOf(
                            WorkflowStep.Extract(
                                xpath(".//h3/@data-full-title"),
                                into = "title",
                                from = WorkflowStep.Extract.Source.Attribute("whatever"),
                            ),
                        ),
                )

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // An Attr node has no getAttribute. The optional *call* keeps that from throwing, so a
            // mismatched locator/source pair yields "" rather than failing the whole run.
            assertTrue("getAttribute?." in controller.evaluatedScripts.single())
            assertIs<WorkflowEvent.Completed>(events.last())
        }
}
