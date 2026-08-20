# `vitre-mcp` — driving the WebView from an agent

`docs/CONCURRENCY.md` ends with a list of what was missing before an agent could use this library,
and where an MCP server would have to sit. This document is what got built, and why it took the
shape it did.

The short version: **an agent cannot use a selector-addressed API**, because it has never seen the
page. Everything below follows from that.

## The gap this closes

A workflow is written by someone with the page open in a browser. Its selectors are the residue of
that: `#buy`, `.titleline > a`, `//div[@data-component-type='s-search-result']`. None of them can be
produced by a caller that has not looked at the page, and asking a model to produce one anyway is
asking it to guess — a guess that fails silently, because `document.querySelector('#buy')?.click()`
against a page with no `#buy` succeeds and does nothing.

So the module rests on one new primitive in core, `Snapshot`, and one new locator, `Locator.Handle`:

```
snapshot  →  heading "Search results"          [ref=e1]
               textbox value="keyboard"        [ref=e3]
               link "Wireless keyboard"        [ref=e4]
               button "Add to cart"            [ref=e6]

click(ref=e6)
```

The agent never writes a selector. It reads handles out of a snapshot and passes them back.

## Handles

A handle names an element the page has already shown us, and it lives *in the page* — in a registry
on `window` belonging to the document that issued it. That gives it the right lifetime for free: a
navigation destroys the document and the registry with it, so a handle from the previous page stops
resolving at exactly the moment it stops meaning anything.

Two rules make a wrong action impossible rather than merely unlikely:

- **Numbers are never reused.** A second snapshot mints fresh refs for elements it has not seen and
  keeps existing refs for elements it has. Were handles indices into the latest snapshot, an agent
  that snapshotted, thought, and then acted on `e3` would act on whatever had since taken third
  place — silently, and plausibly.
- **Failure to resolve is reported, not absorbed.** Every expression the engine generates turns a
  missing element into `null` and carries on. For a selector that is correct; for a handle it is a
  lie, since a handle asserts the element was seen. So handle-addressed steps are vetted first, and
  the three failures are distinguished because the agent's next move differs:

  | Status | What the agent is told |
  |---|---|
  | `no-snapshot` | No snapshot has been taken of this document. Take one. |
  | `unknown` | This document never issued that handle — it is from a previous page. |
  | `detached` | The element existed and has been removed. Snapshot again; the page has changed. |

That vetting costs one extra round trip, and only for handle-addressed steps. Selector-addressed
workflows written before handles existed pay nothing.

### A bug worth recording

The first version of the handle expression was `(registry ? registry.get(ref) : null) || null`,
unbracketed. Every unit test passed, including one asserting the generated click ended in
`?.click()`. On a device, nothing happened when the button was clicked, and the step went green.

`?.` and `!==` both bind tighter than `||`, so `X||null?.click()` groups as `X||(null?.click())` —
the lookup is evaluated, the method is never called — and `X||null!==null` groups as
`X||(null!==null)`, so a `WaitFor` on a present element polls until it times out. Asserting on the
*text* of a generated script cannot catch this. `HandleLocatorTest` now asserts instead that no
operator binds loosely at the top level of any locator expression, which can.

## Sessions

MCP is stateless by design — a server may not infer anything from an earlier message on the same
connection — and a WebView is nothing but state. `WebViewSessions` is the join:

```kotlin
object AppMcp {
    val sessions = WebViewSessions()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val server by lazy { McpServer(sessions, scope) }
}

// wherever the WebView is mounted
DisposableEffect(controller) {
    controller?.let { AppMcp.sessions.register("gallery", it, "the main WebView") }
    onDispose { AppMcp.sessions.unregister("gallery") }
}
```

The server never creates a WebView and cannot reach one that was not registered, which is what makes
"the app decides what an agent may touch" true rather than aspirational.

`session` may be omitted when exactly one is registered. That is not a "current session" by another
name — with two registered, a call without `session` fails and names them both, because a default
that *picks* among several would drive the wrong WebView and never say so.

## Leases

Ordering — which `vitre-core` has always had — makes each operation indivisible. It does not
make a *sequence* indivisible, and every interesting piece of automation is a sequence:

```
agent A: wait_for(".price")          agent B: click("#next-page")
agent A: extract(".price")     ← reads the price on B's page
```

All four operations were correctly serialised and the answer is still wrong. `acquire_lease` holds
the WebView across calls; `release_lease` gives it back. A call quoting no lease is not locked out,
it queues — the holder is uninterrupted, not privileged.

Mechanically, core gained `WebViewController.exclusively`, which holds the ordering lock for a block
and puts proof of that in the coroutine context so calls inside it do not deadlock against
themselves. The MCP module parks a coroutine inside one and hands the claim to each arriving tool
call. **The expiry lives here rather than in core**: an MCP client can crash between acquire and
release, and a WebView held forever by a client that no longer exists is worse than any interleaving
the lease was preventing. Core has no notion of a client that can go away, so it imposes no deadline.

## The tools

Thirteen, all mapping onto `WorkflowStep`s rather than generating their own JavaScript. That is not
tidiness: this library already had two implementations of "talk to a WebView", the Android and iOS
actuals, and they drifted until a boolean meant `true` on one and `"1"` on the other. A second
implementation of the step vocabulary reachable only through an agent would drift the same way and
be harder to notice.

| Tool | Notes |
|---|---|
| `list_sessions` | Which WebViews exist, and whether `session` can be omitted. |
| `snapshot` | The one to start with. Returns an indented outline, not HTML. |
| `navigate` | Loads a URL; invalidates every handle. |
| `click` / `type` | Wait for the element first, so a miss fails instead of no-oping. |
| `wait_for` | For content that arrives asynchronously. |
| `extract` | One value: text, an attribute, or a live DOM property. |
| `extract_rows` | A list or table, one record per row, columns resolved within the row. |
| `evaluate` | Escape hatch. An expression, not statements. |
| `send_message` / `await_message` | The `postMessage` bridge, for pages the host owns. |
| `acquire_lease` / `release_lease` | See above. |

Two things shape the descriptions attached to them. They are the prompt, not documentation — the only
thing a model reads before choosing how to address an element — so each says what the tool does to
the page and names the tool to reach for instead when it is the wrong one. And a failed call comes
back as a result with `isError: true`, never a JSON-RPC error, because protocol errors are shown to
the model inconsistently and are not framed as something to correct.

## Protocol: why the server speaks two eras

The `2026-07-28` revision removed the `initialize` handshake. Version, identity and capabilities now
ride in each request's `_meta`, results carry a `resultType`, and `server/discover` replaces the
handshake for clients that want to look before they leap.

That splits every client in two. A server implementing only the new shape cannot talk to anything
built before it; one implementing only the old shape is a legacy server to everything built after.
The spec's own compatibility matrix names the dual-era server as the arrangement that works in all
combinations, and the cost is one branch:

- `initialize` → legacy. The reply names a version we speak even when the client asked for one we do
  not, because a legacy client has no fall-forward mechanism: told only "no", it drops the
  connection and the user sees nothing useful.
- `_meta` carrying `2026-07-28` → modern. Results get `resultType` and a `serverInfo`; an unsupported
  version is refused with `-32022` **and the list of versions we do speak**, which is the whole
  reason that code exists.
- Anything else → legacy, with no `resultType`, because the field did not exist in those revisions
  and sending it is a guess about the client's tolerance.

Supported: `2026-07-28`, `2025-11-25`, `2025-06-18`.

## Transport: what ships, and what deliberately does not

`docs/CONCURRENCY.md` asked for this to be decided before building rather than after. It is decided:
**the module ships an in-process transport and no network transport.**

The two candidates differ in security, not convenience:

- **In-process.** An agent inside the app calls the server. The tools reach exactly as far as the app
  already does, and no new way in exists.
- **A loopback socket**, driven over `adb forward` from a developer's machine. Convenient — and it
  turns page automation into something any process that can reach the port can use. On Android a
  loopback listener is reachable by every other app on the device, needs no permission, and shows
  nothing in the UI. The WebView it exposes is frequently signed into the user's accounts, so what
  leaks is not "automation", it is the session.

An app that wants one implements `McpTransport` itself, which makes it a visible, deliberate line in
that app's own code rather than a capability every consumer of this library acquires by depending on
it. Anything doing so owes its users, at minimum: off by default, an explicit loopback bind, a
per-connection secret, and a visible indicator while the port is open. Mobile has no stdio, so there
is no third option that is private by construction.

## What is still missing

- **No `resources` or `prompts`.** Tools were the whole requirement; the rest is surface area with no
  caller.
- **No notifications.** `WebViewBridge.messages` is already a non-consuming firehose and is the
  natural source for `notifications/*`, but nothing consumes them yet, and the subscription machinery
  in `2026-07-28` is more than an unused feature justifies.
- **No screenshots.** A snapshot is the accessibility tree, not pixels. Pages that only make sense
  visually — a canvas, a chart — are invisible to it, and both platforms can capture a bitmap.
- **Handle registry entries are strong references.** A detached element stays reachable until the
  document goes. Bounded by a document's lifetime, so not a leak that grows without limit, but not
  free either on a long-lived single-page app.
