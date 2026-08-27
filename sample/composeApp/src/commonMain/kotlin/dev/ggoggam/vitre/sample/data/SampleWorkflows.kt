package dev.ggoggam.vitre.sample.data

import dev.ggoggam.vitre.core.workflow.Workflow
import dev.ggoggam.vitre.core.workflow.WorkflowStep.Extract.Source
import dev.ggoggam.vitre.core.workflow.handle
import dev.ggoggam.vitre.core.workflow.workflow
import dev.ggoggam.vitre.core.workflow.xpath
import kotlinx.serialization.Serializable

/**
 * The acknowledgement the fixture page waits for.
 *
 * A class rather than a hand-typed envelope string, which is the difference `postMessage` draws:
 * the id and type stay arguments — they are protocol — and only the payload is a value the caller
 * owns. Note there is no reply type here to go with it. The page does answer, but a step cannot
 * hand that answer back to this block; it lands in a variable, and `WorkflowEvent.Completed`
 * decodes it (`decodePayload<…>("fromPage")`).
 */
@Serializable
data class Ack(
    val seen: Boolean,
)

/**
 * The gallery, in two halves.
 *
 * The **fixture** workflows drive a page this module ships, loaded straight into the WebView with
 * no network. They are the ones that can demonstrate the bridge at all: `AwaitMessage` waits for
 * `window.vitre.postMessage`, and no third-party site will ever call it, so a bridge demo
 * pointed at somebody else's page can only ever hang. They are also the useful smoke test, because
 * a failure means the library broke rather than that a website was redesigned.
 *
 * The **live** workflows exist to show the same steps working against the real web, so they stick
 * to server-rendered pages and selectors that are part of those pages' structure rather than their
 * styling. Even so, they are the ones that will rot: a live site can change any day, and the
 * previous gallery is a good illustration — it drove MDN's search box, which has since moved inside
 * a shadow root where `document.querySelector` cannot reach it at all.
 */

object SampleWorkflows {
    /**
     * Everything the library does, against a page that cooperates: load, wait, type, click, receive
     * a bridge message, send one back, read the result.
     *
     * The `Click` → `AwaitMessage` pair is the interesting part. The click handler posts
     * *synchronously*, so the message has already arrived and gone by the time `AwaitMessage`
     * starts listening — which used to lose it permanently. The inbox buffers unread messages, so
     * the step matches one that predates it.
     */
    val BridgeRoundTrip =
        workflow(id = "bridge-round-trip", name = "Bridge round trip") {
            loadHtml(html = FIXTURE_HTML, baseUrl = FIXTURE_ORIGIN)
            waitFor("#ping", timeoutMs = 5_000)
            input(selector = "#subject", text = "Kotlin Multiplatform")
            click("#ping")
            awaitMessage(type = "pong", into = "fromPage", timeoutMs = 5_000)
            postMessage(type = "ack", payload = Ack(seen = true), id = "ack-1")
            waitFor("#status[data-acked=true]", timeoutMs = 5_000)
            extract(selector = "#status", into = "status")
        }

    /**
     * The loop an agent actually runs: look at the page, then act on what you saw.
     *
     * Every other workflow here was written by someone with the page open in front of them, and its
     * selectors encode that knowledge. This one is written the way an agent has to work — it does
     * not name a single selector after the first wait. `Snapshot` walks the document and hands back
     * an element per line with a handle attached; the steps after it address those handles.
     *
     * The handles are spelled out because this page ships with the sample and its structure is
     * fixed, so the walk assigns the same numbers every run: `e1` the heading, `e2` the paragraph,
     * `e3` the text field, `e4` the button, `e5` the status line. An agent would read them out of
     * the snapshot instead of knowing them — which is the entire point — but hard-coding them here
     * turns the demo into a check on the walk itself: if the roles it recognises ever change, this
     * workflow fails rather than quietly drifting.
     */
    val AgentsEyeView =
        workflow(id = "agents-eye-view", name = "Snapshot & handles (agent's eye view)") {
            loadHtml(html = FIXTURE_HTML, baseUrl = FIXTURE_ORIGIN)
            waitFor("#ping", timeoutMs = 5_000)
            snapshot(into = "page")
            // From here on, no selectors — only things the snapshot reported.
            input(handle("e3"), text = "typed by handle")
            click(handle("e4"))
            awaitMessage(type = "pong", into = "fromPage", timeoutMs = 5_000)
            postMessage(type = "ack", payload = Ack(seen = true), id = "ack-1")
            waitFor("#status[data-acked=true]", timeoutMs = 5_000)
            // The same element the snapshot called e5, read back after the page changed it.
            extract(handle("e5"), into = "status")
        }

    /**
     * The attribute/property distinction, which is subtle enough to be worth its own demo.
     *
     * Typing into a field assigns the DOM property `el.value`. It does not touch the `value`
     * attribute, which is only the field's initial value as written in the markup — so the two
     * extracts below disagree, and reading the attribute is almost never what you meant.
     */
    val FormEcho =
        workflow(id = "form-echo", name = "Form echo (attribute vs property)") {
            loadHtml(html = FIXTURE_HTML, baseUrl = FIXTURE_ORIGIN)
            waitFor("#subject", timeoutMs = 5_000)
            input(selector = "#subject", text = "typed by the workflow")
            extract(selector = "#subject", into = "markupValue", from = Source.Attribute("value"))
            extract(selector = "#subject", into = "liveValue", from = Source.Property("value"))
        }

    /**
     * A search-results table pulled out of a page the sample ships — the same shape as the Amazon
     * workflow below, but deterministic, so it can be the smoke test for `ExtractRows` and XPath.
     *
     * Every column here is chosen because CSS cannot express it:
     *  - the title lives in an attribute, and XPath can select the attribute *as a node*;
     *  - "in stock" is matched on its text content;
     *  - the rating is matched on a substring of an attribute;
     *  - the seller is reached by walking *up* from the price and back down.
     */
    val FixtureSearchResults =
        workflow(id = "fixture-search-results", name = "Search results table (XPath)") {
            loadHtml(html = RESULTS_HTML, baseUrl = FIXTURE_ORIGIN)
            waitFor(xpath("//li[@data-sku]"), timeoutMs = 5_000)
            extractRows(rows = xpath("//li[@data-sku]"), into = "results", limit = 10) {
                // `.` is the row itself, so this reads the row's own attribute.
                column("sku", xpath("."), from = Source.Attribute("data-sku"))
                // An attribute node. Its `textContent` is the attribute's value, which is why this
                // reads as Text and not as Attribute.
                column("title", xpath(".//h3/@data-full-title"))
                column("price", xpath(".//span[@class='price']"))
                // Matched on the text a human reads, not on a class hook.
                column("stock", xpath(".//*[normalize-space()='In stock']"))
                column("rating", xpath(".//*[contains(@aria-label,'out of 5')]"), from = Source.Attribute("aria-label"))
                // Up to the row, then back down — CSS has no parent combinator.
                column("seller", xpath(".//span[@class='price']/ancestor::li[1]//span[@class='seller']"))
            }
        }

    /**
     * The real thing: search Amazon and list what comes back.
     *
     * Worth saying plainly — this is the workflow most likely to break, and not because of anything
     * here. Amazon serves different markup to different clients, reshuffles class names, and will
     * show a bot check instead of results whenever it feels like it. When that happens the run
     * fails at the `WaitFor`, which is the correct outcome and the reason the fixture above exists.
     *
     * The locators are written to survive as much of that as they can: the search box is matched by
     * *any* of the ids Amazon's layouts use, the row container is a `data-` attribute rather than a
     * class, and the submit button is found by walking up to the enclosing form rather than by
     * guessing its own id.
     */
    val AmazonSearchResults =
        workflow(id = "amazon-search", name = "Amazon search results") {
            navigate("https://www.amazon.com/")
            // One locator for every layout Amazon serves. `field-keywords` is what the homepage
            // form uses and `k` is what the results page uses, so both arms are needed — matching
            // only `k` finds the box on a search page and nothing at all on the front page.
            val searchBox =
                xpath(
                    "//input[@id='twotabsearchtextbox' or @id='nav-search-keywords' " +
                        "or @name='k' or @name='field-keywords']",
                )
            waitFor(searchBox, timeoutMs = 20_000)
            input(searchBox, text = "mechanical keyboard")
            // Submit by walking up from *the box that was actually found* to its form, rather than
            // by naming a field again. Repeating a locator here is what broke this workflow: the
            // submit was hung off `//input[@name='k']`, which matches nothing on the homepage, so
            // `Click` resolved to null and did nothing — and because a selector matching nothing is
            // a legitimate no-op (see HandleLocatorTest), the run sailed past it and failed twenty
            // seconds later at the `WaitFor` below, blaming search results for a button that was
            // never pressed. Deriving one locator from the other keeps the two in step.
            //
            // `ancestor::form[1]` is the nearest enclosing form — there is no way to say that in
            // CSS, and hard-coding the button's id has broken before.
            click(xpath("${searchBox.expression}/ancestor::form[1]//*[@type='submit']"))
            waitFor(xpath("//div[@data-component-type='s-search-result']"), timeoutMs = 25_000)
            // Amazon renders results as you approach them, so extracting straight after the first
            // row appears returns only the handful above the fold. Scrolling asks for the rest; the
            // positional predicate below is what waits for them to arrive.
            evaluateJs(script = "(function(){window.scrollTo(0,document.body.scrollHeight);return 'scrolled';})()")
            // Sponsored rows carry `AdHolder`, and a predicate drops them here rather than
            // downstream. Matching on the class rather than on the word "Sponsored" is deliberate:
            // this device is served Amazon in Korean, and any locator keyed to English text
            // silently returns nothing.
            val organicRows =
                xpath("//div[@data-component-type='s-search-result'][not(contains(@class,'AdHolder'))]")
            // `(...)[6]` — the sixth match, not "a match with index 6". XPath positions are 1-based
            // and the parentheses matter: without them the predicate applies per parent rather than
            // to the whole node set.
            waitFor(xpath("(${organicRows.expression})[6]"), timeoutMs = 15_000)
            extractRows(rows = organicRows, into = "results", limit = 8) {
                column("asin", xpath("."), from = Source.Attribute("data-asin"))
                // Amazon truncates the visible title with a line clamp; the untruncated one is on
                // the h2's aria-label.
                column("title", xpath(".//h2[@aria-label]"), from = Source.Attribute("aria-label"))
                // `.a-offscreen` is the screen-reader price — already normalised, where the visible
                // one is split across superscript spans.
                column("price", xpath(".//span[@class='a-offscreen']"))
                // Same reasoning as the row predicate: `a-icon-alt` is a class, so it survives
                // translation where `contains(@aria-label,'out of 5 stars')` does not.
                column("rating", xpath(".//span[@class='a-icon-alt']"))
                column("url", xpath(".//a[contains(@class,'a-link-normal')]"), from = Source.Property("href"))
            }
            // XPath aggregates over a node set, which is the one thing here with no CSS equivalent
            // at all — CSS can select nodes but never count them.
            evaluateJs(
                script =
                    "document.evaluate(\"count(${organicRows.expression})\",document,null,1,null).numberValue",
                into = "organicResultCount",
            )
        }

    /**
     * Google Maps: a page that is an application rather than a document.
     *
     * Every other live workflow here reads a server-rendered page, where the answer is in the HTML
     * before any script runs and the only question is which selector names it. Maps is the other
     * kind. It arrives as a map, the results do not exist in the DOM at all until a control is
     * pressed, and a modal sits over the whole thing on first load. Three of the four steps below
     * are there to get the page into a state where there is anything to extract — which is what
     * driving a real application usually costs.
     *
     * It is also the workflow that found the `intent://` gap. Maps reads the `wv` token in an
     * Android WebView's user agent and redirects the main frame to
     * `intent://…;package=com.google.android.apps.maps;end`, whether or not the app is installed. A
     * WebView cannot render that, so before `PageLoadWebViewClient.shouldOverrideUrlLoading`
     * existed the navigation died at step one with `ERR_UNKNOWN_URL_SCHEME` and took the page with
     * it. Refusing the handoff leaves the web page in place, which is what the rest of this reads.
     *
     * `hl=en` in the URL is load-bearing, not cosmetic. The row locator matches an `aria-label`
     * containing the word "stars", and Maps writes that label in the device's language: the phone
     * this was developed against is served Korean, where the same label reads "별표" and every
     * locator below matches nothing. The Amazon workflow draws this lesson the other way round, by
     * keying on class names *because* it cannot pin the language; here the language can be pinned,
     * so it is.
     */
    val GoogleMapsPlaces =
        workflow(id = "google-maps-places", name = "Google Maps places nearby") {
            navigate("https://www.google.com/maps/search/coffee+near+Seoul+Station/?hl=en")
            // Maps reads the `wv` token an Android WebView puts in its user agent and offers to
            // hand the query to the installed app. The offer is a modal: it covers the controls
            // below it, so nothing else on this page can be clicked until it is gone.
            //
            // Matched on `jsaction` rather than on a class. Every class on this page is minified
            // and reminted whenever Google deploys, but `jsaction` carries the handler's name —
            // `dismiss_action` — and names what the button *does*, which is the nearest thing to a
            // contract the page offers. It is also language-independent, unlike matching the
            // button's "Stay on web" text.
            val dismissAppPrompt = xpath("//button[contains(@jsaction,'dismiss_action')]")
            waitFor(dismissAppPrompt, timeoutMs = 25_000)
            click(dismissAppPrompt)
            // Mobile Maps shows results as pins first and as a list second, and "second" is literal:
            // before this click not one result row exists in the document. That is why the wait for
            // rows is below rather than straight after the navigation — waiting there finds nothing
            // and blames the search for a view that was never opened.
            //
            // The button's own class is minified and its `jsaction` is a generated pane id, so the
            // one durable thing about it is the label it wraps. `.//*` rather than `.//span`
            // because which element holds the text is exactly the sort of detail a redesign moves.
            val viewList = xpath("//button[.//*[normalize-space(text())='View list']]")
            waitFor(viewList, timeoutMs = 15_000)
            click(viewList)
            // A result row has no id, no `data-` attribute and no role — its container is a
            // minified class and nothing else. The one node in the row under a stable contract is
            // the rating, which is a `role="img"` with the score spelled out in its `aria-label`
            // because a row of stars has to be readable aloud. So the row is found by anchoring on
            // that and walking *up*, which is the thing CSS cannot do at all.
            //
            // Anchoring on the rating rather than on the name is deliberate. Going down from the
            // name matches a sixth "row": the sponsored block wraps the entire results pane and
            // carries a heading of its own ("Why this ad?"), so the nearest enclosing container
            // holding both a heading and a rating is the whole pane. From the rating the same
            // expression lands on the tight container instead, and the ad wrapper never matches.
            //
            // What it costs is honest and worth stating: a place Maps has no rating for is not a
            // row here at all. Rows are defined by the anchor, and the anchor is the rating.
            val row =
                xpath(
                    "//span[@role='img'][contains(@aria-label,'stars')]" +
                        "/ancestor::div[.//div[contains(@class,'fontHeadlineSmall')]][1]",
                )
            waitFor(row, timeoutMs = 20_000)
            // Maps renders the list a screen at a time. On a phone-sized viewport the pane holds
            // one row when it first appears and each scroll to the bottom loads roughly one more,
            // so extracting straight after the wait above returns a single place — which reads as
            // Google refusing to answer when in fact nothing has asked for the rest yet.
            //
            // This is the one script here that has to *wait*, and it can: `evaluateJs` awaits a
            // promise rather than returning it, so the loop lives in the page instead of being
            // spread across a dozen alternating scroll and wait steps. It gives up early when a
            // pass stops adding rows, which is what keeps it inside the 15s a script has to settle.
            //
            // It scrolls every scrollable container rather than the one holding the list, for the
            // same reason the row locator walks up from the rating: that container's class is
            // minified like all the others, while "has more content than it can show" is a property
            // no redesign can rename.
            //
            // The count it returns is the honest one — how many rows the pane ended up holding, as
            // against the ten `limit` would have taken.
            evaluateJs(
                script =
                    """
                    (async () => {
                      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                      const count = () => document.evaluate(
                        "count(${row.expression})", document, null, 1, null).numberValue;
                      let seen = count();
                      let dry = 0;
                      for (let pass = 0; pass < 8 && seen < 8; pass++) {
                        document.querySelectorAll('*').forEach((el) => {
                          if (el.scrollHeight > el.clientHeight + 40) el.scrollTop = el.scrollHeight;
                        });
                        await sleep(1100);
                        const now = count();
                        dry = now === seen ? dry + 1 : 0;
                        seen = now;
                        if (dry >= 2) break;
                      }
                      return seen;
                    })()
                    """.trimIndent(),
                into = "placeCount",
            )
            // The rating's own block, which the rest of the row hangs off. `fontHeadlineSmall` and
            // `fontBodyMedium` below are the exception to the minification: they come from Google's
            // shared typography sheet, are shared across their products, and say what a thing *is*
            // rather than how this build happened to name it.
            val ratingBlock = ".//span[@role='img'][contains(@aria-label,'stars')]/ancestor::div[2]"
            extractRows(rows = row, into = "places", limit = 10) {
                column("name", xpath(".//div[contains(@class,'fontHeadlineSmall')]"))
                // The label reads "4.3 stars 105 Reviews" — score and review count in one string,
                // and the only place the review count appears in a machine-readable form at all.
                // The visible text next to it is "4.3(105)".
                column(
                    "rating",
                    xpath(".//span[@role='img'][contains(@aria-label,'stars')]"),
                    from = Source.Attribute("aria-label"),
                )
                // Category, address and opening hours are three spans in two divs that follow the
                // rating block, none of which carries a usable hook of its own. `following-sibling`
                // reaches them by position from the one node that does — the second axis query in
                // this workflow with no CSS equivalent.
                column("category", xpath("$ratingBlock/following-sibling::div[1]/div[1]/span[1]"))
                // `last()`, not `[2]`: some rows carry an extra empty span between the category and
                // the address, and indexing from the front reads the separator for one row in five.
                column("address", xpath("$ratingBlock/following-sibling::div[1]/div[1]/span[last()]"))
                // Positional, and so occasionally wrong in a way worth leaving visible: a place
                // Google has an editorial blurb for puts that line here instead, and this column
                // reads "Iconic coffeehouse chain" for the Starbucks row. There is no attribute
                // distinguishing the two, so the choice is a wrong value in one row of eight or no
                // column at all — the same trade the fixture's missing-price row is there to model.
                column("hours", xpath("$ratingBlock/following-sibling::div[1]/div[2]"))
            }
        }

    /** The smallest possible real-world read. Static, server-rendered, unlikely to move. */
    val ExampleDotComTitle =
        workflow(id = "example-title", name = "example.com title") {
            navigate("https://example.com")
            waitFor("h1", timeoutMs = 10_000)
            extract(selector = "h1", into = "title")
        }

    /**
     * A real page with real structure: text and an attribute off the same element. `.titleline > a`
     * is Hacker News' own markup rather than a styling hook, which is about as durable as a
     * third-party selector gets.
     */
    val HackerNewsTopStory =
        workflow(id = "hn-top-story", name = "Hacker News top story") {
            navigate("https://news.ycombinator.com/")
            waitFor(".titleline > a", timeoutMs = 15_000)
            extract(selector = ".titleline > a", into = "headline")
            // An href genuinely is an attribute — it is in the markup, and nothing has reassigned
            // the property.
            extract(selector = ".titleline > a", into = "url", from = Source.Attribute("href"))
        }

    /**
     * What the chat screen puts on the WebView before the agent is asked anything.
     *
     * Not in [all], because it is not a demonstration of the workflow engine — it is the host app
     * doing what a host app does, putting a page up. Everything after this is the agent's, and it
     * reaches the page only through MCP tools.
     *
     * The results fixture is the right page to reason about: it has a table worth reading, a row
     * with a field missing, and no network between it and the demo.
     */
    val ChatFixture =
        workflow(id = "chat-fixture", name = "Load the results fixture") {
            loadHtml(html = RESULTS_HTML, baseUrl = FIXTURE_ORIGIN)
            waitFor(xpath("//li[@data-sku]"), timeoutMs = 5_000)
        }

    val all: List<Workflow> =
        listOf(
            AgentsEyeView,
            BridgeRoundTrip,
            FormEcho,
            FixtureSearchResults,
            AmazonSearchResults,
            GoogleMapsPlaces,
            ExampleDotComTitle,
            HackerNewsTopStory,
        )
}

/**
 * A synthetic origin. It never resolves and is never fetched — the document is handed to the
 * WebView directly — but giving the page an origin rather than an opaque one keeps relative URLs
 * and storage behaving like a real page's, and on Android it is what the bridge's allowed-origin
 * rule matches.
 */
private const val FIXTURE_ORIGIN = "https://fixture.vitre.test/"

private val RESULTS_HTML =
    """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <!-- A snapshot leads with the title, and it is the first thing an agent reads to work out
             which page it is looking at, so both fixtures carry one. -->
        <title>Vitre search results</title>
        <style>
          :root { color-scheme: light dark; }
          body { font: 15px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px; }
          h1 { font-size: 18px; margin: 0 0 4px; }
          .hint { color: #666; margin: 0 0 16px; font-size: 13px; }
          ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
          li { border: 1px solid #ddd; border-radius: 12px; padding: 12px; display: grid; gap: 4px; }
          h3 { font-size: 15px; margin: 0; }
          .price { font-weight: 600; }
          .seller { color: #666; font-size: 13px; }
          .stock { font-size: 13px; color: #1a7f37; }
          .out { color: #b3261e; }
          @media (prefers-color-scheme: dark) {
            body { background: #111; color: #eee; }
            li { border-color: #333; }
            .hint, .seller { color: #999; }
          }
        </style>
      </head>
      <body>
        <h1>Search results</h1>
        <p class="hint">A stand-in for a real results page, with the same awkward shapes.</p>
        <ul id="results">
          <li data-sku="KB-1001">
            <h3 data-full-title="Aula F75 Pro Wireless Mechanical Keyboard, 75% Hot-Swappable, RGB">Aula F75 Pro Wireless Mechanical…</h3>
            <span class="price">${'$'}89.99</span>
            <span class="stock">In stock</span>
            <span aria-label="4.6 out of 5 stars">★★★★☆</span>
            <span class="seller">Aula Official Store</span>
          </li>
          <li data-sku="KB-1002">
            <h3 data-full-title="Keychron K2 V2 Wireless Mechanical Keyboard, Brown Switches, 84 Keys">Keychron K2 V2 Wireless Mechanical…</h3>
            <span class="price">${'$'}79.00</span>
            <span class="stock out">Out of stock</span>
            <span aria-label="4.4 out of 5 stars">★★★★☆</span>
            <span class="seller">Keychron</span>
          </li>
          <li data-sku="KB-1003">
            <h3 data-full-title="Logitech MX Mechanical Mini, Low Profile, Tactile Quiet">Logitech MX Mechanical Mini…</h3>
            <span class="price">${'$'}149.99</span>
            <span class="stock">In stock</span>
            <span aria-label="4.5 out of 5 stars">★★★★☆</span>
            <span class="seller">Logitech</span>
          </li>
          <li data-sku="KB-1004">
            <!-- Deliberately missing a price and a rating: a real results page has rows like this,
                 and each field must fail on its own without shifting the other rows. -->
            <h3 data-full-title="Unbranded 60% Mechanical Keyboard Kit, Barebones">Unbranded 60% Mechanical Keyboard Kit…</h3>
            <span class="stock">In stock</span>
            <span class="seller">Third-party seller</span>
          </li>
        </ul>
      </body>
    </html>
    """.trimIndent()

private val FIXTURE_HTML =
    """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <!-- A snapshot leads with the title, and it is the first thing an agent reads to work out
             which page it is looking at, so both fixtures carry one. -->
        <title>Vitre fixture</title>
        <style>
          :root { color-scheme: light dark; }
          body {
            font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            margin: 0; padding: 24px; display: grid; gap: 16px; align-content: start;
          }
          h1 { font-size: 20px; margin: 0; }
          p { margin: 0; color: #666; }
          #status { padding: 12px; border-radius: 10px; background: #eee; font-variant-numeric: tabular-nums; }
          #status[data-acked=true] { background: #d8f0d8; }
          input, button { font: inherit; padding: 10px 12px; border-radius: 10px; border: 1px solid #bbb; }
          button { background: #1a73e8; color: white; border-color: transparent; }
          @media (prefers-color-scheme: dark) {
            body { background: #111; color: #eee; }
            p { color: #999; }
            #status { background: #222; }
            #status[data-acked=true] { background: #1e3a1e; }
            input { background: #1b1b1b; color: #eee; border-color: #444; }
          }
        </style>
      </head>
      <body>
        <h1>Vitre fixture</h1>
        <p>A page that speaks the bridge protocol, so the demo does not depend on the network.</p>
        <input id="subject" type="text" value="initial markup value">
        <button id="ping">Send pong to native</button>
        <div id="status" data-acked="false">waiting</div>
        <script>
          // Everything is scoped to this function rather than declared at the top level. A global
          // `var status` does not create a variable at all — `window.status` is a legacy accessor
          // that coerces whatever it is given to a string, so the element silently becomes
          // "[object HTMLDivElement]" and every write to it is a no-op.
          (function () {
            var statusEl = document.getElementById('status');
            var subjectEl = document.getElementById('subject');

            // The documented way to wait for the bridge: check first, listen only if the check
            // fails. On Android and iOS the check is the branch that runs, because the bridge is
            // installed before any page script, and a bare listener would hang: the
            // `vitre:ready` event was dispatched before this script existed to hear it. On the
            // desktop CEF injects from `onLoadStart`, which races this very script, so either
            // branch can be the one that runs — which is why this page carries both. See
            // BridgeReady.
            function whenBridgeReady(fn) {
              if (window.vitre) { fn(); }
              else { window.addEventListener('vitre:ready', fn, { once: true }); }
            }

            // Nothing waits on the status line's *initial* text — the workflows wait for #ping and
            // for data-acked — so it is free to say something more useful than "waiting".
            whenBridgeReady(function () {
              statusEl.textContent = 'bridge ready';
            });

            // Synchronous on purpose: this is the timing that used to lose the message, because the
            // workflow only started listening after the click step had returned.
            document.getElementById('ping').addEventListener('click', function () {
              statusEl.textContent = 'pong sent to native';
              window.vitre.postMessage(JSON.stringify({
                id: 'pong-1',
                type: 'pong',
                payload: { subject: subjectEl.value }
              }));
            });

            var replies = 0;
            window.addEventListener('vitre', function (event) {
              statusEl.textContent = 'acknowledged by native';
              statusEl.setAttribute('data-acked', 'true');
              // The reply convention, in the two lines it takes on the page: echo the incoming
              // message's id back as `replyTo`, keeping an id of one's own. That is all
              // `bridge.request` needs to match an answer to its question. A workflow that only
              // waits by type never looks at the field, which is why the steps above still pass.
              var incoming = null;
              try { incoming = JSON.parse(event.data); } catch (e) { return; }
              if (!incoming || !incoming.id) { return; }
              replies += 1;
              window.vitre.postMessage(JSON.stringify({
                id: 'reply-' + replies,
                type: 'ack',
                replyTo: incoming.id,
                payload: { seen: true }
              }));
            });
          })();
        </script>
      </body>
    </html>
    """.trimIndent()
