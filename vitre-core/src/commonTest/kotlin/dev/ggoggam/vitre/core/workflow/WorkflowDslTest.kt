package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.workflow.WorkflowStep.Extract.Source
import dev.ggoggam.vitre.core.workflow.WorkflowStep.ExtractRows.Column
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The DSL is sugar, so what these assert is that it is *only* sugar: every block below is compared
 * against the constructor call it replaces, and the two must be equal. `Workflow` and every step
 * are data classes, so equality here covers the step list, its order, and every argument.
 */
class WorkflowDslTest {
    @Test
    fun dsl_builds_the_same_workflow_as_the_constructors() {
        val built =
            workflow("hn-top-story", "Hacker News top story") {
                navigate("https://news.ycombinator.com/")
                waitFor(".titleline > a", timeoutMs = 15_000)
                extract(".titleline > a", into = "headline")
                extract(".titleline > a", into = "url", from = Source.Attribute("href"))
            }

        assertEquals(
            Workflow(
                id = "hn-top-story",
                name = "Hacker News top story",
                steps =
                    listOf(
                        WorkflowStep.Navigate("https://news.ycombinator.com/"),
                        WorkflowStep.WaitFor(".titleline > a", timeoutMs = 15_000),
                        WorkflowStep.Extract(selector = ".titleline > a", into = "headline"),
                        WorkflowStep.Extract(".titleline > a", into = "url", from = Source.Attribute("href")),
                    ),
            ),
            built,
        )
    }

    /** A bare string means CSS in the DSL exactly as it does in the constructors. */
    @Test
    fun string_overloads_mean_css() {
        val built =
            workflow("locators", "locator forms") {
                waitFor("#ping")
                waitFor(xpath("//li[@data-sku]"))
                click("#ping")
                click(handle("e4"))
                input("#subject", text = "typed")
                input(handle("e3"), text = "typed by handle")
            }

        assertEquals(
            listOf(
                WorkflowStep.WaitFor(css("#ping")),
                WorkflowStep.WaitFor(xpath("//li[@data-sku]")),
                WorkflowStep.Click(css("#ping")),
                WorkflowStep.Click(handle("e4")),
                WorkflowStep.Input(css("#subject"), "typed"),
                WorkflowStep.Input(handle("e3"), "typed by handle"),
            ),
            built.steps,
        )
    }

    @Test
    fun every_step_has_a_function() {
        val built =
            workflow("all-steps", "one of each") {
                navigate("https://example.com")
                loadHtml("<p>hi</p>", baseUrl = "https://fixture.test/")
                waitFor("#a", timeoutMs = 1_000)
                click("#b")
                input("#c", text = "typed")
                extract("#d", into = "text")
                extractRows(rows = xpath("//li"), into = "rows", limit = 3) {
                    column("sku", xpath("."), from = Source.Attribute("data-sku"))
                }
                snapshot(into = "page", maxNodes = 10, nameLimit = 20)
                evaluateJs("1+1", into = "sum")
                awaitMessage(type = "pong", into = "fromPage", timeoutMs = 2_000)
                postMessage("""{"id":"ack-1","type":"ack"}""")
                step(WorkflowStep.Navigate("https://example.org"))
            }

        assertEquals(
            listOf(
                WorkflowStep.Navigate("https://example.com"),
                WorkflowStep.LoadHtml("<p>hi</p>", "https://fixture.test/"),
                WorkflowStep.WaitFor("#a", timeoutMs = 1_000),
                WorkflowStep.Click("#b"),
                WorkflowStep.Input("#c", "typed"),
                WorkflowStep.Extract("#d", into = "text"),
                WorkflowStep.ExtractRows(
                    rows = xpath("//li"),
                    columns = mapOf("sku" to Column(xpath("."), Source.Attribute("data-sku"))),
                    into = "rows",
                    limit = 3,
                ),
                WorkflowStep.Snapshot(into = "page", maxNodes = 10, nameLimit = 20),
                WorkflowStep.EvaluateJs("1+1", into = "sum"),
                WorkflowStep.AwaitMessage(type = "pong", into = "fromPage", timeoutMs = 2_000),
                WorkflowStep.PostMessage("""{"id":"ack-1","type":"ack"}"""),
                WorkflowStep.Navigate("https://example.org"),
            ),
            built.steps,
        )
    }

    /**
     * Column order is the record's field order, and it is the one thing a map literal makes easy to
     * lose. Equality on the step would pass whatever the order, so this reads the keys back.
     */
    @Test
    fun columns_keep_the_order_they_were_declared_in() {
        val built =
            workflow("rows", "column order") {
                extractRows(rows = xpath("//li[@data-sku]"), into = "results") {
                    column("sku", xpath("."), from = Source.Attribute("data-sku"))
                    column("title", xpath(".//h3/@data-full-title"))
                    column("price", xpath(".//span[@class='price']"))
                    column("seller", xpath(".//span[@class='seller']"))
                }
            }

        val rows = built.steps.single() as WorkflowStep.ExtractRows
        assertEquals(listOf("sku", "title", "price", "seller"), rows.columns.keys.toList())
    }

    @Test
    fun a_repeated_column_name_fails_rather_than_overwriting() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                workflow("rows", "duplicate column") {
                    extractRows(rows = xpath("//li"), into = "results") {
                        column("price", xpath(".//span[@class='price']"))
                        column("price", xpath(".//span[@class='sale-price']"))
                    }
                }
            }

        assertEquals("Duplicate column `price`", failure.message)
    }

    /**
     * The block runs to completion before the engine ever sees it, so Kotlin control flow inside it
     * decides what the workflow *contains*. Worth pinning down, because the shape invites reading it
     * as a branch the engine takes.
     */
    @Test
    fun control_flow_in_the_block_runs_at_build_time() {
        fun build(loggedIn: Boolean) =
            workflow("conditional", "conditional") {
                if (!loggedIn) click("#login")
                navigate("https://example.com/account")
            }

        assertEquals(2, build(loggedIn = false).steps.size)
        assertEquals(listOf(WorkflowStep.Navigate("https://example.com/account")), build(loggedIn = true).steps)
    }

    @Test
    fun for_each_builds_the_same_step_as_the_constructor() {
        val built =
            workflow("wf", "fan-out") {
                forEach(over = "results", item = "product", into = "details", limit = 5) {
                    navigate(template("{product.url}"))
                    extract("#price", into = "price")
                }
            }

        assertEquals(
            listOf<WorkflowStep>(
                WorkflowStep.ForEach(
                    over = "results",
                    item = "product",
                    into = "details",
                    body =
                        listOf(
                            WorkflowStep.Navigate(template("{product.url}")),
                            WorkflowStep.Extract(selector = "#price", into = "price"),
                        ),
                    limit = 5,
                ),
            ),
            built.steps,
        )
    }

    /** A body that binds its item to nothing, or to a name no template could read, is a build-time typo. */
    @Test
    fun for_each_rejects_an_item_name_a_template_could_not_read() {
        assertFailsWith<IllegalArgumentException> {
            workflow("wf", "bad") { forEach(over = "results", item = "", into = "out") {} }
        }
        assertFailsWith<IllegalArgumentException> {
            workflow("wf", "bad") { forEach(over = "results", item = "my item", into = "out") {} }
        }
        assertFailsWith<IllegalArgumentException> {
            workflow("wf", "bad") { forEach(over = "results", item = "p", into = "out", limit = 0) {} }
        }
    }
}
