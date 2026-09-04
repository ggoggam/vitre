package dev.ggoggam.vitre.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateTest {
    @Test
    fun a_pattern_with_no_placeholders_is_the_same_value_a_plain_string_produces() {
        // The collapse matters beyond tidiness: steps are compared by value, so two spellings of
        // the same constant text must not make two workflows that are `!=` to each other.
        assertEquals(Template.Literal("https://example.com"), template("https://example.com"))
        assertEquals(WorkflowStep.Navigate("https://example.com"), WorkflowStep.Navigate(template("https://example.com")))
    }

    @Test
    fun a_pattern_that_is_only_a_placeholder_collapses_to_the_variable() {
        assertEquals(Template.Variable("sku"), template("{sku}"))
    }

    @Test
    fun placeholders_and_text_interleave_in_order() {
        assertEquals(
            Template.Parts(
                listOf(
                    Template.Literal("https://shop.test/p/"),
                    Template.Variable("sku"),
                    Template.Literal("?ref="),
                    Template.Variable("campaign"),
                ),
            ),
            template("https://shop.test/p/{sku}?ref={campaign}"),
        )
    }

    @Test
    fun doubled_braces_are_literal_braces() {
        assertEquals(Template.Literal("{}"), template("{{}}"))
        assertEquals(
            Template.Parts(listOf(Template.Literal("{"), Template.Variable("a"), Template.Literal("}"))),
            template("{{{a}}}"),
        )
    }

    @Test
    fun dotted_names_are_allowed_so_a_fan_out_can_bind_item_fields() {
        assertEquals(Template.Variable("product.path"), template("{product.path}"))
    }

    @Test
    fun a_malformed_pattern_fails_while_the_workflow_is_being_built() {
        // Build time, not run time: this is a typo in a program, and the line number is the point.
        assertTrue(assertFailsWith<IllegalArgumentException> { template("/p/{sku") }.message!!.contains("Unclosed"))
        assertTrue(assertFailsWith<IllegalArgumentException> { template("/p/sku}") }.message!!.contains("Unmatched"))
        assertTrue(assertFailsWith<IllegalArgumentException> { template("/p/{}") }.message!!.contains("names no variable"))
        assertTrue(assertFailsWith<IllegalArgumentException> { template("/p/{a b}") }.message!!.contains("not a variable name"))
    }

    @Test
    fun describe_round_trips_through_template() {
        for (pattern in listOf("https://shop.test/p/{sku}", "{a}{b}", "plain text", "{{literal}}", "{{{a}}}")) {
            assertEquals(template(pattern), template(template(pattern).describe()), "round trip of `$pattern`")
        }
    }

    @Test
    fun variableNames_reports_what_a_template_reads_in_order() {
        assertEquals(listOf("sku", "campaign"), template("/p/{sku}?ref={campaign}").variableNames())
        assertEquals(emptyList(), template("nothing here").variableNames())
    }
}
