package dev.ggoggam.vitre.core.workflow

/**
 * How a step finds the element(s) it acts on.
 *
 * CSS is the better default — shorter, and what most page authors think in. XPath earns its place
 * when CSS runs out, which on real pages happens sooner than expected:
 *
 *  - **Matching on text.** `//button[normalize-space()='Add to cart']` has no CSS equivalent.
 *  - **Walking upwards.** `//span[@id='price']/ancestor::div[@data-asin]` — CSS only descends, so
 *    without this you have to find the container first and hope its own selector is stable.
 *  - **Selecting attributes as nodes.** `.//h2/@aria-label` returns the attribute itself, which is
 *    useful when the full text lives in an attribute and the visible text is truncated — exactly
 *    Amazon's product titles.
 *  - **Positional and axis queries** — `following-sibling::`, `[last()]`, `[position()<4]`.
 *
 * Neither pierces shadow DOM. That is a property of the page, not of the query language, and no
 * choice here fixes it.
 */
sealed interface Locator {
    data class Css(
        val selector: String,
    ) : Locator

    data class XPath(
        val expression: String,
    ) : Locator

    /**
     * A handle issued by [WorkflowStep.Snapshot], naming one element the page has already shown us.
     *
     * The other two locators describe *how to search*; this one names a specific element found
     * earlier, which is what an agent needs. An agent does not know the page's selectors — asking it
     * to guess one is asking it to hallucinate — so it snapshots, reads back `e7 button "Add to
     * cart"`, and acts on `e7`. No selector is invented at any point.
     *
     * Handles live in the page, in the document that issued them, and both halves of that matter.
     * A navigation destroys the registry along with the document, so a handle from the previous page
     * fails loudly instead of resolving against a same-shaped element on the new one. Within a
     * document they are never recycled: a second snapshot mints new numbers rather than reassigning
     * old ones, because an agent holding `e3` across two snapshots must get the element it saw or an
     * error, never a different element that happens to sit where the old one did.
     *
     * A handle is absolute, so unlike the other two it ignores the scope it is resolved in — as a
     * [WorkflowStep.ExtractRows] column it reads the same element for every row, which is almost
     * never what a column is for.
     */
    data class Handle(
        val ref: String,
    ) : Locator
}

/**
 * For failure messages and the sample's step list. Names the language, because "no match for
 * `//h2[@aria-label]`" is only actionable if you know it was not being read as CSS.
 */
fun Locator.describe(): String =
    when (this) {
        is Locator.Css -> "css `$selector`"
        is Locator.XPath -> "xpath `$expression`"
        is Locator.Handle -> "handle `$ref`"
    }

/** `css("#results .item")` — the shorthand steps default to anyway. */
fun css(selector: String): Locator.Css = Locator.Css(selector)

/**
 * `xpath("//h2[@aria-label]")`.
 *
 * Inside [WorkflowStep.ExtractRows] columns, start the expression with `.//` so it is evaluated
 * relative to the row. A leading `//` searches the whole document from any context node, so every
 * row would report the first row's values — the classic XPath scoping mistake, and a quiet one
 * because the result looks plausible.
 */
fun xpath(expression: String): Locator.XPath = Locator.XPath(expression)

/**
 * `handle("e7")` — an element a [WorkflowStep.Snapshot] of the *current* document reported.
 *
 * Callers do not make these up; they come back from a snapshot. See [Locator.Handle] for why a
 * stale one is an error rather than a near-miss.
 */
fun handle(ref: String): Locator.Handle = Locator.Handle(ref)
