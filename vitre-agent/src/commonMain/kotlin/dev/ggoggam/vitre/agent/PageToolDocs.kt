package dev.ggoggam.vitre.agent

/**
 * What a model is told about each page action, and about the arguments they share.
 *
 * These are not documentation. They are the prompt: a description here is the only thing a model
 * reads before deciding whether to call a tool and with what, so they say what the action does *to
 * the page*, what it gives back, and which tool to reach for instead when this is the wrong one.
 * The misuse worth pre-empting throughout is an agent guessing a CSS selector for a page it has
 * never looked at.
 *
 * They live in `vitre-agent` rather than in an adapter because an adapter is a way of *delivering*
 * them, not a reason to have a second opinion about them. MCP renders them into JSON Schema and
 * Koog into `@LLMDescription`s; a model that meets Vitre through either one meets the same tools.
 *
 * Every entry is `const` so that it can also be an annotation argument, which is what Koog's
 * schema generator reads.
 */
object PageToolDocs {
    // ── Shared arguments ───────────────────────────────────────────────────────────────────────

    const val SESSION: String =
        "Which WebView to act on. Omit it when there is only one — `list_sessions` says how many " +
            "there are."

    const val LEASE: String =
        "The lease from `acquire_lease`, if this call is part of a sequence that must not be " +
            "interleaved with another caller's."

    const val REF: String =
        "Handle from a previous `snapshot` of the current page, e.g. \"e7\". Prefer this: it names " +
            "an element you have actually seen, and fails loudly if the page has changed under you."

    const val CSS: String =
        "CSS selector. Use only when you know the page's markup — do not guess one; take a " +
            "`snapshot` and use the `ref` it gives you instead."

    const val XPATH: String =
        "XPath expression, for what CSS cannot reach: matching on visible text " +
            "(//button[normalize-space()='Add to cart']), walking up with ancestor::, or " +
            "selecting an attribute as a node."

    const val FROM: String = "\"text\" (default), \"attribute\" or \"property\"."

    const val NAME: String = "Which attribute or property, when `from` is one of those."

    const val URL: String = "Absolute URL to load."

    const val TEXT: String = "The text to put in the field, replacing what is there."

    const val SCRIPT: String = "A JavaScript expression, e.g. `document.title`."

    const val MESSAGE: String = "The payload, usually JSON the page knows how to read."

    const val MESSAGE_TYPE: String = "The `type` field of the message to wait for."

    const val LEASE_ID: String = "The lease id from `acquire_lease`."

    const val COLUMN_CSS: String = "CSS selector, relative to the row."

    const val COLUMN_XPATH: String = "XPath starting `.//`, relative to the row."

    const val COLUMNS: String =
        "Field name to locator, resolved within each row. XPath columns must start `.` — use " +
            "{\"xpath\": \".\"} to read the row element itself, e.g. its own data attribute. A CSS " +
            "column can only reach *inside* the row, so there is no CSS spelling of \"the row itself\"."

    const val LIMIT: String = "Maximum rows (default 20)."

    const val TIMEOUT: String = "How long to wait for the element (default 10000)."

    /** For `await_message`, which waits for a message rather than an element. */
    const val WAIT_TIMEOUT: String = "How long to wait (default 10000)."

    const val MAX_NODES: String =
        "Cap on elements reported (default 200). The result goes into your context, so raise it " +
            "only when the outline says it was truncated."

    const val TTL: String = "How long to hold it before it expires (default 30000)."

    // ── Tools ──────────────────────────────────────────────────────────────────────────────────

    const val LIST_SESSIONS: String =
        "Lists the WebViews you can drive. Call it first if you do not know whether there is more " +
            "than one; with a single session every other tool's `session` argument can be omitted."

    const val SNAPSHOT: String =
        "Shows what is on the page: the interactive and text-bearing elements, each with a `ref` " +
            "handle you pass to `click`, `type` and `extract`. This is how you look at a page — " +
            "start here, and take a fresh one after anything that changes the page, because refs " +
            "from a previous document stop resolving. Returns an indented outline, not HTML."

    const val NAVIGATE: String =
        "Loads a URL in the WebView and waits for the page to finish loading. Discards every `ref` " +
            "from the previous page."

    const val CLICK: String =
        "Clicks an element, waiting for it to appear first. Fails if it never does, rather than " +
            "reporting a click that landed on nothing."

    const val TYPE: String =
        "Replaces the value of an input or textarea with `text` and fires the input and change " +
            "events a page listens for. Does not press Enter — click the form's button, which a " +
            "`snapshot` will show you."

    const val WAIT_FOR: String =
        "Waits until an element is present. Use it after an action that loads content " +
            "asynchronously, before reading what it loaded."

    const val EXTRACT: String =
        "Reads the text, an attribute, or a live DOM property of a single element. For a field the " +
            "user has typed in, use `from: \"property\"` with `name: \"value\"` — the `value` " +
            "*attribute* holds the markup's original value and does not track typing. For a list of " +
            "results use `extract_rows`."

    const val EXTRACT_ROWS: String =
        "Reads one record per matching row, with each column resolved inside that row, as a JSON " +
            "array. Use this for search results and tables rather than many `extract` calls: a row " +
            "missing a field yields an empty string in that one record instead of shifting every " +
            "later record onto the wrong row."

    const val EVALUATE: String =
        "Evaluates a JavaScript *expression* in the page and returns its value. The escape hatch " +
            "for what the other tools cannot express; prefer them, since they resolve elements " +
            "through the same guarded path. Wrap statements in an IIFE — a bare statement list will " +
            "not parse."

    const val SEND_MESSAGE: String =
        "Delivers a string to the page as a `MessageEvent('vitre')` on `window`. Only useful for a " +
            "page written to listen for it — the host app's own pages, not a third-party site."

    const val AWAIT_MESSAGE: String =
        "Waits for the page to post a `{id, type, payload}` message of the given type via " +
            "`window.vitre.postMessage`, and returns it. Matches one the page already sent as well " +
            "as one still to come, so it is safe to call after the action that triggers it."

    const val ACQUIRE_LEASE: String =
        "Takes a WebView for several calls in a row, so no other caller — another agent, a " +
            "workflow, a button in the app — can act on the page in between. Needed when a later " +
            "call depends on what an earlier one left on screen, e.g. wait then read. Release it as " +
            "soon as the sequence is done; it also expires on its own so a client that stops cannot " +
            "wedge the page."

    const val RELEASE_LEASE: String = "Gives a leased WebView back, letting queued callers proceed."

    /**
     * What the model is told the whole toolset is for, once, before it calls anything.
     *
     * MCP delivers this as the server's `instructions`; a Koog host puts it in the agent's system
     * prompt, which is the same job under a different name.
     */
    const val INSTRUCTIONS: String =
        """
Drives a WebView inside a mobile app: the app's own embedded browser, not a desktop one.

Look before you act. Call `snapshot` to see the page as a list of elements, each with a `ref`
handle, and act on those handles. Do not guess CSS selectors for a page you have not looked at —
a selector that matches nothing makes most actions fail, and the ones that would silently do
nothing are guarded so they fail too.

Handles belong to the document that issued them. Anything that replaces the page — `navigate`, a
click that follows a link — invalidates every ref, and using a stale one is an error rather than
a wrong element. Take a fresh snapshot after such a step.

You are not the only caller. The app's own UI and any workflow it runs share this WebView, and
single operations are ordered against each other but sequences are not. When a later call depends
on what an earlier one left on screen, hold the page with `acquire_lease` and pass the lease id,
then `release_lease`. If those two tools are not in your list, the page is already being held for
you for as long as you are running, and there is nothing to acquire.
"""
}
