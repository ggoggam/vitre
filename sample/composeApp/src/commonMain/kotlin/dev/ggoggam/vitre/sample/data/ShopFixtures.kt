package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.core.net.InterceptedRequest
import dev.ggoggam.vitre.core.net.InterceptedResponse
import dev.ggoggam.vitre.core.net.RequestHandler

/**
 * Four synthetic shops, served entirely from memory at four **distinct** origins.
 *
 * Distinct is the whole point. A demo that put four copies of the same fixture into four lanes
 * would prove nothing about cross-origin anything: the lanes would be same-origin with each other,
 * and the mechanism this sample exists to demonstrate would never be exercised. Because these are
 * served through the interceptor instead, each lane genuinely is a foreign origin to the host and
 * to its neighbours — while the run stays offline, deterministic, and fit to be the smoke test.
 *
 * They also disagree with each other on purpose. A real price comparison spends most of its effort
 * on the fact that no two shops mark up a price the same way, so:
 *
 *  - **Alpha Hardware** is a plain `<ul>` of `data-sku` rows with the price as text.
 *  - **ByteBazaar** is a `<table>` whose real price lives in a `data-cents` integer attribute.
 *  - **Keyclack** splits the price across `<span>`s, so it takes two columns and reassembly.
 *  - **Nordic Parts** ships an empty shell and fills it from a *cross-origin* JSON API — which
 *    is the one that needs CORS relaxed, and the one where the network tap beats the DOM.
 */
object ShopFixtures {
    const val ALPHA_ORIGIN: String = "https://alpha-hardware.test"
    const val BAZAAR_ORIGIN: String = "https://bytebazaar.test"
    const val KEYCLACK_ORIGIN: String = "https://keyclack.test"
    const val NORDIC_ORIGIN: String = "https://nordicparts.test"

    /**
     * Deliberately a *different* origin from the page that fetches it. A same-origin API would
     * work with no help from us and would quietly demonstrate nothing.
     */
    const val NORDIC_API_ORIGIN: String = "https://api.nordicparts.test"

    fun searchUrl(
        origin: String,
        query: String,
    ): String =
        when (origin) {
            ALPHA_ORIGIN -> "$origin/search?q=${query.urlEncoded()}"
            BAZAAR_ORIGIN -> "$origin/s?query=${query.urlEncoded()}"
            KEYCLACK_ORIGIN -> "$origin/catalog?term=${query.urlEncoded()}"
            NORDIC_ORIGIN -> "$origin/find?q=${query.urlEncoded()}"
            else -> "$origin/?q=${query.urlEncoded()}"
        }

    /** Every handler the fixture scenario needs, in the order the interceptor should try them. */
    val handlers: List<RequestHandler> =
        listOf(
            RequestHandler(::serveAlpha),
            RequestHandler(::serveBazaar),
            RequestHandler(::serveKeyclack),
            RequestHandler(::serveNordicShell),
            RequestHandler(::serveNordicApi),
            RequestHandler(::serveFavicon),
        )

    // ── Alpha Hardware ────────────────────────────────────────────────────────────────────────

    private fun serveAlpha(request: InterceptedRequest): InterceptedResponse? {
        if (!request.url.startsWith("$ALPHA_ORIGIN/search")) return null
        val offers = matches(ALPHA_ORIGIN, request.url.queryParam("q"))
        val rows =
            offers.joinToString("\n") { offer ->
                """
                <li data-sku="${offer.item.sku}">
                  <h3>${offer.item.title.escaped()}</h3>
                  <span class="price">${'$'}${offer.priceCents.asDecimal()}</span>
                  <span class="ship">${if (offer.shippingCents == 0) "Free delivery" else "+${'$'}${offer.shippingCents.asDecimal()} delivery"}</span>
                  <span class="stock${if (offer.inStock) "" else " out"}">${if (offer.inStock) "In stock" else "Backordered"}</span>
                  <a class="permalink" href="$ALPHA_ORIGIN/p/${offer.item.sku}">View</a>
                </li>
                """.trimIndent()
            }
        return page(
            title = "Alpha Hardware",
            accent = "#2f6f4f",
            body =
                """
                <h1>Alpha Hardware</h1>
                <p class="muted">${offers.size} results</p>
                <ul id="results">
                $rows
                </ul>
                """.trimIndent(),
        )
    }

    // ── ByteBazaar ────────────────────────────────────────────────────────────────────────────

    private fun serveBazaar(request: InterceptedRequest): InterceptedResponse? {
        if (!request.url.startsWith("$BAZAAR_ORIGIN/s")) return null
        val rows =
            matches(BAZAAR_ORIGIN, request.url.queryParam("query")).joinToString("\n") { offer ->
                """
                <tr data-id="${offer.item.sku}">
                  <td class="name"><a href="$BAZAAR_ORIGIN/item/${offer.item.sku}">${offer.item.title.escaped()}</a></td>
                  <td class="amt" data-cents="${offer.priceCents}">${'$'}${offer.priceCents.asDecimal()}</td>
                  <td class="ship" data-ship-cents="${offer.shippingCents}">${if (offer.shippingCents == 0) "Free" else "${'$'}${offer.shippingCents.asDecimal()}"}</td>
                  <td class="avail">${if (offer.inStock) "Yes" else "No"}</td>
                </tr>
                """.trimIndent()
            }
        return page(
            title = "ByteBazaar",
            accent = "#7a4fbf",
            body =
                """
                <h1>ByteBazaar</h1>
                <table id="grid">
                  <thead><tr><th>Item</th><th>Price</th><th>Ship</th><th>Stock</th></tr></thead>
                  <tbody>
                $rows
                  </tbody>
                </table>
                """.trimIndent(),
        )
    }

    // ── Keyclack ──────────────────────────────────────────────────────────────────────────────

    /**
     * The one shop that will not take a query in the URL.
     *
     * Deliberately: plenty of real storefronts only search from their own box, and a demo where
     * every lane is a `GET ?q=` never exercises `Input` or `Click` inside a foreign document —
     * which is most of what "automating a third-party site" actually means. Submitting the form
     * navigates the frame, so this is also the case that proves a lane survives a page-initiated
     * navigation.
     */
    private fun serveKeyclack(request: InterceptedRequest): InterceptedResponse? {
        if (!request.url.startsWith("$KEYCLACK_ORIGIN/catalog")) return null
        val term = request.url.queryParam("term")
        val search =
            """
            <form id="finder" action="/catalog" method="get">
              <input id="q" name="term" type="search" placeholder="Search Keyclack" value="${term.orEmpty().escaped()}">
              <button id="go" type="submit">Search</button>
            </form>
            """.trimIndent()
        if (term == null) {
            return page(
                title = "Keyclack",
                accent = "#b4593a",
                body = "<h1>Keyclack</h1>\n$search\n<p class=\"muted\">Search the catalogue to see prices.</p>",
            )
        }
        val cards =
            matches(KEYCLACK_ORIGIN, term).joinToString("\n") { offer ->
                val whole = offer.priceCents / CENTS_PER_UNIT
                val fraction = (offer.priceCents % CENTS_PER_UNIT).toString().padStart(2, '0')
                """
                <div class="card" data-code="${offer.item.sku}">
                  <h4>${offer.item.title.escaped()}</h4>
                  <p class="cost">
                    <span class="cur">${'$'}</span><span class="whole">$whole</span><span class="frac">$fraction</span>
                  </p>
                  <p class="meta">
                    <span class="delivery" data-cents="${offer.shippingCents}">${if (offer.shippingCents == 0) "free shipping" else "shipping ${'$'}${offer.shippingCents.asDecimal()}"}</span>
                    · <span class="avail">${if (offer.inStock) "ships today" else "sold out"}</span>
                  </p>
                  <a href="$KEYCLACK_ORIGIN/k/${offer.item.sku}">Details</a>
                </div>
                """.trimIndent()
            }
        return page(
            title = "Keyclack",
            accent = "#b4593a",
            body = "<h1>Keyclack</h1>\n$search\n<div id=\"cards\">\n$cards\n</div>",
        )
    }

    // ── Nordic Parts: shell + cross-origin API ────────────────────────────────────────────────

    private fun serveNordicShell(request: InterceptedRequest): InterceptedResponse? {
        if (!request.url.startsWith("$NORDIC_ORIGIN/find")) return null
        val query = request.url.queryParam("q").orEmpty()
        return page(
            title = "Nordic Parts",
            accent = "#2f5f9f",
            body =
                """
                <h1>Nordic Parts</h1>
                <p class="muted" id="state">loading…</p>
                <div id="grid"></div>
                <script>
                  // Cross-origin on purpose. In a browser this fails the CORS check and the grid
                  // stays empty; inside a lane the interceptor has already reflected the Origin
                  // back, so it succeeds — which is the whole demonstration.
                  fetch('$NORDIC_API_ORIGIN/items?q=' + encodeURIComponent(${query.jsLiteral()}), { mode: 'cors' })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                      document.getElementById('state').textContent = data.items.length + ' results via ' + data.source;
                      document.getElementById('grid').innerHTML = data.items.map(function (it) {
                        return '<article data-part="' + it.sku + '">' +
                          '<h5>' + it.title + '</h5>' +
                          '<b data-amount="' + it.price + '">' + it.currency + ' ' + it.price + '</b>' +
                          '<em data-ship="' + it.shipping + '">ship ' + it.shipping + '</em>' +
                          '<i class="avail">' + (it.inStock ? 'available' : 'unavailable') + '</i>' +
                          '</article>';
                      }).join('');
                    })
                    .catch(function (e) {
                      document.getElementById('state').textContent = 'api failed: ' + e.message;
                    });
                </script>
                """.trimIndent(),
        )
    }

    /**
     * The JSON a real shop's front end would consume — and note what it *does not* send: no
     * `Access-Control-Allow-Origin`. The page still reads it, because the interceptor adds one.
     */
    private fun serveNordicApi(request: InterceptedRequest): InterceptedResponse? {
        if (!request.url.startsWith("$NORDIC_API_ORIGIN/items")) return null
        val items =
            matches(NORDIC_ORIGIN, request.url.queryParam("q")).joinToString(",") { offer ->
                """{"sku":"${offer.item.sku}","title":"${offer.item.title.escaped()}",""" +
                    """"currency":"USD","price":"${offer.priceCents.asDecimal()}",""" +
                    """"shipping":"${offer.shippingCents.asDecimal()}","inStock":${offer.inStock}}"""
            }
        val json = """{"source":"nordic-catalog-v3","items":[$items]}"""
        return InterceptedResponse(
            contentType = "application/json",
            headers = mapOf("Cache-Control" to "no-store"),
            body = json.encodeToByteArray(),
        )
    }

    /** Otherwise every lane logs a failed favicon fetch, which is noise in the network tab. */
    private fun serveFavicon(request: InterceptedRequest): InterceptedResponse? =
        if (!request.url.endsWith("/favicon.ico")) {
            null
        } else {
            InterceptedResponse(status = 204, reason = "No Content", contentType = "image/x-icon")
        }

    // ── Catalogue ─────────────────────────────────────────────────────────────────────────────

    /** What [origin] sells, filtered to [query]. Every shop prices the same catalogue differently. */
    private fun matches(
        origin: String,
        query: String?,
    ): List<ShopOffer> {
        val pricing = PRICING.getValue(origin)
        val tokens =
            query
                .orEmpty()
                .lowercase()
                .split(' ')
                .filter { it.isNotBlank() }
        return CATALOG
            .filterNot { it.sku in pricing.absent }
            .filter { item ->
                val haystack = "${item.title} ${item.brand} ${item.sku}".lowercase()
                tokens.isEmpty() || tokens.all { haystack.contains(it) }
            }.map { item -> pricing.quote(item) }
    }

    private fun page(
        title: String,
        accent: String,
        body: String,
    ): InterceptedResponse =
        InterceptedResponse(
            contentType = "text/html",
            // Sent on purpose: without stripping, none of these render in a lane at all, so
            // leaving them in is what makes the fixtures a real test of the mechanism.
            headers =
                mapOf(
                    "X-Frame-Options" to "DENY",
                    "Content-Security-Policy" to "frame-ancestors 'none'; base-uri 'self'",
                ),
            body = shopHtml(title, accent, body).encodeToByteArray(),
        )

    private const val CENTS_PER_UNIT = 100
}

/** One product, as the catalogue knows it. Shops disagree about the price, not the thing. */
data class CatalogItem(
    val sku: String,
    val title: String,
    val brand: String,
)

/** What one shop charges for one [CatalogItem]. */
data class ShopOffer(
    val item: CatalogItem,
    val priceCents: Int,
    val shippingCents: Int,
    val inStock: Boolean,
)

private val CATALOG =
    listOf(
        CatalogItem("KB-1001", "Aula F75 Pro Wireless Mechanical Keyboard, 75% Hot-Swappable", "Aula"),
        CatalogItem("KB-1002", "Keychron K2 V2 Wireless Mechanical Keyboard, Brown Switches", "Keychron"),
        CatalogItem("KB-1003", "Logitech MX Mechanical Mini Keyboard, Low Profile Tactile Quiet", "Logitech"),
        CatalogItem("KB-1004", "NuPhy Air75 V2 Low Profile Mechanical Keyboard", "NuPhy"),
        CatalogItem("KB-1005", "Ducky One 3 SF Mechanical Keyboard, 65% Hotswap", "Ducky"),
    )

/** The catalogue price, before any shop marks it up or down. */
private val LIST_PRICE_CENTS =
    mapOf(
        "KB-1001" to 7_999,
        "KB-1002" to 9_499,
        "KB-1003" to 11_999,
        "KB-1004" to 13_499,
        "KB-1005" to 15_999,
    )

/**
 * How one shop turns the catalogue into its own prices.
 *
 * The numbers are chosen so the answer is not obvious, because a comparison one shop simply wins
 * demonstrates nothing. Keyclack always has the cheapest *item* and, thanks to a flat delivery
 * charge it never waives, usually not the cheapest *delivered* — which is the mistake a price
 * comparison most often makes. ByteBazaar only becomes competitive above its free-shipping
 * threshold. And two shops do not carry the whole catalogue, so a lane returning fewer rows than
 * its neighbour is ordinary rather than a bug.
 */
private data class ShopPricing(
    val pricePermille: Int,
    val flatShippingCents: Int,
    val freeShippingOverCents: Int? = null,
    val absent: Set<String> = emptySet(),
    val outOfStock: Set<String> = emptySet(),
) {
    fun quote(item: CatalogItem): ShopOffer {
        val list = LIST_PRICE_CENTS.getValue(item.sku)
        val price = (list.toLong() * pricePermille / PERMILLE).toInt().asCharmPrice()
        val shipping =
            if (freeShippingOverCents != null && price >= freeShippingOverCents) 0 else flatShippingCents
        return ShopOffer(
            item = item,
            priceCents = price,
            shippingCents = shipping,
            inStock = item.sku !in outOfStock,
        )
    }

    /** No shop charges $74.26. Nearest whole unit, less a penny. */
    private fun Int.asCharmPrice(): Int = ((this + HALF_UNIT) / CENTS_PER_UNIT) * CENTS_PER_UNIT - 1

    private companion object {
        const val PERMILLE = 1_000
        const val CENTS_PER_UNIT = 100
        const val HALF_UNIT = 50
    }
}

private val PRICING =
    mapOf(
        // List price, but never charges for delivery — so it owns the cheap end outright.
        ShopFixtures.ALPHA_ORIGIN to
            ShopPricing(pricePermille = 1_000, flatShippingCents = 0, outOfStock = setOf("KB-1004")),
        // Undercuts on the sticker, then adds delivery unless you spend enough.
        ShopFixtures.BAZAAR_ORIGIN to
            ShopPricing(pricePermille = 940, flatShippingCents = 895, freeShippingOverCents = 12_000),
        // Cheapest item every time, and a flat delivery charge that usually undoes it.
        ShopFixtures.KEYCLACK_ORIGIN to
            ShopPricing(pricePermille = 880, flatShippingCents = 1_295, absent = setOf("KB-1005")),
        // Charges a small premium, ships free.
        ShopFixtures.NORDIC_ORIGIN to
            ShopPricing(pricePermille = 1_020, flatShippingCents = 0, outOfStock = setOf("KB-1002")),
    )

private fun shopHtml(
    title: String,
    accent: String,
    body: String,
): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>$title</title>
    <style>
      :root { color-scheme: light dark; --accent: $accent; --line: #dcdce2; --muted: #70707c; }
      @media (prefers-color-scheme: dark) { :root { --line: #303038; --muted: #8f8f9c; } }
      * { box-sizing: border-box; }
      body { margin: 0; padding: 10px 12px 20px;
             font: 12px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
      h1 { font-size: 14px; margin: 0 0 2px; color: var(--accent); }
      .muted { color: var(--muted); margin: 0 0 10px; font-size: 11px; }
      ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 7px; }
      li, .card, article { border: 1px solid var(--line); border-radius: 9px; padding: 8px 9px; display: grid; gap: 2px; }
      h3, h4, h5 { font-size: 12px; margin: 0; font-weight: 600; }
      .price, .cost, b { font-weight: 700; color: var(--accent); font-size: 13px; }
      .cost { margin: 0; }
      .frac { font-size: 10px; vertical-align: super; }
      .ship, .meta, em, .stock, i { font-size: 11px; color: var(--muted); font-style: normal; }
      .out { color: #b3261e; }
      a { color: var(--accent); font-size: 11px; }
      form { display: flex; gap: 5px; margin: 0 0 10px; }
      input[type=search] { flex: 1 1 auto; min-width: 0; font: inherit; padding: 5px 7px;
                           border: 1px solid var(--line); border-radius: 7px; background: transparent; color: inherit; }
      button { font: inherit; padding: 5px 10px; border: 0; border-radius: 7px;
               background: var(--accent); color: #fff; }
      table { border-collapse: collapse; width: 100%; font-size: 11px; }
      th, td { border-bottom: 1px solid var(--line); padding: 5px 4px; text-align: left; }
      th { color: var(--muted); font-weight: 600; }
      .amt { font-weight: 700; color: var(--accent); white-space: nowrap; }
      #cards, #grid { display: grid; gap: 7px; }
    </style></head>
    <body>
    $body
    </body></html>
    """.trimIndent()

private fun Int.asDecimal(): String = "${this / 100}.${(this % 100).toString().padStart(2, '0')}"

private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** A JS string literal for embedding into a fixture's inline script. */
private fun String.jsLiteral(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Percent-encodes everything outside the unreserved set.
 *
 * Hand-rolled because `commonMain` has no URL encoder and pulling one in for a query string would
 * be the tail wagging the dog.
 */
private fun String.urlEncoded(): String =
    buildString {
        for (byte in this@urlEncoded.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            // The ASCII check is not redundant: `isLetterOrDigit` is true for plenty of characters
            // above 0x7F, and each of those arrives here one UTF-8 byte at a time.
            if ((code < 0x80 && char.isLetterOrDigit()) || char in "-_.~") {
                append(char)
            } else {
                append('%').append(code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

/** `?q=foo&x=1` → the value of [name], percent-decoded. Null if absent. */
internal fun String.queryParam(name: String): String? =
    substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.percentDecoded()

private fun String.percentDecoded(): String {
    if ('%' !in this && '+' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        when {
            this[index] == '%' && index + 2 < length -> {
                val hex = substring(index + 1, index + 3).toIntOrNull(16)
                if (hex == null) {
                    bytes.add(this[index].code.toByte())
                    index++
                } else {
                    bytes.add(hex.toByte())
                    index += 3
                }
            }

            this[index] == '+' -> {
                bytes.add(' '.code.toByte())
                index++
            }

            else -> {
                for (byte in this[index].toString().encodeToByteArray()) bytes.add(byte)
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
