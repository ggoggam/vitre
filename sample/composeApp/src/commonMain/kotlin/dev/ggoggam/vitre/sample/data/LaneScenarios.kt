package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.core.net.InterceptionPolicy
import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowStep
import dev.ggoggam.vitre.core.workflow.WorkflowStep.Extract.Source
import dev.ggoggam.vitre.core.workflow.WorkflowStep.ExtractRows.Column
import dev.ggoggam.vitre.core.workflow.css
import dev.ggoggam.vitre.core.workflow.xpath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One site in a pool, and everything needed to drive it: the workflow to run, and how to read what
 * comes back.
 *
 * [offersFrom] exists because the engine cannot do it. `Extract` writes into a variable nothing
 * else reads — variables do not flow between steps, which `docs/CONCURRENCY.md` lists as a known
 * gap — so normalising four shops' four ways of writing a price into one comparable number happens
 * here, in Kotlin, after the run.
 */
data class LaneSite(
    val label: String,
    val origin: String,
    val workflowFor: (query: String) -> Workflow,
    val offersFrom: (variables: Map<String, String>) -> List<Offer> = { emptyList() },
    /** Extra variables worth showing verbatim, e.g. a probe's JSON. */
    val notesFrom: (variables: Map<String, String>) -> String? = { null },
)

/** One shop's answer for one product, normalised so four shops can be sorted against each other. */
data class Offer(
    val shop: String,
    val title: String,
    val priceCents: Int?,
    val shippingCents: Int?,
    val inStock: Boolean?,
    val url: String?,
) {
    /**
     * What it actually costs. Sorting on the item price alone gets the answer wrong for two of the
     * fixture shops, which is exactly as often as it happens in real life.
     */
    val totalCents: Int? get() = priceCents?.let { it + (shippingCents ?: 0) }
}

/** A pool of sites driven in parallel, with everything the UI needs to run and render it. */
data class LaneScenario(
    val id: String,
    val name: String,
    val summary: String,
    val sites: List<LaneSite>,
    val policy: InterceptionPolicy,
    /** Null when the scenario takes no input, which hides the query field. */
    val queryLabel: String?,
    val defaultQuery: String = "",
    /** Whether to show the merged price table. A diagnostic has nothing to merge. */
    val comparesPrices: Boolean = true,
    /**
     * How long a lane may take to parse a document.
     *
     * Per scenario because the right answer differs by an order of magnitude: a fixture answers in
     * milliseconds, and four heavy third-party sites sharing one renderer take tens of seconds — a
     * distinction the default cannot make on their behalf.
     */
    val navigationTimeoutMs: Long = 30_000,
)

object LaneScenarios {
    /**
     * The workflow the whole mechanism exists for: one query, four shops, one answer.
     *
     * Offline and deterministic, so it is also the smoke test — a failure here means the lane
     * plumbing broke, not that somebody redesigned a website. What it is *not* is easier than the
     * real thing: the four shops sit at four distinct origins, one of them only has prices behind a
     * cross-origin JSON API, and no two of them write a price the same way.
     */
    val PriceScoutFixtures: LaneScenario =
        LaneScenario(
            id = "price-scout-fixtures",
            name = "Price scout (4 shops)",
            summary = "Four cross-origin shops searched at once, merged and sorted by delivered price.",
            queryLabel = "Product",
            defaultQuery = "mechanical keyboard",
            policy = InterceptionPolicy(handlers = ShopFixtures.handlers),
            sites =
                listOf(
                    alphaHardware(),
                    byteBazaar(),
                    keyclack(),
                    nordicParts(),
                ),
        )

    /**
     * The honest demonstration, against sites nobody here controls.
     *
     * Four real sites driven at once, each reporting what the automation can see from inside the
     * document it landed on and whether a cross-origin `fetch` succeeded. That fetch is the part
     * still worth watching: it comes back with a status on Android, where the interceptor can
     * supply an `Access-Control-Allow-Origin` the server never sent, and blocked on iOS, where
     * nothing can rewrite a response header.
     *
     * These four used to be here because they all refuse to be framed, back when a lane was an
     * iframe and rendering at all proved the interception worked. A lane is now a top-level
     * document and `X-Frame-Options` has nothing to say about one, so the scenario measures what
     * the platform allows rather than what the library got around.
     *
     * It hits the live web, so it will rot, and unlike the fixtures that is fine: it is a probe,
     * not a test, and a site that has changed is a thing worth finding out.
     *
     * The four were picked for *not* putting a bot challenge in front of the front page, which is
     * not fussiness: a challenge renders in a lane as "Webpage not available", which looks exactly
     * like the lane having failed to load.
     */
    val LivePagesProbe: LaneScenario =
        LaneScenario(
            id = "live-pages-probe",
            name = "Live pages probe",
            summary = "Four real sites driven at once, each inspected from inside the document it landed on.",
            queryLabel = null,
            comparesPrices = false,
            policy = InterceptionPolicy(),
            navigationTimeoutMs = 90_000,
            sites =
                listOf(
                    probeSite("GitHub", "https://github.com/"),
                    probeSite("Android Developers", "https://developer.android.com/"),
                    probeSite("MDN", "https://developer.mozilla.org/en-US/"),
                    probeSite("Wikipedia", "https://en.wikipedia.org/wiki/Main_Page"),
                ),
        )

    val all: List<LaneScenario> = listOf(PriceScoutFixtures, LivePagesProbe)

    // ── Fixture shops ─────────────────────────────────────────────────────────────────────────

    /** Plain list, price as text. The easy one, and the shape most shops actually use. */
    private fun alphaHardware() =
        LaneSite(
            label = "Alpha Hardware",
            origin = ShopFixtures.ALPHA_ORIGIN,
            workflowFor = { query ->
                Workflow(
                    id = "alpha",
                    name = "Alpha Hardware",
                    steps =
                        listOf(
                            WorkflowStep.Navigate(ShopFixtures.searchUrl(ShopFixtures.ALPHA_ORIGIN, query)),
                            WorkflowStep.WaitFor(css("li[data-sku]"), timeoutMs = 10_000),
                            WorkflowStep.ExtractRows(
                                rows = css("#results li[data-sku]"),
                                limit = 10,
                                into = "items",
                                columns =
                                    linkedMapOf(
                                        // `xpath(".")` and not `css(":scope")`: querySelector only
                                        // ever searches *descendants*, so `:scope` against a row
                                        // matches nothing and every sku comes back empty.
                                        "sku" to Column(xpath("."), Source.Attribute("data-sku")),
                                        "title" to Column(css("h3")),
                                        "price" to Column(css(".price")),
                                        "ship" to Column(css(".ship")),
                                        "stock" to Column(css(".stock")),
                                        "url" to Column(css("a.permalink"), Source.Property("href")),
                                    ),
                            ),
                        ),
                )
            },
            offersFrom = { variables ->
                variables.rows("items").map { row ->
                    Offer(
                        shop = "Alpha Hardware",
                        title = row.text("title"),
                        priceCents = decimalToCents(row.text("price")),
                        shippingCents = decimalToCents(row.text("ship")) ?: 0,
                        inStock = row.text("stock").equals("In stock", ignoreCase = true),
                        url = row.text("url").ifBlank { null },
                    )
                }
            },
        )

    /** Table, with the price the shop actually means hidden in a `data-cents` integer. */
    private fun byteBazaar() =
        LaneSite(
            label = "ByteBazaar",
            origin = ShopFixtures.BAZAAR_ORIGIN,
            workflowFor = { query ->
                Workflow(
                    id = "bazaar",
                    name = "ByteBazaar",
                    steps =
                        listOf(
                            WorkflowStep.Navigate(ShopFixtures.searchUrl(ShopFixtures.BAZAAR_ORIGIN, query)),
                            WorkflowStep.WaitFor(css("tbody tr[data-id]"), timeoutMs = 10_000),
                            WorkflowStep.ExtractRows(
                                rows = css("tbody tr[data-id]"),
                                limit = 10,
                                into = "items",
                                columns =
                                    linkedMapOf(
                                        "title" to Column(css("td.name a")),
                                        // The attribute, not the rendered text: an integer needs no
                                        // currency-symbol guessing and no locale.
                                        "cents" to Column(css("td.amt"), Source.Attribute("data-cents")),
                                        "shipCents" to Column(css("td.ship"), Source.Attribute("data-ship-cents")),
                                        "avail" to Column(css("td.avail")),
                                        "url" to Column(css("td.name a"), Source.Property("href")),
                                    ),
                            ),
                        ),
                )
            },
            offersFrom = { variables ->
                variables.rows("items").map { row ->
                    Offer(
                        shop = "ByteBazaar",
                        title = row.text("title"),
                        priceCents = row.text("cents").toIntOrNull(),
                        shippingCents = row.text("shipCents").toIntOrNull() ?: 0,
                        inStock = row.text("avail").equals("Yes", ignoreCase = true),
                        url = row.text("url").ifBlank { null },
                    )
                }
            },
        )

    /** Price split across spans, so it takes two columns and reassembly. All XPath, to show it. */
    private fun keyclack() =
        LaneSite(
            label = "Keyclack",
            origin = ShopFixtures.KEYCLACK_ORIGIN,
            workflowFor = { query ->
                Workflow(
                    id = "keyclack",
                    name = "Keyclack",
                    steps =
                        listOf(
                            // No query in the URL for this one. It has to be typed into the shop's
                            // own box and submitted, which is what automating a third-party site
                            // usually means — and what proves `Input` and `Click` reach inside a
                            // cross-origin document rather than only `Extract` doing so.
                            WorkflowStep.Navigate("${ShopFixtures.KEYCLACK_ORIGIN}/catalog"),
                            WorkflowStep.WaitFor(css("#q"), timeoutMs = 10_000),
                            WorkflowStep.Input(css("#q"), text = query),
                            // Submitting navigates the frame, so the reply to this click races the
                            // document being torn down. The lane retries a command whose document
                            // was replaced, which is the only reason this step is safe.
                            WorkflowStep.Click(css("#go")),
                            WorkflowStep.WaitFor(xpath("//div[contains(@class,'card')]"), timeoutMs = 10_000),
                            WorkflowStep.ExtractRows(
                                rows = xpath("//div[contains(@class,'card')]"),
                                limit = 10,
                                into = "items",
                                columns =
                                    linkedMapOf(
                                        "code" to Column(xpath("."), Source.Attribute("data-code")),
                                        "title" to Column(xpath(".//h4")),
                                        // Two halves of one number. Reading `.cost` whole would
                                        // give "$8999" — the decimal point is CSS, not content.
                                        "whole" to Column(xpath(".//span[@class='whole']")),
                                        "frac" to Column(xpath(".//span[@class='frac']")),
                                        "shipCents" to Column(xpath(".//span[@class='delivery']"), Source.Attribute("data-cents")),
                                        "avail" to Column(xpath(".//span[@class='avail']")),
                                        "url" to Column(xpath(".//a"), Source.Property("href")),
                                    ),
                            ),
                        ),
                )
            },
            offersFrom = { variables ->
                variables.rows("items").map { row ->
                    val whole = row.text("whole").filter { it.isDigit() }.toIntOrNull()
                    val fraction =
                        row
                            .text("frac")
                            .filter { it.isDigit() }
                            .padEnd(2, '0')
                            .take(2)
                            .toIntOrNull()
                    Offer(
                        shop = "Keyclack",
                        title = row.text("title"),
                        priceCents = whole?.let { it * 100 + (fraction ?: 0) },
                        shippingCents = row.text("shipCents").toIntOrNull() ?: 0,
                        inStock = row.text("avail").contains("ships", ignoreCase = true),
                        url = row.text("url").ifBlank { null },
                    )
                }
            },
        )

    /**
     * Empty shell, filled from a cross-origin JSON API.
     *
     * The `WaitFor` here is load bearing in a way the others' are not: nothing renders until the
     * `fetch` completes, and in a browser that fetch fails the CORS check. If the lane's results
     * appear, `Access-Control-Allow-Origin` was added by the interceptor.
     */
    private fun nordicParts() =
        LaneSite(
            label = "Nordic Parts",
            origin = ShopFixtures.NORDIC_ORIGIN,
            workflowFor = { query ->
                Workflow(
                    id = "nordic",
                    name = "Nordic Parts",
                    steps =
                        listOf(
                            WorkflowStep.Navigate(ShopFixtures.searchUrl(ShopFixtures.NORDIC_ORIGIN, query)),
                            WorkflowStep.WaitFor(css("article[data-part]"), timeoutMs = 10_000),
                            WorkflowStep.Extract(selector = "#state", into = "apiState"),
                            WorkflowStep.ExtractRows(
                                rows = css("article[data-part]"),
                                limit = 10,
                                into = "items",
                                columns =
                                    linkedMapOf(
                                        "sku" to Column(xpath("."), Source.Attribute("data-part")),
                                        "title" to Column(css("h5")),
                                        "amount" to Column(css("b"), Source.Attribute("data-amount")),
                                        "ship" to Column(css("em"), Source.Attribute("data-ship")),
                                        "avail" to Column(css("i.avail")),
                                    ),
                            ),
                        ),
                )
            },
            offersFrom = { variables ->
                variables.rows("items").map { row ->
                    Offer(
                        shop = "Nordic Parts",
                        title = row.text("title"),
                        priceCents = decimalToCents(row.text("amount")),
                        shippingCents = decimalToCents(row.text("ship")) ?: 0,
                        inStock = row.text("avail") == "available",
                        url = null,
                    )
                }
            },
            notesFrom = { it["apiState"] },
        )

    // ── Live probe ────────────────────────────────────────────────────────────────────────────

    private fun probeSite(
        label: String,
        url: String,
    ) = LaneSite(
        label = label,
        origin = url,
        workflowFor = {
            Workflow(
                id = "probe-${label.lowercase().replace(' ', '-')}",
                name = label,
                steps =
                    listOf(
                        WorkflowStep.Navigate(url),
                        WorkflowStep.EvaluateJs(script = PROBE_SCRIPT, into = "probe"),
                    ),
            )
        },
        notesFrom = { it["probe"] },
    )

    /**
     * Run from *inside* the lane's own document.
     *
     * Returns a promise, which the controller awaits — the `fetch` is the point and it cannot be
     * answered synchronously. `example.com` sends no `Access-Control-Allow-Origin` of its own, so a
     * status here rather than a `TypeError` means the interceptor supplied one.
     */
    private val PROBE_SCRIPT =
        """
        (async function () {
          var out = {
            url: location.href,
            title: (document.title || '').slice(0, 80),
            nodes: document.querySelectorAll('*').length
          };
          try {
            var r = await fetch('https://example.com/', { mode: 'cors' });
            out.crossOriginFetch = r.status + ' ' + (await r.text()).length + 'B';
          } catch (e) {
            out.crossOriginFetch = 'blocked: ' + (e && e.message || e);
          }
          return out;
        })()
        """.trimIndent()
}

// ── Reading what came back ────────────────────────────────────────────────────────────────────

private val JSON = Json { ignoreUnknownKeys = true }

/** `ExtractRows` stores a JSON array; anything else here is a workflow that failed earlier. */
private fun Map<String, String>.rows(key: String): List<JsonObject> {
    val raw = this[key] ?: return emptyList()
    val parsed = runCatching { JSON.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    return parsed.mapNotNull { it as? JsonObject }
}

private fun JsonObject.text(key: String): String =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()
        .trim()

/**
 * `"$1,299.99"` → `129999`; `"Free delivery"` → `0`; `""` → null.
 *
 * Tolerant on purpose: it is fed whatever four different shops happened to put in an element, and
 * a currency symbol, a thousands separator or a trailing word are all normal.
 */
internal fun decimalToCents(raw: String): Int? {
    if (raw.isBlank()) return null
    if (raw.contains("free", ignoreCase = true)) return 0
    val digits = raw.filter { it.isDigit() || it == '.' }.trimEnd('.')
    if (digits.isEmpty()) return null
    val whole = digits.substringBefore('.').ifEmpty { "0" }.toIntOrNull() ?: return null
    val fraction =
        digits
            .substringAfter('.', "")
            .padEnd(2, '0')
            .take(2)
            .ifEmpty { "0" }
            .toIntOrNull() ?: 0
    return whole * 100 + fraction
}

/** `12999` → `"$129.99"`. */
internal fun Int.asMoney(): String = "$${this / 100}.${(this % 100).toString().padStart(2, '0')}"
