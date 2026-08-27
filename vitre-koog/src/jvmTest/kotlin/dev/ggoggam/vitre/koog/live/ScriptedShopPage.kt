package dev.ggoggam.vitre.koog.live

import dev.ggoggam.vitre.core.bridge.DefaultWebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewBridge
import dev.ggoggam.vitre.core.bridge.WebViewInbox
import dev.ggoggam.vitre.core.concurrent.WebViewOrdering
import dev.ggoggam.vitre.core.webview.ExclusiveAccess
import dev.ggoggam.vitre.core.webview.WebViewController
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A shop page with no browser under it.
 *
 * [dev.ggoggam.vitre.koog.testing.FakePageController] answers scripts from a lookup table, which is
 * enough when the test knows in advance which scripts will arrive. A live model does not work that
 * way: it decides what to call, so the page has to *react* — typing has to change what a later
 * extract reads, and clicking has to change what a later snapshot shows. Otherwise the model is
 * being graded on a page that cannot tell whether it did the task.
 *
 * So this holds state and answers the shapes `vitre-core` actually emits, which were read off the
 * driver rather than guessed (see the `evaluatedScripts` of any test using the fake). It is not a
 * DOM and does not try to be one: an unrecognised script fails loudly rather than returning `null`,
 * because a `null` here would surface as a puzzling model mistake three tool calls later.
 *
 * What it therefore cannot prove is the half the on-device test exists for — the snapshot walk, the
 * handle registry on `window`, a real click. Those run for real in
 * [dev.ggoggam.vitre.koog.KoogAgentOnDeviceTest]. Here the page is the fake and the *model* is real,
 * which is the exact complement.
 */
class ScriptedShopPage : WebViewController {
    /** What is in the search box. Set by the `type` tool, read back by `extract`. */
    var query: String = ""
        private set

    /** Whether the search button has been pressed, which is what puts results on the page. */
    var searched: Boolean = false
        private set

    /** Every script that arrived, for a test that wants to assert on the order of tool calls. */
    val evaluatedScripts: MutableList<String> = mutableListOf()

    data class Product(
        val name: String,
        val price: String,
        val stock: String,
    )

    // Two traps, both of the kind a model actually falls into. The cheapest keyboard on the page is
    // not wireless, and the cheapest wireless one is out of stock — so "the cheapest wireless
    // keyboard in stock" is the third row, and it can only be found by reading all three columns of
    // all three rows. A model that skims the first result, or sorts on price alone, gets it wrong.
    private val products =
        listOf(
            Product("Cauchy Mechanical Keyboard", "$39.99", "In stock"),
            Product("Aster Wireless Keyboard", "$54.50", "Out of stock"),
            Product("Borel Wireless Keyboard", "$61.00", "In stock"),
        )

    /** The answer the page supports, for the test to assert on. */
    val cheapestWirelessInStock: Product get() = products.last()

    // ── The page as a list of nodes ────────────────────────────────────────────────────────────

    private data class Node(
        val id: String,
        val role: String,
        val name: String,
        val tag: String,
        val depth: Int,
        val selectors: Set<String>,
        val text: String = name,
        val value: String? = null,
    )

    private fun nodes(): List<Node> =
        buildList {
            add(Node("heading", "heading", "Keyboards", "h1", 0, setOf("h1")))
            add(
                Node(
                    id = "q",
                    role = "searchbox",
                    name = "Search products",
                    tag = "input",
                    depth = 0,
                    selectors = setOf("#q", "input", "input#q", "input[type=search]"),
                    text = "",
                    value = query,
                ),
            )
            add(Node("go", "button", "Search", "button", 0, setOf("#go", "button", "button#go")))
            if (searched) {
                products.forEachIndexed { i, p ->
                    add(Node("row$i", "text", p.name, "li", 0, setOf(".result", "li")))
                    add(Node("row$i-name", "text", p.name, "span", 1, setOf(".result .name", ".name")))
                    add(Node("row$i-price", "text", p.price, "span", 1, setOf(".result .price", ".price")))
                    add(Node("row$i-stock", "text", p.stock, "span", 1, setOf(".result .stock", ".stock")))
                }
            }
        }

    // Handles are minted on first sighting and never reissued, exactly as the real snapshot's
    // WeakMap does — a ref the model saw two snapshots ago still has to resolve, since the document
    // was never replaced.
    private val refs = mutableMapOf<String, String>()
    private var nextRef = 1

    private fun refFor(id: String): String = refs.getOrPut(id) { "e${nextRef++}" }

    private fun byRef(ref: String): Node? {
        val id = refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        return nodes().firstOrNull { it.id == id }
    }

    private fun bySelector(selector: String): Node? = nodes().firstOrNull { selector in it.selectors }

    // ── Answering scripts ──────────────────────────────────────────────────────────────────────

    private val handleRef = Regex("""byRef\.get\("([^"]+)"\)""")
    private val cssSelector = Regex("""querySelector\("((?:[^"\\]|\\.)*)"\)""")
    private val rowsSelector = Regex("""querySelectorAll\("((?:[^"\\]|\\.)*)"\)""")

    // `var T="…"`, not `el.value="…"`: the fill step stopped assigning `value` directly. It writes
    // through the setter on the element's prototype now — the only write a framework's value
    // tracker can see — so the text is bound to a local and handed to that.
    private val typedValue = Regex("""var T="((?:[^"\\]|\\.)*)"""")
    private val propertyName = Regex("""\?\.\["([^"]+)"\]""")
    private val column = Regex(""""([^"]+)":\(\(r\.querySelector\("([^"]+)"\)\)""")

    /** The element a script is about, whether it addressed it by handle or by selector. */
    private fun target(script: String): Node? =
        handleRef.find(script)?.let { byRef(it.groupValues[1]) }
            ?: cssSelector.find(script)?.let { bySelector(it.groupValues[1]) }

    override suspend fun evaluateJs(script: String): String =
        order.ordered {
            evaluatedScripts += script
            answer(script)
        }

    private fun answer(script: String): String =
        when {
            // The snapshot walk. Recognised by its tail rather than its body, which is 100 lines of
            // JavaScript this class has no business knowing the shape of.
            "walk(document.body,0)" in script -> {
                snapshotJson()
            }

            script.trim() == "document.title" -> {
                JsonPrimitive(TITLE).toString()
            }

            // The handle guard, which runs before every ref-addressed action. Nothing here ever
            // detaches — the page mutates in place — so a known ref is always 'ok'.
            "return 'no-snapshot'" in script -> {
                val ref = handleRef.find(script)?.groupValues?.get(1)
                JsonPrimitive(if (ref != null && byRef(ref) != null) "ok" else "unknown").toString()
            }

            // Presence, as `wait_for` and every action's implicit wait ask it.
            script.trimEnd().endsWith("!==null") -> {
                (target(script) != null).toString()
            }

            "el.dispatchEvent(new Event('input'" in script -> {
                val node = target(script)
                val text =
                    typedValue
                        .find(script)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                if (node?.id == "q") query = unescape(text)
                // A status, not `null`. The fill step reports what it managed to do, so that a
                // field it could not actually write fails the step instead of passing quietly —
                // which means this page has to answer it like a page that did the work.
                JsonPrimitive("ok").toString()
            }

            script.trimEnd().endsWith("?.click()") -> {
                // The one state change that matters: pressing Search is what puts rows on the page,
                // so a model that answers without clicking cannot have read them.
                if (target(script)?.id == "go") searched = true
                "null"
            }

            ".map(function(r)" in script -> {
                extractRowsJson(script)
            }

            "?.textContent??''" in script -> {
                JsonPrimitive(target(script)?.text.orEmpty()).toString()
            }

            propertyName.containsMatchIn(script) -> {
                val node = target(script)
                val property = propertyName.find(script)!!.groupValues[1]
                val read = if (property == "value") node?.value else node?.text
                JsonPrimitive(read.orEmpty()).toString()
            }

            // Deliberately not `null`. A script this class does not understand means the page is
            // lying to the model, and a lie is far harder to read off a failed assertion than a
            // stack trace naming the script.
            else -> {
                error("ScriptedShopPage does not know this script:\n$script")
            }
        }

    private fun snapshotJson(): String =
        buildJsonObject {
            put("url", URL)
            put("title", TITLE)
            put("truncated", false)
            putJsonArray("nodes") {
                nodes().forEach { node ->
                    add(
                        buildJsonObject {
                            put("ref", refFor(node.id))
                            put("role", node.role)
                            put("name", node.name)
                            put("tag", node.tag)
                            put("depth", node.depth)
                            node.value?.let { put("value", it) }
                        },
                    )
                }
            }
        }.toString()

    private fun extractRowsJson(script: String): String {
        val rows =
            rowsSelector
                .find(script)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val columns = column.findAll(script).map { it.groupValues[1] to it.groupValues[2] }.toList()
        // The row locator has to actually name the rows. A model that passes the wrong one gets an
        // empty array, which is what a real page would give it.
        val matched = if (rows in setOf(".result", "li", ".result li", "ul li")) products.indices else IntRange.EMPTY
        return buildJsonArray {
            matched.forEach { i ->
                add(
                    buildJsonObject {
                        columns.forEach { (field, selector) ->
                            put(field, columnValue(i, selector))
                        }
                    },
                )
            }
        }.toString()
    }

    private fun columnValue(
        row: Int,
        selector: String,
    ): String {
        val id =
            when {
                "name" in selector || selector == "h3" -> "row$row-name"
                "price" in selector -> "row$row-price"
                "stock" in selector || "avail" in selector -> "row$row-stock"
                else -> return ""
            }
        return nodes().firstOrNull { it.id == id }?.text.orEmpty()
    }

    // `jsString` escapes what it puts in a script, so the value read back out has to be unescaped
    // or a model that types an apostrophe sees it come back with a backslash in front.
    private fun unescape(raw: String): String =
        raw
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

    // ── The rest of the controller contract ────────────────────────────────────────────────────

    private val inbox = WebViewInbox()
    private val order = WebViewOrdering()

    override val bridge: WebViewBridge = DefaultWebViewBridge(inbox) { script -> evaluateJs(script) }

    override suspend fun navigate(url: String) = order.ordered { }

    override suspend fun loadHtml(
        html: String,
        baseUrl: String?,
    ) = order.ordered { }

    override suspend fun <T> exclusively(block: suspend (ExclusiveAccess) -> T): T = order.exclusively(block)

    override fun close() = Unit

    companion object {
        const val URL: String = "https://shop.test/keyboards"
        const val TITLE: String = "Keyboards — shop.test"
    }
}
