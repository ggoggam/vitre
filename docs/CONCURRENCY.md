# Threading, and how an agent gets to drive the WebView

The question this document answers: Vitre has business logic, UI, a WebView, and — since
`vitre-mcp` — agents calling in over MCP. How do those stay out of each other's way?

The short answer is that we do not synchronise threads, because you cannot. **The WebView owns a
thread and everybody else visits it, one caller at a time.**

## Why there is nothing to negotiate

Both platforms have already decided:

- `WKWebView` is UIKit. Every member is main-thread-only, and calling one from anywhere else is
  undefined behaviour — not an exception. It usually presents as a hang or a wrong answer, which is
  why it survives testing.
- `android.webkit.WebView` must be used on the thread that constructed it. For a hosted view that
  is the UI thread.

So the WebView thread *is* the platform main thread, and no amount of design changes that. What is
left to decide is who may touch it, and in what order.

## The model

Three roles, three places, one crossing point.

```
   UI (Compose, main thread)                 Agent / MCP / host code (any dispatcher)
            │                                                │
            │ observes StateFlow                             │ suspending calls
            ▼                                                ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │                      WorkflowEngine                             │
   │              Dispatchers.Default — selectors, JSON,             │
   │              variables. Never sees the WebView thread.          │
   └────────────────────────────────┬────────────────────────────────┘
                                    │  one operation at a time
                       ┌────────────▼─────────────┐
                       │    WebViewSerializer     │  ← the entire concurrency policy
                       │  • confines to main      │
                       │  • totally orders ops    │  (WebViewOrdering)
                       │  • leases, for sequences │
                       └────────────┬─────────────┘
                                    ▼
                        WKWebView / android.webkit.WebView
```

`WebViewSerializer` is the whole of it, and it enforces exactly two rules.

**1. Confinement.** Every platform call goes through `withContext(WebViewDispatcher)`. Callers run
wherever they like and cross over for the duration of one operation. `WebViewDispatcher` is
`Dispatchers.Main.immediate` on Android and iOS and `Dispatchers.Swing.immediate` on the desktop, so
a caller already on the UI thread — the Compose host, most of the time — pays nothing.

**2. Total ordering.** `navigate` and `evaluate` share one lock, held by `WebViewOrdering` — public
so that a `WebViewController` written outside this module can delegate to it rather than inventing a
second policy. Operations against a WebView are
serialised, because interleaving them is not merely racy, it is meaningless: a script evaluated
halfway through somebody else's navigation runs against whichever document happened to be
committed, which is not a result any caller asked for.

Waiting for an inbound bridge message deliberately does **not** take the lock. It is a wait, not an
operation, and holding the WebView while waiting for the page to speak is a deadlock — the page
usually needs a script to run first.

Everything else follows from those two. The engine runs on `Dispatchers.Default` because parsing
selectors and decoding JSON is business logic and has no business on the UI thread. The UI observes
events and never holds a controller reference it drives directly.

## What this bought

These were live bugs, not hypotheticals:

| Symptom | Cause | Fix |
|---|---|---|
| iOS drove `WKWebView` from whatever dispatcher the collector happened to be on | Android hand-rolled `webView.post{}`; iOS hand-rolled nothing | Confinement moved into the shared serializer, so neither actual can forget |
| A script whose page navigated away hung the workflow forever | Both platforms drop a pending script callback when the document goes, without invoking it | `evaluate` is bounded and raises `ScriptTimeoutException` |
| …and a step after a click that navigated then failed a workflow that was fine | Same cause, but the page started the navigation, so ordering against `navigate` could not rule it out — and waiting out the timeout reported a healthy page as a slow one | `evaluate` watches for the document being replaced and resubmits **once**, against the page that settles in its place |
| `AwaitMessage` hung forever when it lost a race | The page posted before the step subscribed; a no-replay `SharedFlow` drops that silently | `WebViewInbox` buffers unread messages and consumes each exactly once |
| Cancelling a run reported it as a failure | The engine caught `Throwable`, including `CancellationException` | Every self-imposed timeout becomes a domain exception at the point it expires, so a cancellation reaching the top can only be the collector's |
| `WaitFor(timeoutMs = 10_000)` could run for a minute | Elapsed time counted poll intervals, not the round trips between them | Bounded on wall clock |
| iOS could never satisfy a `WaitFor` | WebKit returns Foundation objects; `description` prints JS `true` as `"1"`, where Android returns JSON `"true"` | iOS wraps scripts in `JSON.stringify`, and desktop does the same on its way back over CEF's message router, so every platform returns JSON |

## Why agents make this the load-bearing part

A workflow is one caller running a script it wrote in advance. An agent is a *second* caller,
arriving at times nobody planned, interleaved with the first. That is the entire difficulty, and it
is why the ordering lock matters more than it looks:

- An agent's `click` must not land between a workflow's `WaitFor` poll and the `Extract` that
  depends on it.
- Two agent tool calls issued concurrently must not both evaluate against a document that one of
  them is in the middle of replacing.
- A UI "reload" button is just a third caller.

With a single serialisation point, none of these are special cases — they queue. Without one, every
combination is its own bug, and they only reproduce under load.

The bridge inbox matters for the same reason. An agent that sends a request to the page and waits
for a reply is in exactly the position `AwaitMessage` was: the reply can arrive before the wait
begins. Buffer-and-consume-once is what makes request/response over the bridge possible at all.

## What was missing for agents, and what closed it

Ordering is necessary and not sufficient. Three gaps were named here; two are closed and one turned
out to dissolve.

**1. There was no way for an agent to see the page.** ✅ `WorkflowStep.Snapshot` walks the document
and returns the interactive and text-bearing elements with stable handles, as an indented outline
rather than HTML. `Locator.Handle` makes every selector-addressed step handle-addressed for free,
since they all already took a `Locator`. This was the single largest gap and it did come before MCP.

**2. Variables do not flow between steps.** Still true for workflows, and it stopped mattering for
agents: a tool call's result goes back to the model, which composes the next call itself. The model
*is* the variable store. Left alone rather than built for a caller that no longer needs it.

**3. Sessions were implicit.** ✅ `WebViewSessions` in `vitre-mcp` maps a name to a controller,
and the host registers its own. It stayed out of core, which is right: an app with one WebView never
needs to name it.

## Where MCP fits

Built — see [MCP.md](MCP.md). This section is what it was designed against, and it held up.

MCP is a transport and a schema. It is *not* a concurrency model, and it must not become one — if
the ordering guarantees live in the MCP layer, then the UI and the workflow engine are outside them.
So the shape is:

```
vitre-mcp      ← JSON-RPC, tool schemas, session registry, lease expiry.
      │
vitre-core     ← WebViewSerializer already ordered everything. It gained
                      Snapshot, Locator.Handle and `exclusively`; the ordering
                      policy itself did not move.
```

Concretely:

- **Tools map onto the step vocabulary** — `navigate`, `click`, `type`, `extract`, `snapshot`,
  `evaluate`, `send_message`, `await_message` — plus session lifecycle. They should map onto steps
  rather than being a parallel implementation, or the two drift the way the platform actuals did.
- **Every tool call names a session.** MCP calls carry no state between them; the WebView is nothing
  but state. A registry of `sessionId -> WebViewController` is the bridge, and it belongs in the MCP
  module, not in core.
- **Concurrent calls are already handled.** An MCP server will have several tool calls in flight.
  They queue on the existing lock. Nothing new is needed — which is the point of putting the policy
  where it is.
- **Add a lease for multi-step plans.** Ordering stops two callers corrupting each other's *single*
  operations. It does not stop an agent's `type` landing between another client's `click` and
  `extract`. When that matters, a caller needs to hold the session across several operations. The
  lock already exists; what is missing is a public way to hold it, with a timeout so a crashed
  client cannot wedge the WebView. — Now `WebViewController.exclusively`, with the claim carried in
  the coroutine context so calls inside it do not deadlock against themselves. The timeout lives in
  the MCP module rather than core, because "the client went away" is a thing only that layer knows
  about.
- **`WebViewBridge.messages` is the notification channel.** It is already a non-consuming firehose
  precisely so an observer cannot starve the workflow driving the page. MCP notifications and a
  debug pane are both just subscribers.
- **Transport was the open question.** Decided before building: the module ships the in-process
  transport and no network one. A loopback listener on Android is reachable by every other app on
  the device, needs no permission, and shows nothing in the UI, and the WebView it would expose is
  usually signed into the user's accounts — so what leaks is the session, not merely automation. An
  app that wants one implements `McpTransport` itself, which makes it a visible line in that app's
  code rather than a capability acquired by taking the dependency.

Two things to keep out of core: JSON-RPC (core has no transport and should not grow one), and any
notion of "the current session" (a global there is a bug the moment there are two WebViews).
