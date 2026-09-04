package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a [Template] does once the engine runs the step holding it. */
class TemplateStepTest {
    @Test
    fun navigate_fills_its_url_in_from_variables_an_earlier_step_extracted() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "\"B07XYZ\"" }
            val workflow =
                workflow("wf", "templated navigate") {
                    extract("#sku", into = "sku")
                    navigate(template("https://shop.test/p/{sku}"))
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(listOf("https://shop.test/p/B07XYZ"), controller.navigations)
        }

    @Test
    fun input_fills_its_text_in_and_still_escapes_it_for_javascript() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "\"say \\\"hi\\\"\"" }
            val workflow =
                workflow("wf", "templated input") {
                    extract("#seed", into = "seed")
                    input("#q", template("{seed}!"))
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // The resolved value goes through `jsString` exactly as a literal would — a variable
            // holding a quote must not be able to close the string it is interpolated into.
            val typed = controller.evaluatedScripts.last()
            assertTrue(typed.contains("""el.value="say \"hi\"!""""), typed)
        }

    @Test
    fun a_plain_string_is_never_interpolated() =
        runTest {
            val controller = FakeWebViewController()
            val workflow = workflow("wf", "literal braces") { navigate("https://shop.test/p/{sku}") }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            // No variable `sku` is set, and the step still succeeds: a bare String is a literal, so
            // the braces reach the page untouched rather than failing or resolving.
            assertEquals(listOf("https://shop.test/p/{sku}"), controller.navigations)
        }

    @Test
    fun a_template_naming_an_unset_variable_fails_the_step_and_says_what_was_set() =
        runTest {
            val controller = FakeWebViewController()
            controller.nextEvalResult = { "\"value\"" }
            val workflow =
                workflow("wf", "typo") {
                    extract("#sku", into = "sku")
                    navigate(template("https://shop.test/p/{skew}"))
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(StepPath.root(1), failed.path)
            assertTrue(failed.message.contains("skew"), failed.message)
            assertTrue(failed.message.contains("sku"), failed.message)
            // Nothing was navigated to: an unset name is a failure rather than an empty string, so
            // the run stops instead of loading `https://shop.test/p/`.
            assertEquals(emptyList(), controller.navigations)
        }
}
