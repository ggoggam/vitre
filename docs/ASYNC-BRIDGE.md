# Redesigning the async script plane

The question this document answers: `evaluateJs` learned to await promises three different ways —
`callAsyncJavaScript` on iOS, an `AsyncScript` wrapper over the shared bridge on Android, and an
opt-in flag deciding whether either happens at all. What should it look like instead, and what is
worth taking from [parkwoocheol/compose-webview](https://github.com/parkwoocheol/compose-webview),
which was reviewed as a reference for this design?

## The verdict on the reference

compose-webview is a **hosting** library: the app embeds its own first-party page and wants typed
RPC with it. Its bridge is page→native — `window.AppBridge.call(method, data)` returns a Promise,
native resolves it by `callbackId`, handlers register as `register<T, R>` suspend lambdas.

Vitre is an **automation** library. It drives third-party pages that will never call
`window.vitre`, and its primary direction is native→page. So the reference's public API — the
handler registry, `BridgeSerializer`, reified `register<T,R>` — solves a problem no Vitre
caller has, and none of it should be copied. Its `emit()` is actively wrong (it concatenates the
event name into JS unescaped — an injection our `jsString` already rules out), and its
`CoroutineScope(Dispatchers.Main)` lifecycle is looser than `WebViewSerializer`'s model.

Four transport-level ideas *are* worth taking, and they map exactly onto the defects below:

1. **A dedicated correlation table.** Request/response matching lives in a `callbackId → callback`
   map, not in a general message stream that observers also read.
2. **`BridgeInvocationContext`.** Every inbound message carries `sourceOrigin`, `isMainFrame`, and
   which transport delivered it — and handlers can gate on them. We receive the same facts from
   every platform today and discard them.
3. **`addDocumentStartJavaScript`** (Android) as the origin-scoped place to install a runtime once,
   rather than re-wrapping per call.
4. **`JavaScriptReplyProxy`** as a frame-bound reply channel — a reply reaches exactly the frame
   that called, without an `evaluateJavascript` round-trip. Not needed for this redesign, but it is
   the right primitive if page→native RPC is ever built (see the page→native section below).

## What is wrong today

### 1. Any frame can forge a script result

`AndroidWebViewController` registers the message listener with `setOf("*")` and discards
`sourceOrigin` and `isMainFrame`; iOS installs the bridge user script with
`forMainFrameOnly = false` and ignores `WKScriptMessage.frameInfo`. `evaluateAndSettle` then waits
on the shared inbox for any message whose payload says the right `cid` — and cids are a counter
starting at zero, trivially guessable. So a third-party iframe on a page a lane is driving can run:

```js
window.vitre.postMessage(JSON.stringify({
  id: "script:result#1", type: "script:result",
  payload: { cid: 1, ok: true, value: "\"attacker's answer\"" }
}))
```

and resolve a workflow's `Extract` with data of its choosing. For a library whose stated job is
driving third-party shops, that is the defect that matters most.

Honest limit: a script running **in the main frame** shares the JS context with our wrapper and can
shadow `window.vitre.postMessage` before the wrapper calls it, so nothing short of an isolated
content world makes main-frame results tamper-proof — a hostile main document can already lie in
its DOM, which is where the data comes from anyway. The boundary we can and should enforce is the
*frame* boundary: only the document we are driving may answer, and its subframes may not.

### 2. RPC and observation share one plane

Script results travel through `WebViewInbox` — the same buffer that holds page traffic for
`AwaitMessage` and feeds `bridge.messages`, the "non-consuming firehose" documented as being for
hosts' debug panes. Consequences: every settle does an O(n) predicate scan (with a JSON decode per
candidate) over a deque holding unrelated traffic; internal `script:result` plumbing leaks into the
observer stream; and a stale result that nobody awaits any more sits in `unread` until the next
navigation clears it.

### 3. The pending sentinel is in-band

`__wv_pending:<cid>` is an ordinary string. A script that legitimately evaluates to that string is
indistinguishable from a pending promise, and the caller then waits out the full script timeout for
a settle that will never come.

### 4. One contract, two implementations, and a default that preserves the bug

iOS awaits with `callAsyncJavaScript`; Android awaits with `AsyncScript` over the bridge; both gate
on an `awaitsPromises` constructor flag that defaults to `false` — i.e. the default is the
behaviour where a promise silently serialises as `{}`. The flag's own KDoc recounts the silent
cross-platform divergence this already caused once. Lanes remember to pass `true` on both
platforms; the Compose hosts (`VitreWebView.android.kt:34`, `VitreWebView.ios.kt:38`) do
not, so a hosted page and a lane disagree about what `evaluateJs` means today.

## The design

### `evaluateJs` always awaits — the flag is removed

**Decision (owner-confirmed): `awaitsPromises` is deleted, not defaulted to `true`.** The contract
becomes: *`evaluateJs` returns the script's settled value, JSON-encoded.* `await` on a plain value
is a no-op, so the synchronous case is unchanged in meaning; a promise stops being a special case
the caller has to have opted into.

Migration is small because the flag is young: the two pools drop the argument; the two Compose
hosts change behaviour — a hosted page's `evaluateJs` on a promise now returns the value instead of
`{}`. Nobody can plausibly depend on receiving `{}`; the KDoc on `WebViewController.evaluateJs`
gains a line saying promises are awaited and `ScriptTimeoutException` bounds the wait.

### A dedicated settle plane: `ScriptResults`

A new small object owned by each controller, in commonMain next to `AsyncScript`:

```kotlin
internal class ScriptResults {
    fun expect(cid: Long): CompletableDeferred<SettledResult>
    /** True if [raw] was a script result and was consumed. Never suspends. */
    fun deliver(raw: String): Boolean
    /** Fails every pending wait: the document that would have answered is gone. */
    fun clear()
    fun forget(cid: Long)
}
```

The platform message callback offers every inbound message to `ScriptResults.deliver` **first**;
only messages it does not claim go on to `WebViewInbox`. That fixes defect 2 outright: correlation
is a map lookup keyed by cid (the reference's `callbackId` table, kept native-side where automation
needs it), `bridge.messages` never sees internal plumbing, `AwaitMessage` predicates never scan
past stale results, and an unclaimed result is dropped by `forget`/`clear` instead of rotting in
`unread`.

`clear()` runs where `inbox.clear()` runs today — `onPageStarted` / `didStartProvisionalNavigation`
— and fails pending waits with `ScriptTimeoutException("the document navigated away mid-settle")`
immediately, instead of the caller waiting out the full script timeout as the current
`evaluateAndSettle` KDoc concedes it must.

### Frame- and origin-gated delivery, tagged at the platform edge — landed

The platform callbacks no longer discard what they are handed. Both now thread the frame flag and
the posting frame's origin the whole way through:

- **Android** (`addWebMessageListener` callback): the previously-ignored `sourceOrigin: Uri` and
  `isMainFrame` are bound and passed on to `inbox.deliver`. The listener keeps `setOf("*")` because
  lanes navigate anywhere; the *frame* check is the gate. The platform reports an opaque origin —
  a sandboxed frame, a `data:` document — as the literal string `"null"`, which is normalised to a
  Kotlin `null` so no host mistakes it for an origin named "null".
- **iOS** (`WKScriptMessageHandler`): `message.frameInfo.mainFrame` for the flag, and
  `frameInfo.securityOrigin` reassembled into `scheme://host[:port]` — WebKit has no whole-origin
  string, so it is built from the parts, with an empty host meaning "opaque" and port 0 meaning
  "the scheme's default" and therefore omitted.

The tag arrives as `InboundBridgeMessage(raw, fromMainFrame, sourceOrigin)`. `ScriptResults` had
already taken the frame flag for the settle plane; what landed here is the other half, for the
inbox:

**Awaits are main-frame-gated.** `WebViewInbox.deliver` queues only main-frame messages for
`awaitMatching`. Without that, an embedded ad on a third-party site a lane is driving could post
`{"type":"ready"}` and both *satisfy* and *consume* the `AwaitMessage` armed for the main document
— the forgery from defect 1, one plane over. `bridge.awaitMessage`'s contract now says so.

**Subframe traffic is firehose-only.** It still reaches `bridge.messages`, and reaches the new
`bridge.inbound` carrying its frame and origin. This is what the page→native section below asks
for: a subframe *error* is information, it just is not an *answer*.

**Subframe messages are not buffered, and that is deliberate.** The obvious alternative — queue
them tagged, and let `awaitMatching` skip them — buffers messages that nothing can ever consume,
since `unread` has exactly one reader and that reader is now main-frame-only. They would
accumulate until the next navigation's `clear()`, which is a leak with no upside. If a
subframe-aware await is ever wanted (a step that waits for *any* frame, or for a named origin),
that is the moment to start buffering tagged entries and to widen the predicate to take an
`InboundBridgeMessage` — not before.

`InboundBridgeMessage` also stands in for the `BridgeInvocationContext` this section used to
propose: same two facts, minus the transport enum until there is a second transport, and it is
public rather than internal because the decision above is exactly the one hosts need to see.

### An unguessable sentinel and cid

`AsyncScript` gains a per-controller random **nonce** (from `kotlin.random.Random`, generated once
per controller instance):

- the pending sentinel becomes `__wv_pending:<nonce>:<cid>` — no longer forgeable or accidentally
  collidable by page content (defect 3);
- the result envelope carries the nonce, and `ScriptResults.deliver` requires nonce **and** cid to
  match, so a guessed counter is no longer enough (defect 1, together with the main-frame gate).

The nonce lives only inside the wrapped IIFE's closure and the native table. A main-frame script
that shadows `postMessage` can capture it — see the honest limit above — but no subframe and no
counter-guesser can.

### One `evaluateAndSettle`, in commonMain

The settle logic — wrap, evaluate, compare against the sentinel, await the deferred, map
rejection to `ScriptFailedException` — is string-and-coroutine work with no platform types in it.
It moves to commonMain (an internal `AsyncEvaluation` helper taking the controller's raw
`evaluate: suspend (String) -> String` and its `ScriptResults`), and both controllers call it. The
platform actuals keep only what is genuinely platform: submitting a script and delivering
callbacks.

**iOS keeps `callAsyncJavaScript`?** No — it switches to the common path. What it loses: WebKit
compiling the function body outside the page's `script-src` (the wrapper is evaluated like any
other script), and native `await`. What it gains: one implementation, one timeout semantics, one
navigation-mid-settle story, one test surface, and the same forged-result gate as Android instead
of a parallel bespoke one. The CSP point is real but narrow — `evaluateJavaScript` on WKWebView is
not subject to the page's CSP either for the *evaluation itself*; the difference only bites pages
that break `eval`-adjacent paths, and no lane has hit it. If one ever does, reintroducing
`callAsyncJavaScript` behind the same common interface is a platform-actual detail, not a design
change. Until then, two code paths that must agree is exactly the state that produced defect 4.

`asJsonExpression` / `asAwaitedJsonExpression` normalisation folds into the common wrapper: the
wrapper already returns the value or the sentinel, so it is the natural single place where
`JSON.stringify((v) ?? null)` produces the cross-platform encoding the `evaluateJs` contract
promises.

### What is deliberately not taken from the reference

- `register<T,R>` handler registry, `BridgeSerializer`, `registerNullable`, `emit`/`on` events —
  page→native RPC surface with no Vitre consumer.
- Its `emit()` string interpolation (unescaped event name — injection).
- `rememberWebViewJsBridge` / `dispose()` lifecycle — `WebViewSerializer` already owns ours.
- `BridgeCapabilities` — one transport per platform; capability negotiation is complexity on spec.

### Page→native: what exists, what error handling needs, what stays parked

Page→native is not absent today — `window.vitre.postMessage` → `WebViewInbox` →
`AwaitMessage` / `bridge.messages` is exactly that, one-way. What the reference adds is the
*request/response* form, and the two deserve different treatment.

**Error reporting is a real page→native consumer, and it is one-way.** A driven third-party page
fails in ways the engine currently cannot see: an uncaught exception, an `unhandledrejection`, a
fetch the page swallowed. Today that surfaces as a locator timing out with no cause attached.
Nothing on the page waits for native's answer to "an error happened" — so this is telemetry on the
existing plane, not RPC. The missing piece is instrumentation: a document-start script (Android
`addDocumentStartJavaScript` — already proposed above; iOS a `WKUserScript`, where the bridge
install script already runs) that hooks `window.onerror` and `unhandledrejection` and posts a typed
one-way message:

```json
{ "id": "…", "type": "page:error",
  "payload": { "message": "…", "source": "…", "stack": "…" } }
```

Hosts observe it on `bridge.messages`; a workflow can `AwaitMessage` it; the engine can attach the
last one to a step failure as cause. Two design notes so it composes with the rest of this
document: subframe errors are *kept but tagged* via `InboundBridgeMessage`, which is what landed —
the main-frame gate stops a subframe from *answering* a wait, on either plane, but the observer
firehoses carry every frame's traffic, so a subframe error still reaches a host that is listening;
and `inbox.clear()` on navigation drops errors from the outgoing
document, which is correct — they could only mislead a step running against the new one.

### Native→page request/response — landed

The direction that *does* have a caller is the other one: native asks, the page answers. It needed
no new plane, only a convention and two extension functions in `TypedBridge.kt` over the primitives
that already exist.

**The reply names the request in `replyTo`.** A new optional field on the envelope carries the id of
the message being answered; `id` stays what it always was — this message's own identity. Keeping the
two apart is what lets the settle plane go on reading `script:result#<cid>` as an identity, and what
keeps a reply distinguishable from its request on the firehose instead of two messages sharing one
id. The field is default-null and default-omitted, so every message written before it existed is
still byte-identical on the wire. `bridge.request(type, payload)` posts and then awaits
`replyTo == id`; the page side is one line, echoing the incoming id back (see the sample fixture).

**Post-then-await is not a race.** A synchronous page handler replies before `request` has begun
waiting — the common case, not the unlucky one — and the reply still matches, because `WebViewInbox`
buffers unread messages and the await scans the buffer before it sleeps. That is the inbox's whole
reason to exist; `replyTo` is what stops the *wrong* buffered reply from being taken.

**Two id namespaces, one channel.** Generated ids are `msg#<random hex>`, deliberately disjoint from
the settle plane's `script:result#<cid>`, so a reader of `bridge.messages` can tell page traffic from
internal plumbing at a glance and neither side can be mistaken for the other. `post`/`request` refuse
the `script:result` *type* outright: `ScriptResults` claims those before the inbox sees them, so such
a message would be swallowed and the caller would learn about it as a bare timeout.

**Payloads are typed by serialization, not by a registry.** `post`, `request` and `awaitMessage`
each gained a `reified` overload that runs the payload through `kotlinx.serialization` on the way
out and the reply's payload on the way back — `request<Ack, Token>("issue-token", Ack(seen = true))`
is the whole round trip. These are conveniences over the functions below them and add nothing to
the protocol: same envelope, same id namespace, same reserved-type refusal, same `replyTo`
correlation, and a typed call is byte-identical on the wire to the hand-built one it replaces. The
typed `request` returns the reply's *payload* rather than its `ReceivedMessage`, because the
envelope's remaining fields are plumbing that call already resolved; when the reply's `type` is what
distinguishes an answer from a refusal, the untyped overload is still there. `ignoreUnknownKeys`
means a payload class is a *view* of what the page sent, so a page that adds a field does not break
a class that predates it — and anything dropped that way is still in `ReceivedMessage.raw`.

**Workflows get the input half only, and that asymmetry is structural.** `WorkflowScope.postMessage`
serializes a payload class into the envelope at build time, which removes the hand-typed JSON string
from the call site. There is deliberately no `(T) -> R` there: a workflow block is a builder that
runs once, up front, and the step it appends is dispatched later by the engine, so no reply can be
returned to the block that declared it. The reply lands in a variable, and the typing resumes at the
far end — `WorkflowEvent.Completed.decodePayload<R>(name)` for a message envelope,
`decode<R>(name)` for anything else a step stored. A caller who wants the round trip as one
expression wants the host API, which is what `request` is for. `id` stays a required argument on the
workflow side rather than being generated, because a workflow is a value and one that mints a fresh
id per build is not equal to itself.

**Still no handler registry, and no new `WorkflowStep`.** The verdict on the reference stands —
`register<T, R>` is page→native RPC surface with no consumer here, and this layer registers nothing
and holds no state; it is `awaitMessage` and `postToWebView` with the JSON decode moved inside, which
is where `WorkflowEngine`'s `AwaitMessage` branch was doing it by hand. Workflows already compose the
request shape out of `PostMessage` + `AwaitMessage`; what they cannot do is correlate by id, and a
declarative script that generates ids is not a thing a workflow author wants — id correlation is a
programmatic-host concern, so it lives in the host API only. Requests and replies are page traffic
like any other and stay visible on `messages` and `inbound`; nothing tries to hide them.

**Page→native request/response stays parked.** If injected instrumentation ever needs to *ask native*
something (`window.vitre.call(...)` returning a Promise — a config lookup, an ack that gates
page behaviour), the reference's shape — callback table in JS, `JavaScriptReplyProxy` replies,
document-start bootstrap — is the right one, and `ScriptResults` + `BridgeInvocationContext` are
the pieces it would sit on. Not built now: nothing on the page needs an answer yet, and it is new
surface on a security-sensitive boundary.

### The bridge-ready contract — landed

Both directions above assume the page can tell when `window.vitre` is there. Until now it had
no documented way to find out and had to poll.

**The guarantee: existence is the flag.** On Android and iOS the bridge is installed before any
page script runs, for the same reason on each — iOS injects a `WKUserScript` at
`WKUserScriptInjectionTimeAtDocumentStart`, Android's `addWebMessageListener` puts its object in
place before the document's own scripts execute. So by the time a line of page JavaScript can ask
the question, the answer is already final, and `if (window.vitre)` is authoritative and
synchronous. There is no `.ready` boolean, deliberately: a second flag is only a second thing that
can disagree with the first, and the first cannot be wrong.

**The desktop is weaker here, and a page author needs to know it.** CEF exposes no document-start
hook to an application — `CefRenderProcessHandler::OnContextCreated` lives in the render process and
is not reachable from Java — so `CefWebViewController` injects `CefBridgeChannel.installScript()`
from `onLoadStart`, which genuinely races the document's first inline script. Measured, it lands on
both sides of that script across runs of the same page. The check-then-listen pattern below handles
either order and is therefore not optional on this platform, where on the other two it is
belt-and-braces. Nothing the library itself does is affected: a settled-promise report is posted by
a script *we* evaluate, long after injection, and inbound page traffic is buffered by `WebViewInbox`
rather than dropped.

**The `vitre:ready` event — belt-and-braces on Android and iOS, load-bearing on the desktop.**
Dispatched on `window` at install, for code that would rather be told than ask. The name is not
`vitre` — that is the `MessageEvent` used for native→page delivery, and a listener for one must
never be woken by the other. `BridgeReady` in commonMain holds the name and the announcement
script, so the constants are one thing rather than a literal per platform.

**The pattern is check-then-listen, and on Android and iOS the check is the branch that fires:**

```js
function whenBridgeReady(fn) {
  if (window.vitre) { fn(); }
  else { window.addEventListener('vitre:ready', fn, { once: true }); }
}
```

`fn` therefore runs synchronously on Android and iOS, and on the desktop only when the check wins
the race. Whatever depends on it belongs *inside* `fn`, not on the line after the call — that is the
same intermittent-on-desktop-only bug in a second disguise.

**Why the event alone is insufficient.** A page script that registers a bare listener and nothing
else hangs *by construction* on Android and iOS: the announcement precedes every page script there,
so such a listener is always registered after the event it is waiting for has already fired. On
those two the event only ever reaches something that ran earlier still, such as another
document-start script — and on an Android WebView too old for the announcement to be installed at
all, there is no event for anyone to catch. The desktop fails less predictably, and worse: the race
above means a bare listener catches the event on some runs and misses it on others, so the bug ships
instead of showing up the first time anyone tries it. Handling the already-ready case first is what
makes a late listener safe on all three, which is why the sample fixture adopts the helper above
rather than a bare `addEventListener`.

**Where each platform dispatches.** iOS does it inline in the install script, after the assignment
and *behind* the existing `if (window.vitre) return;` idempotence guard — a re-injection into a
context that already has the bridge must not re-fire ready. Android has no such script, so it uses
`WebViewCompat.addDocumentStartJavaScript` (idea 3 from the reference, put to its first use), guarded
by `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` and **silently skipped**
where the platform WebView is too old. Nothing a page can depend on is lost by that fallback: the
synchronous existence check holds on every WebView, and on Android the event was never the
load-bearing half. The returned `ScriptHandler` is retained so a `close()` can `remove()` it. The
desktop dispatches inline the way iOS does — the event is the last statement in
`CefBridgeChannel.installScript`, behind the same idempotence guard — so a re-injection into a
context that already has the bridge does not re-fire ready there either.

## Migration and test plan

Order of work, each landable alone:

1. **`ScriptResults` + delivery gating** (fixes forgery and the shared plane; no API change).
   Tests: forged result from a subframe is ignored; nonce mismatch is ignored; stale result after
   `clear()` fails the waiter promptly; `bridge.messages` never emits `script:result`;
   `AwaitMessage` cannot consume one.
2. **Common `evaluateAndSettle`, iOS onto it** (deletes the iOS-only path). Tests: existing
   `AsyncScriptTest` plus sentinel-collision (`"__wv_pending:…"` as a legitimate value) and
   navigation-mid-settle; run the `bridge-round-trip` and live-probe sample workflows on both
   platforms.
3. **Delete `awaitsPromises`** (pools drop the argument; hosts change behaviour). The
   `WebViewController.evaluateJs` KDoc and this document are the record of the new contract.

`FakeWebViewController` needs no change — it never had the flag. `WebViewInboxTest` gains the
"results never reach the inbox" cases; `AsyncScriptTest` largely survives with the nonce threaded
through.
