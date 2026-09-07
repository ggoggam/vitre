package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.frame.LaneSource
import dev.ggoggam.vitre.core.testing.FakeWebViewController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a fan-out does with its items, on a single WebView where the items run one after another.
 *
 * Everything about *where* items run — several lanes, the parent giving its lane up, the pool of
 * one that still finishes — is in `FanOutLaneTest`. This file is about the step's contract: what
 * the body sees, what it leaves behind, and how it reports.
 */
class ForEachStepTest {
    private val results =
        """[{"sku":"a1","title":"Alpha","url":"https://shop.test/p/a1"},""" +
            """{"sku":"b2","title":"Beta","url":"https://shop.test/p/b2"},""" +
            """{"sku":"c3","title":"Gamma","url":"https://shop.test/p/c3"}]"""

    /** A controller whose first `ExtractRows` answers with [results] and whose extracts answer with the page's URL. */
    private fun controller() =
        FakeWebViewController().apply {
            nextEvalResult = { script ->
                when {
                    script.startsWith("(function(){return") -> results
                    script == "location.href" -> "\"${navigations.lastOrNull() ?: "about:blank"}\""
                    else -> "\"price of ${navigations.last().substringAfterLast('/')}\""
                }
            }
        }

    private fun searchThenVisit(limit: Int = 20) =
        workflow("wf-each", "search then visit") {
            navigate("https://shop.test/search")
            extractRows(rows = css("li"), into = "results") { column("title", css("h3")) }
            forEach(over = "results", item = "product", into = "details", limit = limit) {
                navigate(template("{product.url}"))
                extract("#price", into = "price")
            }
        }

    @Test
    fun runs_the_body_once_per_item_with_the_item_bound() =
        runTest {
            val controller = controller()

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(searchThenVisit()).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(
                listOf(
                    "https://shop.test/search",
                    "https://shop.test/p/a1",
                    "https://shop.test/p/b2",
                    "https://shop.test/p/c3",
                ),
                controller.navigations,
            )
        }

    @Test
    fun stores_one_result_per_item_in_item_order_with_what_the_body_set() =
        runTest {
            val events = WorkflowEngine(controller(), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val details = assertIs<WorkflowEvent.Completed>(events.last()).decode<List<FanOutResult>>("details")
            assertEquals(listOf(0, 1, 2), details.map { it.index })
            assertEquals(
                listOf("a1", "b2", "c3"),
                details.map {
                    it.item.jsonObject
                        .getValue("sku")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                listOf("price of a1", "price of b2", "price of c3"),
                details.map { it.variables.getValue("price") },
            )
            assertTrue(details.all { it.error == null }, "no item failed: $details")
            // The body's own variables only. The bindings and the parent's `results` are the
            // caller's already, and repeating them per item would triple the payload for nothing.
            assertEquals(setOf("price"), details.flatMap { it.variables.keys }.toSet())
        }

    /** Items do not see each other, and the parent does not see them by name — only through `into`. */
    @Test
    fun item_variables_do_not_leak_into_the_parent_or_each_other() =
        runTest {
            val events = WorkflowEngine(controller(), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val variables = assertIs<WorkflowEvent.Completed>(events.last()).variables
            assertEquals(setOf("results", "details"), variables.keys)
        }

    @Test
    fun the_body_sees_the_parents_variables() =
        runTest {
            val controller = controller()
            val workflow =
                workflow("wf-each", "inherit") {
                    navigate("https://shop.test/search")
                    extract("#currency", into = "currency")
                    extractRows(rows = css("li"), into = "results") { column("title", css("h3")) }
                    forEach(over = "results", item = "product", into = "details") {
                        navigate(template("{product.url}?cur={currency}"))
                    }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals("https://shop.test/p/a1?cur=price of search", controller.navigations[1])
        }

    @Test
    fun a_primitive_item_binds_under_the_item_name_alone() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { script -> if (script.contains("urls")) "[\"https://a.test\",\"https://b.test\"]" else "null" }
                }
            val workflow =
                workflow("wf-each", "primitives") {
                    evaluateJs("urls()", into = "urls")
                    forEach(over = "urls", item = "url", into = "visited") {
                        navigate(template("{url}"))
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://a.test", "https://b.test"), controller.navigations)
            val visited = assertIs<WorkflowEvent.Completed>(events.last()).decode<List<FanOutResult>>("visited")
            assertEquals(listOf(JsonPrimitive("https://a.test"), JsonPrimitive("https://b.test")), visited.map { it.item })
        }

    @Test
    fun an_object_item_binds_the_whole_element_and_each_field() =
        runTest {
            val bound = mutableMapOf<String, String>()
            bound.bindItem(
                "p",
                buildJsonObject {
                    put("title", JsonPrimitive("Alpha"))
                    put("stars", JsonPrimitive(4))
                },
            )

            assertEquals("Alpha", bound["p.title"])
            // Not a string, so it keeps its JSON form — the rule Extract follows for a script result.
            assertEquals("4", bound["p.stars"])
            assertEquals("""{"title":"Alpha","stars":4}""", bound["p"])
        }

    /**
     * The contract the step is for: twenty product pages and one bot check is the normal case, and
     * throwing away nineteen answers over the twentieth is the wrong tool for that job.
     */
    @Test
    fun a_failing_item_is_recorded_and_its_siblings_still_run() =
        runTest {
            val controller =
                controller().apply {
                    onNavigate = { url -> if (url.endsWith("b2")) throw IllegalStateException("bot check") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(searchThenVisit()).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            val details = completed.decode<List<FanOutResult>>("details")
            assertEquals(listOf(null, "bot check", null), details.map { it.error })
            assertEquals("price of a1", details[0].variables["price"])
            assertEquals("price of c3", details[2].variables["price"])
            assertNull(details[1].variables["price"])
            // Every item was attempted, the failed one included.
            assertEquals(4, controller.navigations.size)
            // And the failure is in the stream, wrapped, against the body step that threw.
            val failed =
                events
                    .filterIsInstance<WorkflowEvent.FanOutItem>()
                    .single { it.event is WorkflowEvent.Failed }
            assertEquals(1, failed.index)
            assertEquals("2.each.0", (failed.event as WorkflowEvent.Failed).path.toString())
        }

    @Test
    fun a_missing_variable_fails_the_step_and_names_what_was_set() =
        runTest {
            val controller = controller()
            val workflow =
                workflow("wf-each", "typo") {
                    navigate("https://shop.test/search")
                    extractRows(rows = css("li"), into = "results") { column("title", css("h3")) }
                    forEach(over = "result", item = "product", into = "details") {
                        navigate(template("{product.url}"))
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(StepPath.root(2), failed.path)
            assertContains(failed.message, "No variable `result`")
            assertContains(failed.message, "results")
            assertEquals(1, controller.navigations.size, "no item should have run")
        }

    @Test
    fun a_variable_that_is_not_an_array_fails_the_step() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "\"just text\"" } }
            val workflow =
                workflow("wf-each", "not an array") {
                    extract("#title", into = "title")
                    forEach(over = "title", item = "t", into = "out") { navigate("https://never.test") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val failed = assertIs<WorkflowEvent.Failed>(events.last())
            assertEquals(StepPath.root(1), failed.path)
            assertContains(failed.message, "does not hold a JSON array")
            assertTrue(controller.navigations.isEmpty())
        }

    @Test
    fun limit_caps_the_items() =
        runTest {
            val controller = controller()

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(searchThenVisit(limit = 2)).toList()

            assertEquals(3, controller.navigations.size)
            val details = assertIs<WorkflowEvent.Completed>(events.last()).decode<List<FanOutResult>>("details")
            assertEquals(2, details.size)
        }

    @Test
    fun an_empty_array_runs_nothing_and_stores_an_empty_result() =
        runTest {
            val controller = FakeWebViewController().apply { nextEvalResult = { "[]" } }
            val workflow =
                workflow("wf-each", "empty") {
                    evaluateJs("[]", into = "nothing")
                    forEach(over = "nothing", item = "x", into = "out") { navigate("https://never.test") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            val completed = assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("[]", completed.variables["out"])
            assertTrue(controller.navigations.isEmpty())
        }

    /** The shape a timeline is drawn from: item events wrapped, the step bracketing all of them. */
    @Test
    fun item_events_arrive_wrapped_with_index_and_count_between_the_steps_own_events() =
        runTest {
            val events = WorkflowEngine(controller(), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val stepPath = StepPath.root(2)
            val started = events.indexOf(WorkflowEvent.StepStarted(stepPath, searchThenVisit().steps[2]))
            val completed = events.indexOf(WorkflowEvent.StepCompleted(stepPath))
            val items = events.filterIsInstance<WorkflowEvent.FanOutItem>()
            assertTrue(items.isNotEmpty())
            assertTrue(items.all { events.indexOf(it) in (started + 1) until completed }, "item events outside the step's bracket")
            assertEquals(setOf(0, 1, 2), items.map { it.index }.toSet())
            assertTrue(items.all { it.count == 3 && it.path == stepPath })
            // On a single WebView the items ran in order, one at a time — each one leased, ran its
            // body, and completed before the next leased.
            val firsts = items.filter { it.event is WorkflowEvent.LaneLeased }.map { it.index }
            assertEquals(listOf(0, 1, 2), firsts)
            assertTrue(items.all { it.laneId == LaneSource.SOLO_LANE_ID })
            val innerStarted =
                items.filter { it.event is WorkflowEvent.StepStarted }.map {
                    (it.event as WorkflowEvent.StepStarted)
                        .path
                        .toString()
                }
            assertEquals(listOf("2.each.0", "2.each.1", "2.each.0", "2.each.1", "2.each.0", "2.each.1"), innerStarted)
            assertEquals(3, items.count { it.event is WorkflowEvent.Completed })
        }

    /** An item's `Completed` carries what that item set, so a caller can read results as they land. */
    @Test
    fun an_items_completed_event_carries_its_own_variables() =
        runTest {
            val events = WorkflowEngine(controller(), EmptyCoroutineContext).run(searchThenVisit()).toList()

            val first =
                events
                    .filterIsInstance<WorkflowEvent.FanOutItem>()
                    .first { it.index == 0 && it.event is WorkflowEvent.Completed }
            val variables = (first.event as WorkflowEvent.Completed).variables
            assertEquals("price of a1", variables["price"])
            assertEquals("Alpha", variables["product.title"])
        }

    /** After the fan-out the workflow is back on a lane, and can go on. */
    @Test
    fun steps_after_the_fan_out_run_and_see_its_result() =
        runTest {
            val controller = controller()
            val workflow =
                workflow("wf-each", "after") {
                    navigate("https://shop.test/search")
                    extractRows(rows = css("li"), into = "results") { column("title", css("h3")) }
                    forEach(over = "results", item = "product", into = "details") {
                        navigate(template("{product.url}"))
                    }
                    runIf(variableMatches("details", "\"error\":null")) { navigate("https://done.test") }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals("https://done.test", controller.navigations.last())
            // A fresh lease for the segment after the barrier, on top of the run's own and one per item.
            assertEquals(2, events.count { it is WorkflowEvent.LaneLeased })
        }

    @Test
    fun fan_outs_nest() =
        runTest {
            val controller =
                FakeWebViewController().apply {
                    nextEvalResult = { script ->
                        when {
                            script.contains("outer") -> "[{\"name\":\"x\"},{\"name\":\"y\"}]"
                            script.contains("inner") -> "[1,2]"
                            else -> "null"
                        }
                    }
                }
            val workflow =
                workflow("wf-each", "nested") {
                    evaluateJs("outer()", into = "groups")
                    forEach(over = "groups", item = "g", into = "perGroup") {
                        evaluateJs("inner()", into = "members")
                        forEach(over = "members", item = "m", into = "perMember") {
                            navigate(template("https://t.test/{g.name}/{m}"))
                        }
                    }
                }

            val events = WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertIs<WorkflowEvent.Completed>(events.last())
            assertEquals(
                listOf("https://t.test/x/1", "https://t.test/x/2", "https://t.test/y/1", "https://t.test/y/2"),
                controller.navigations,
            )
            val deepest = events.filterIsInstance<WorkflowEvent.FanOutItem>().mapNotNull { it.event as? WorkflowEvent.FanOutItem }.first()
            assertEquals("1.each.1", deepest.path.toString())
        }

    @Test
    fun the_body_can_read_the_item_in_a_condition() =
        runTest {
            val controller = controller()
            val workflow =
                workflow("wf-each", "conditional body") {
                    navigate("https://shop.test/search")
                    extractRows(rows = css("li"), into = "results") { column("title", css("h3")) }
                    forEach(over = "results", item = "product", into = "details") {
                        runIf(variableEquals("product.sku", "b2")) { navigate(template("{product.url}")) }
                    }
                }

            WorkflowEngine(controller, EmptyCoroutineContext).run(workflow).toList()

            assertEquals(listOf("https://shop.test/search", "https://shop.test/p/b2"), controller.navigations)
        }
}
