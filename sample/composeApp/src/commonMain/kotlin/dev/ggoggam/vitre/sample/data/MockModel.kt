package dev.ggoggam.vitre.sample.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A model that is not a model.
 *
 * The sample calls no LLM API. What it wants to demonstrate is the *other* half of an agent — the
 * tool loop, the MCP round trip, a page being driven by something that was not written against it —
 * and putting a real inference call in the middle would make that half depend on a key, a network,
 * and a bill, none of which say anything about this library.
 *
 * So the decisions are scripted and **the answers are not**. Each scripted exchange fixes which
 * tools get called and in what order; every number, name and handle in the reply is read back out
 * of what the tools actually returned from the live page. Change the fixture's prices and the
 * "cheapest in stock" answer changes with them; break the snapshot walk and the exchange that reads
 * a handle out of it stops working. That is the property worth having in a sample — a canned
 * transcript would keep passing while the library underneath it rotted.
 *
 * The one thing it cannot show is a model *choosing* a tool. Everything after that choice is real.
 */
class MockModel : AgentModel {
    override suspend fun next(
        prompt: String,
        soFar: List<ToolOutcome>,
    ): AssistantTurn {
        // Stands in for the round trip that is not happening. Without it every turn of a multi-step
        // exchange lands in the same frame and the pane just fills up, which hides the thing the
        // screen exists to show — that this is a loop.
        delay(THINKING_MS)
        val exchange = EXCHANGES.firstOrNull { it.matches(prompt.lowercase()) } ?: return offMenu()
        // Every scripted step issues at most one call, which is what makes the number of results so
        // far the index of the step to run next.
        val step = exchange.steps.getOrNull(soFar.size) ?: return AssistantTurn(text = OVERRUN)
        return step(soFar)
    }

    private fun offMenu(): AssistantTurn =
        AssistantTurn(
            text =
                "I am a scripted stand-in for a model, so I only know a few questions about this " +
                    "page — the tools and the WebView underneath me are real, but the choice of " +
                    "which tool to call is not. Try one of:\n\n" +
                    SUGGESTIONS.joinToString("\n") { "• $it" },
        )

    companion object {
        /** The prompts the scripts recognise, offered in the UI so nobody has to guess them. */
        val SUGGESTIONS: List<String> =
            listOf(
                "What is on this page?",
                "Which keyboard is the cheapest one that is actually in stock?",
                "How much is the Logitech?",
                "How many product rows are there?",
            )

        private const val THINKING_MS = 450L
        private const val OVERRUN = "I have run out of scripted steps for that question."
    }
}

// ── The scripts ────────────────────────────────────────────────────────────────────────────────

/**
 * One question the mock knows, as the sequence of decisions a model would make answering it.
 *
 * A step reads every result so far and returns the next turn, which is the same information a real
 * model has: the conversation, including what the tools said. It is also why the steps below can
 * pull a handle out of a snapshot rather than hard-coding one.
 */
private class Exchange(
    val matches: (String) -> Boolean,
    val steps: List<(List<ToolOutcome>) -> AssistantTurn>,
)

/** A turn that asks for one tool. The id is what a real API pairs the result back against. */
private fun call(
    text: String,
    name: String,
    arguments: JsonObject = JsonObject(emptyMap()),
    hop: Int,
): AssistantTurn = AssistantTurn(text, listOf(ToolCall(id = "toolu_${hop}_$name", name = name, input = arguments)))

private fun answer(text: String): AssistantTurn = AssistantTurn(text = text)

private val EXCHANGES: List<Exchange> =
    listOf(
        // ── Look before you act ────────────────────────────────────────────────────────────────
        Exchange(
            matches = { it.contains("what is on") || it.contains("what's on") || it.contains("look") || it.contains("describe") },
            steps =
                listOf(
                    {
                        call(
                            "I have not seen this page, so I will not guess at its markup. Taking a snapshot.",
                            "snapshot",
                            hop = 0,
                        )
                    },
                    { soFar -> answer(describeSnapshot(soFar.last().output.text)) },
                ),
        ),
        // ── One call for a whole table ─────────────────────────────────────────────────────────
        Exchange(
            matches = { it.contains("cheap") || it.contains("in stock") || it.contains("compare") },
            steps =
                listOf(
                    {
                        call(
                            "I will read the whole table in one call rather than element by element, " +
                                "so each field stays attached to its own row.",
                            "extract_rows",
                            buildJsonObject {
                                put("rows_css", "li[data-sku]")
                                put("limit", 10)
                                putJsonObject("columns") {
                                    // `.` is the row itself, so this reads the row's own attribute.
                                    // Not `css(":scope")`: `querySelector` only ever searches
                                    // descendants, so that matches nothing and yields "".
                                    putJsonObject("sku") {
                                        put("xpath", ".")
                                        put("from", "attribute")
                                        put("name", "data-sku")
                                    }
                                    putJsonObject("title") { put("css", "h3") }
                                    putJsonObject("price") { put("css", ".price") }
                                    putJsonObject("stock") { put("css", ".stock") }
                                }
                            },
                            hop = 0,
                        )
                    },
                    { soFar -> answer(rankRows(soFar.last().output.text)) },
                ),
        ),
        // ── A wrong guess, and the recovery from it ────────────────────────────────────────────
        Exchange(
            matches = { it.contains("logitech") || it.contains("how much") || it.contains("price of") },
            steps =
                listOf(
                    {
                        call(
                            "Reading the Logitech row's price.",
                            "extract",
                            buildJsonObject { put("css", "#result-logitech .price") },
                            hop = 0,
                        )
                    },
                    { soFar ->
                        // Written as a branch rather than assumed: if the guess ever starts working,
                        // the exchange should say so instead of narrating a failure that did not
                        // happen.
                        if (!soFar.last().output.isError) {
                            answer("`#result-logitech .price` resolved after all: ${soFar.last().output.text}.")
                        } else {
                            call(
                                "That selector matched nothing — I invented it, and this page does " +
                                    "not use ids like that. Rather than guess again I will look at " +
                                    "the page and use a handle it gives me.",
                                "snapshot",
                                hop = 1,
                            )
                        }
                    },
                    { soFar ->
                        val found = priceRefNear(soFar.last().output.text, needle = "Logitech")
                        if (found == null) {
                            answer(
                                "The snapshot has no price next to a Logitech row, so the page is " +
                                    "not what I expected. I am not going to invent an answer.",
                            )
                        } else {
                            val (ref, line) = found
                            call(
                                "The snapshot lists it as `$line`. Reading that element by handle, " +
                                    "which fails loudly if the page has changed under me.",
                                "extract",
                                buildJsonObject { put("ref", ref) },
                                hop = 2,
                            )
                        }
                    },
                    { soFar ->
                        val output = soFar.last().output
                        if (output.isError) {
                            answer("The handle did not resolve: ${output.text}")
                        } else {
                            answer(
                                "${output.text}.\n\nTwo tool calls and one dead end — the failure " +
                                    "came back as a result rather than an exception, which is what " +
                                    "let me correct course instead of stopping.",
                            )
                        }
                    },
                ),
        ),
        // ── The escape hatch ───────────────────────────────────────────────────────────────────
        Exchange(
            matches = { it.contains("how many") || it.contains("count") },
            steps =
                listOf(
                    {
                        call(
                            "Counting is not something the read tools express, so this is the one " +
                                "call that drops to JavaScript.",
                            "evaluate",
                            buildJsonObject { put("script", "document.querySelectorAll('li[data-sku]').length") },
                            hop = 0,
                        )
                    },
                    { soFar ->
                        val output = soFar.last().output
                        if (output.isError) {
                            answer("The expression failed: ${output.text}")
                        } else {
                            answer(
                                "${output.text} rows carry a `data-sku`.\n\n`evaluate` takes an " +
                                    "expression, not statements — anything longer has to be wrapped " +
                                    "in an IIFE — and it is the tool to reach for last, because it " +
                                    "skips the guards the others resolve elements through.",
                            )
                        }
                    },
                ),
        ),
    )

// ── Reading what the tools returned ────────────────────────────────────────────────────────────

/**
 * Turns a rendered snapshot back into prose.
 *
 * This is the mock doing, crudely, what a model does with a snapshot: read the header for what page
 * this is, and count what kind of thing is on it. Nothing here is fixture-specific.
 */
private fun describeSnapshot(snapshot: String): String {
    val lines = snapshot.lines()
    val header = lines.firstOrNull().orEmpty()
    val nodes = lines.drop(1).filter { it.contains("[ref=") }
    if (nodes.isEmpty()) return "The snapshot came back with no elements:\n\n$snapshot"
    val roles =
        nodes
            .groupingBy { it.trim().substringBefore(' ') }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            // "heading ×5" rather than "5 heading": these are role names, and pluralising them
            // generically produces nonsense on the ones that are already mass nouns.
            .joinToString(", ") { "${it.key} ×${it.value}" }
    val names = nodes.mapNotNull { nameIn(it) }.take(3).joinToString(", ") { "\"$it\"" }
    return buildString {
        append(header)
        append("\n\n")
        append("${nodes.size} elements — $roles.")
        if (names.isNotEmpty()) append(" The first few read $names.")
        append(
            "\n\nEvery line carries a `[ref=…]` handle, so I can act on any of them without " +
                "inventing a selector, and the whole page cost about a third of what its HTML would.",
        )
    }
}

/** Ranks what `extract_rows` returned. Every figure quoted below comes out of the live page. */
private fun rankRows(raw: String): String {
    val rows =
        runCatching { JSON.parseToJsonElement(raw).jsonArray.map { it.jsonObject } }
            .getOrNull()
            ?: return "`extract_rows` returned something I could not read as JSON:\n\n$raw"
    if (rows.isEmpty()) return "`extract_rows` matched no rows, so `li[data-sku]` is not this page's markup."

    val priced = rows.mapNotNull { row -> priceOf(row)?.let { row to it } }
    val inStock = priced.filter { field(it.first, "stock").equals("In stock", ignoreCase = true) }
    val cheapest =
        inStock.minByOrNull { it.second }
            ?: return "Every row with a price is out of stock, across all ${rows.size} of them."

    return buildString {
        append("The cheapest one in stock is ${field(cheapest.first, "title")}")
        append(" at ${field(cheapest.first, "price")}")
        field(cheapest.first, "sku").takeIf { it.isNotBlank() }?.let { append(", $it") }
        append(".")

        priced
            .filter { it.second < cheapest.second }
            .forEach { (row, _) ->
                append(
                    "\n\n${field(row, "title")} is cheaper at ${field(row, "price")}, " +
                        "but it is ${field(row, "stock").lowercase()}.",
                )
            }

        val unpriced = rows.size - priced.size
        if (unpriced > 0) {
            append(
                "\n\n$unpriced of the ${rows.size} rows carries no price at all. `extract_rows` put " +
                    "an empty string in that one field instead of shifting every later row onto the " +
                    "wrong product, which is the reason to read a table in one call rather than " +
                    "${rows.size * 4} separate ones.",
            )
        }
    }
}

/**
 * Finds the handle of the first price at or after [needle] in a rendered snapshot.
 *
 * The scan is forward-only and shallow on purpose: a price belongs to the row it follows, and
 * widening the search is how a reader ends up confidently quoting the next product's figure.
 */
private fun priceRefNear(
    snapshot: String,
    needle: String,
): Pair<String, String>? {
    val lines = snapshot.lines()
    val start = lines.indexOfFirst { it.contains(needle, ignoreCase = true) }
    if (start < 0) return null
    for (line in lines.drop(start).take(SCAN_AHEAD)) {
        if (!PRICE.containsMatchIn(line)) continue
        val ref = REF.find(line)?.groupValues?.get(1) ?: continue
        return ref to line.trim()
    }
    return null
}

private fun field(
    row: JsonObject,
    name: String,
): String = row[name]?.jsonPrimitive?.content.orEmpty()

/** `"$89.99"` → `89.99`, and null for the row that has no price. Currency is not the point here. */
private fun priceOf(row: JsonObject): Double? =
    field(row, "price")
        .filter { it.isDigit() || it == '.' }
        .toDoubleOrNull()

/** The accessible name a rendered snapshot line quotes, if it has one. */
private fun nameIn(line: String): String? =
    NAME
        .find(line)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.isNotBlank() }

private const val SCAN_AHEAD = 8
private val JSON = Json { ignoreUnknownKeys = true }
private val REF = Regex("""\[ref=([^]]+)]""")
private val NAME = Regex("\"([^\"]*)\"")
private val PRICE = Regex(Regex.escape("$") + """\s?\d""")
