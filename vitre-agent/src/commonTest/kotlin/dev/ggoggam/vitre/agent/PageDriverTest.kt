package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.agent.session.SessionLeases
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.describe
import dev.ggoggam.vitre.core.workflow.handle
import dev.ggoggam.vitre.core.workflow.xpath
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "The rules an agent has to be told about are enforced once, here, not once per protocol."
 *
 * These are the checks that run before a step ever reaches a WebView, so they need no controller —
 * which is the point. Both adapters get them by construction, and the failure messages they produce
 * are the ones a model reads.
 */
class PageDriverTest {
    private fun driver(scope: kotlinx.coroutines.CoroutineScope) =
        PageDriver(WebViewSessions(), SessionLeases(scope), engineContext = kotlin.coroutines.EmptyCoroutineContext)

    @Test
    fun naming_an_element_two_ways_is_refused_rather_than_resolved_to_one_of_them() {
        val failure = assertFailsWith<PageDriverException> { locatorFrom(ref = "e2", css = "#go") }

        // Preferring one silently would act on an element the caller did not choose, and neither the
        // caller nor the model would ever find out which.
        assertTrue("exactly one" in failure.message, failure.message)
        assertTrue("ref" in failure.message && "css" in failure.message, failure.message)
    }

    @Test
    fun naming_no_element_at_all_points_at_the_snapshot() {
        val failure = assertFailsWith<PageDriverException> { locatorFrom() }

        // The recovery an agent needs is not "you missed an argument" but "go and look at the page",
        // because the reason it named nothing is usually that it has nothing to name.
        assertTrue("snapshot" in failure.message, failure.message)
    }

    @Test
    fun the_prefix_names_the_arguments_the_caller_actually_sent() {
        val failure = assertFailsWith<PageDriverException> { locatorFrom(prefix = "rows_") }

        // A tool with two locators in one call has to say which one is wrong. "Give exactly one of
        // ref, css or xpath" on a call that also takes `css` is advice the model cannot act on.
        assertTrue("rows_css" in failure.message, failure.message)
    }

    @Test
    fun each_locator_kind_survives_the_round_trip() {
        assertEquals(handle("e7"), locatorFrom(ref = "e7"))
        assertEquals(css("#go"), locatorFrom(css = "#go"))
        assertEquals(xpath("//li"), locatorFrom(xpath = "//li"))
    }

    @Test
    fun reading_an_attribute_without_saying_which_is_caught_with_the_reason() {
        val failure = assertFailsWith<PageDriverException> { extractSourceFrom("attribute", null) }
        assertTrue("name" in failure.message, failure.message)

        // The distinction is not pedantry: typing into a field assigns the DOM *property*, and the
        // `value` attribute still holds what the markup shipped with.
        assertEquals(WorkflowStep.Extract.Source.Property("value"), extractSourceFrom("property", "value"))
        assertEquals(WorkflowStep.Extract.Source.Text, extractSourceFrom(null, null))
    }

    @Test
    fun a_source_that_is_none_of_the_three_says_what_the_three_are() {
        val failure = assertFailsWith<PageDriverException> { extractSourceFrom("innerHTML", null) }
        assertTrue("attribute" in failure.message && "property" in failure.message, failure.message)
    }

    @Test
    fun a_column_addressed_by_handle_is_refused_before_it_can_repeat_itself() =
        runTest {
            val failure =
                assertFailsWith<PageDriverException> {
                    driver(this).extractRows(
                        rows = css(".product"),
                        columns = mapOf("sku" to WorkflowStep.ExtractRows.Column(handle("e3"))),
                    )
                }

            // A handle names one element in the whole document, so every record would carry the same
            // value — a table that looks right and is wrong in every row but one.
            assertTrue("every row" in failure.message, failure.message)
        }

    @Test
    fun a_column_that_searches_from_the_document_root_is_refused_with_the_fix() =
        runTest {
            val failure =
                assertFailsWith<PageDriverException> {
                    driver(this).extractRows(
                        rows = css(".product"),
                        columns = mapOf("price" to WorkflowStep.ExtractRows.Column(xpath("//span[@class='price']"))),
                    )
                }

            // The quiet one. `//` searches the whole document from any context node, so every record
            // repeats the first row's price.
            assertTrue(".//" in failure.message, failure.message)
            assertTrue("price" in failure.message, failure.message)
        }

    @Test
    fun a_table_with_no_columns_is_a_table_of_nothing() =
        runTest {
            val failure =
                assertFailsWith<PageDriverException> {
                    driver(this).extractRows(rows = css(".product"), columns = emptyMap())
                }
            assertTrue("column" in failure.message, failure.message)
        }

    @Test
    fun validation_happens_before_a_session_is_even_resolved() =
        runTest {
            // No sessions are registered on this driver at all. A caller that gets
            // "no WebView sessions are registered" for a malformed column has been told about the
            // wrong problem, and will go looking in the wrong place.
            val failure =
                assertFailsWith<PageDriverException> {
                    driver(this).extractRows(
                        rows = css(".product"),
                        columns = mapOf("price" to WorkflowStep.ExtractRows.Column(xpath("//span"))),
                    )
                }
            assertTrue(".//" in failure.message, failure.message)
        }

    @Test
    fun a_locator_describes_itself_in_the_language_it_will_be_read_as() {
        // "No match for //h2[@aria-label]" is only actionable if you know it was not read as CSS.
        val locators = listOf<Locator>(css("#a"), xpath("//a"), handle("e1"))
        assertEquals(listOf("css `#a`", "xpath `//a`", "handle `e1`"), locators.map { it.describe() })
    }
}
