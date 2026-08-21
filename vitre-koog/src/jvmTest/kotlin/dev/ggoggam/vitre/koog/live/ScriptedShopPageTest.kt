package dev.ggoggam.vitre.koog.live

import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.session.WebViewSessions
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.handle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same sequence [LiveModelDrivesThePageTest] hopes a model will choose, chosen by hand.
 *
 * This runs in `mise run test` and the live one does not, which is the point: when the live test
 * fails, this says whether the page under it still works. A failure here is Vitre's or the fake's;
 * a failure there with this green is the model's or the descriptors'.
 */
class ScriptedShopPageTest {
    private fun driver(
        page: ScriptedShopPage,
        scope: kotlinx.coroutines.CoroutineScope,
    ): PageDriver {
        val sessions = WebViewSessions()
        sessions.register("main", page, "the shop tab")
        return PageDriver(sessions, scope, engineContext = EmptyCoroutineContext)
    }

    @Test
    fun the_page_answers_a_snapshot_with_handles_that_then_resolve() =
        runTest {
            val page = ScriptedShopPage()
            val snapshot = driver(page, this).snapshot()

            assertEquals(ScriptedShopPage.TITLE, snapshot.title)
            val search = snapshot.nodes.single { it.role == "searchbox" }
            val button = snapshot.nodes.single { it.role == "button" }
            // Handles, not selectors — the thing PageToolDocs tells a model to prefer.
            assertTrue(search.ref.startsWith("e"), search.ref)
            assertTrue(button.ref != search.ref)
            // No results before the search runs, so a model cannot answer without pressing Search.
            assertTrue(snapshot.nodes.none { it.name.contains("Wireless") }, "${snapshot.nodes}")
        }

    @Test
    fun typing_and_clicking_change_what_a_later_read_sees() =
        runTest {
            val page = ScriptedShopPage()
            val driver = driver(page, this)
            val before = driver.snapshot()
            val search = before.nodes.single { it.role == "searchbox" }
            val button = before.nodes.single { it.role == "button" }

            driver.input(handle(search.ref), "wireless keyboard")
            // The `value` *property*, which is what PageToolDocs tells a model to read back after
            // typing — the attribute would still hold the markup's original value.
            val typed = driver.extract(handle(search.ref), from = WorkflowStep.Extract.Source.Property("value"))
            assertEquals("wireless keyboard", typed)
            assertFalse(page.searched)

            driver.click(handle(button.ref))
            assertTrue(page.searched)

            // Refs survive: the page mutated in place rather than navigating, so the handles the
            // first snapshot issued still resolve — as they would in a real document.
            val after = driver.snapshot()
            assertEquals(search.ref, after.nodes.single { it.role == "searchbox" }.ref)
            assertTrue(after.nodes.any { it.name == "Borel Wireless Keyboard" }, "${after.nodes}")
        }

    @Test
    fun the_results_only_give_up_the_answer_to_something_that_reads_all_three_columns() =
        runTest {
            val page = ScriptedShopPage()
            val driver = driver(page, this)
            val snapshot = driver.snapshot()
            driver.input(handle(snapshot.nodes.single { it.role == "searchbox" }.ref), "wireless keyboard")
            driver.click(handle(snapshot.nodes.single { it.role == "button" }.ref))

            val json =
                driver.extractRows(
                    css(".result"),
                    mapOf(
                        "name" to WorkflowStep.ExtractRows.Column(css(".name")),
                        "price" to WorkflowStep.ExtractRows.Column(css(".price")),
                        "stock" to WorkflowStep.ExtractRows.Column(css(".stock")),
                    ),
                )

            // `extractRows` hands back the JSON a model would read, so the test reads it the same way.
            val rows =
                Json.parseToJsonElement(json).jsonArray.map { row ->
                    row.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
                }

            assertEquals(3, rows.size, json)
            val answer =
                rows
                    .filter { "Wireless" in it.getValue("name") && it.getValue("stock") == "In stock" }
                    .minBy { it.getValue("price").removePrefix("$").toDouble() }
            assertEquals(page.cheapestWirelessInStock.price, answer["price"], json)
            // The two traps are on the page and are not the answer.
            assertTrue(rows.any { it["price"] == "$39.99" }, json)
            assertTrue(rows.any { it["price"] == "$54.50" && it["stock"] == "Out of stock" }, json)
        }

    @Test
    fun a_script_the_page_does_not_understand_fails_instead_of_answering_null() =
        runTest {
            val page = ScriptedShopPage()
            val thrown = runCatching { page.evaluateJs("document.readyState") }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException, "$thrown")
            assertTrue("does not know this script" in thrown.message.orEmpty(), "${thrown.message}")
        }
}
