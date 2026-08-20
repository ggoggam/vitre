# Parallel lanes: driving four sites at once

The question this document answers: a workflow drives one page at a time, and most useful work —
comparing a price across four shops, checking one part number against four distributors — is the
same workflow run four times against four different sites. Doing that sequentially takes four times
as long for no reason: three of the four are always sitting idle waiting on somebody's network.

The answer is four **lanes**, each pointed at a different site, with four workflow engines running
against them concurrently. A lane is **one WebView with one site loaded as a top-level document**,
on all three platforms. `pool.lane(id)` is a `WebViewController` like any other, and the workflow engine
has no lane-aware code anywhere in it.

There used to be a second arrangement on Android — four `<iframe>`s inside one WebView, with the
network interceptor stripping `X-Frame-Options` so that sites which refuse to be framed would render
anyway. It worked against the live web, and it was deleted anyway. The last section says why,
because the measurement that settled it is worth keeping even though the code is gone.

## The shape

```
 native (Kotlin)
     │
     │  evaluateJavascript() / evaluateJavaScript()    ← straight at the document
     ├──────────┬──────────┬──────────┐
     ▼          ▼          ▼          ▼
 ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
 │ lane a │ │ lane b │ │ lane c │ │ lane d │
 │ shop-1 │ │ shop-2 │ │ shop-3 │ │ shop-4 │
 └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘
     └──────────┴────┬─────┴──────────┘
                     │  window.vitre.postMessage
                     ▼
        WebMessageListener / WKScriptMessageHandler / CefMessageRouter → WebViewInbox
```

Because a lane is a main frame, there is nothing between native and the document: no host page, no
command routing, no handshake to assign a lane its identity, and no navigation token to make that
handshake race-free. Each lane has its own `WebViewSerializer`, so operations are ordered against
that lane and against nothing else — which is what keeps four lanes from taking turns.

### Why four lanes are actually concurrent

`WebViewSerializer` totally orders operations against one WebView, and the temptation is to read
that as "so the lanes take turns". They do not: the ordering is per lane, and a lane's serializer
knows nothing about its neighbours.

What the lanes *do* share differs by platform, and this is the one place the two are not alike:

| | processes | shared main thread |
|---|---|---|
| iOS, four `WKWebView`s | four content processes | no — four main threads |
| Desktop, four CEF browsers | four renderer processes | no — four main threads |
| Android, four `WebView`s | **one** renderer process | yes — one JS main thread |

Android WebView shares a single renderer across every `WebView` in the app, unlike Chrome. So on
Android the lanes overlap on the *waiting* — four network round trips in flight at once, which is
where the seconds are — and contend for one thread when they parse and run script. On iOS and the
desktop they overlap on both, because Chromium and WebKit both give a browser its own renderer.
Neither arrangement serialises the part that takes seconds, which is the point.

That is a claim about the *renderers*. Anything a pool shares in front of them can serialise the
lanes right back, and on the desktop one thing did — see
[the interceptor's threading rule](#desktop-the-interceptor-must-not-fetch-on-the-thread-cef-calls-it-on).

The practical consequence is Android's alone: four heavy third-party sites parsing on one thread
make each lane slower than it would be alone, so a pool wants a navigation timeout well above the
single-page default. A lane that renders visibly and then reports a timeout is usually this.

## Sessions, and what a lane costs

A lane is first-party on its own site, so cookies and storage behave the way the site expects — a
lane that logs in and a lane that then reads the account can be the same workflow twice. Both
platforms share one cookie jar across lanes deliberately: `WKWebsiteDataStore.defaultDataStore()` on
iOS, the process-wide `CookieManager` on Android.

`forDevice` decides how many lanes a device can carry, and the platforms answer differently
because the cost is different. On iOS a lane is a content process, which is exactly what makes it
parallel and what gets an app jetsammed on a 3GB phone, so the count is scaled down by total RAM. On
Android the memory is in the shared renderer — four lanes measured within noise of one — so only
genuinely small devices are trimmed. On the desktop a lane is a renderer process as on iOS, but
there is no jetsam and usually an order of magnitude more RAM, so only a machine small enough to
swap gets trimmed. Either way, fewer lanes costs wall-clock and nothing else,
because `FramePool.run` queues: six workflows in a pool of two run three deep rather than losing
four of them.

## Network interception (Android and desktop)

Two of the three platforms let an application answer a request outright, and they get the same
arrangement from two different hooks:

| platform | hook | class |
|---|---|---|
| Android | `WebViewClient.shouldInterceptRequest` | `AndroidNetworkInterceptor` |
| Desktop | `CefResourceRequestHandler.getResourceHandler` | `CefNetworkInterceptor` |
| iOS | — none — | see [iOS: fixtures without an interceptor](#ios-fixtures-without-an-interceptor) |

### How a desktop lane reaches the screen

The other two platforms hand you a platform view and you put it in the hierarchy. CEF offers that
too — windowed mode gives you an AWT component — and inside Compose it is the wrong trade, for two
reasons that arrive together:

- **A heavyweight AWT component always paints above Compose.** Anything the host draws over the
  page is behind it and invisible. The sample's single-page runner reports a run in a bottom sheet
  over the page, so "the page wins" is not a compromise, it is the run detail disappearing.
- **It has to be in a window before it paints at all.** A CEF browser realises its native window
  when its component joins a hierarchy, so one created earlier occupies its space while drawing
  nothing — a blank rectangle that reads as a broken lane.

So a desktop lane renders **offscreen**: CEF paints into a buffer, `CefSurface` carries it, and
`CefSurfaceView` draws it as an ordinary Skia image with input forwarded back. Z-order, clipping,
scrolling and animation then work on a lane the way they work on an `Image`. The cost is a copy per
painted frame instead of Chromium compositing straight to the screen — near-nothing for a page being
automated, since CEF only paints when something changes, and real for a lane playing video.

The decision logic is deliberately identical, and the fetching underneath it is literally shared —
`HttpResourceFetcher` and `ExchangeRecorder` live in a `jvmCommonMain` source set both targets
depend on, because two copies of "follow the redirect the JDK's own follower refuses to follow"
is exactly the sort of thing that gets fixed on one platform only.

The interceptor does four jobs:

1. **Un-CORS.** Reflect the request's `Origin` into `Access-Control-Allow-Origin` with
   `Allow-Credentials: true`, answer `OPTIONS` preflights permissively, and widen the page's CSP
   `connect-src`. The last part is not an extra: CORS is the *server's* opinion about who may read
   a response, `connect-src` is the *page's* opinion about where it may ask at all, and relaxing
   only the first leaves the fetch blocked by an error that names neither.
2. **Tap.** Publish every exchange — method, URL, status, headers, timing, and the response body
   when it is textual and under a cap — as a `NetworkExchange` on a `SharedFlow`.
3. **Route.** Let the host answer a request itself. Fixtures, mocks and blocking are all one
   `RequestHandler` interface.
4. **Nothing about framing.** `X-Frame-Options` and CSP `frame-ancestors` are left exactly as the
   server sent them. They answer "who may frame this page", a lane is a top-level document, and
   nobody is asking. Removing them was the iframe arrangement's requirement, and it re-enabled
   clickjacking against the site to get it.

The tap is not a debugging afterthought, it is often the *better* extraction path. A shop that
renders its results from `GET /api/search?q=…` hands you clean typed JSON — price as a number,
currency as a code, stock as a boolean — where the DOM has `$1,299.00` inside three nested spans.

### Desktop: the interceptor must not fetch on the thread CEF calls it on

Worth its own heading because getting it wrong turned four lanes back into one, and nothing about
the symptom pointed here.

`getResourceHandler` is called on CEF's IO thread, and there is exactly **one** of those per browser
process — not one per browser. Android is the opposite: each `WebView` gets its own background
thread for `shouldInterceptRequest`, so four lanes intercept four requests at once. Every lane in a
desktop pool is a browser inside one CEF process, so every lane's requests arrive on the same
thread, single file.

Fetching inline there — which is what the code did at first, because it is what Android's hook
invites — holds that thread for a whole HTTP round trip, and no other lane can *start* a request
until it lets go. Measured, four lanes against sites answering in 1500ms each:

| | total | interceptions started |
|---|---|---|
| fetch on the IO thread | 6270ms | 1500ms apart |
| interception off entirely | 1611ms | together |
| fetch on a worker (current) | 1743ms | together |

Four times one, exactly. The renderers were parallel the whole time — the network in front of them
was a queue of one, and since `interceptMainFrame` defaults on, every lane's *document* went through
it.

So the decision to intercept is made on the IO thread, because it is pure predicate work, and the
fetch is handed to a worker. `CefResourceHandler.processRequest` returning true *without* calling
`Continue()` is CEF's contract for "this request is mine, the answer is coming later", and it is
what makes that possible.

The one thing this costs is the fallback. CEF decides between "the application answers this" and
"the network stack does" the moment `getResourceHandler` returns, and there is no way back
afterwards — so a fetch that fails can no longer be handed to Chromium, and is served as a 502
naming the cause instead. Practical consequence: an unreachable origin fails a workflow at its first
`WaitFor` rather than at its `Navigate`.

The same rule applies to a `RequestHandler`. Those still run on the IO thread, because whether one
answers is part of the decision, so a handler that blocks blocks every lane. Fixtures served from
memory are what they are for.

### `interceptMainFrame` is a real decision now

Under iframes the main frame was the library's own host document and intercepting it bought nothing,
so it defaulted off. A lane *is* the main frame, so it defaults **on**: it is the one request that
carries the site, and with it off a `RequestHandler` never answers a document and the tap never sees
one.

The cost is worth stating plainly, because it applies to every real site a lane loads. Interception
refetches through `HttpURLConnection`, which is not the browser's network stack: HTTP/1.1, no shared
cache, redirects followed by hand (so the document's `location` is the URL that was *requested*
rather than the one that answered), the cookie jar bridged across by hand, and a `POST` navigation
passed through untouched because the platform hook exposes no request body. A pool driving real
sites that needs nothing rewritten should turn it off; a pool serving fixtures cannot.

### Why desktop refetches too

CEF has hooks that *see* a response — `onResourceResponse` gets handed the `CefResponse` — and none
that let an application change its headers on the way past. So adding the
`Access-Control-Allow-Origin` a server declined to send means being the one who sends the whole
response, which is the same conclusion Android reached from the other direction. Both therefore pay
the same price, spelled out below: an intercepted document is one this library refetched through
`HttpURLConnection`, not one the browser's own network stack fetched.

One thing genuinely differs. Android's hook exposes no request body, so a `POST` *cannot* be
replayed; CEF's `CefRequest.getPostData()` does expose one, so it could. It is not, on purpose —
a workflow that works on one platform and silently double-posts on another is a worse outcome than
a `POST` left alone on both.

The other desktop-only seam is cookies. Android reads the WebView's own jar synchronously;
CEF's `CefCookieManager` answers through a visitor callback, and blocking a resource load on it
risks waiting on the thread that would deliver the answer. So intercepted requests on desktop carry
cookies from a `java.net.CookieManager` the interceptor owns. In practice a lane's document is
intercepted by default, which keeps a site's own navigation on one side of that seam.

### What interception cannot do

- **Request bodies are not visible.** `WebResourceRequest` exposes the method and headers but not
  the body, so a `POST` cannot be replayed faithfully. Non-`GET`/`HEAD` requests are passed through
  to the platform untouched: no rewriting, no tap entry.
- **It is synchronous and it blocks that resource load.** Fine for documents and XHR, which is what
  a scraper cares about; a policy that intercepts every image will make pages feel slow.
- **iOS has no equivalent**, and cannot have one: `WKURLSchemeHandler` refuses to register for
  `http` or `https`, precisely so that an application cannot answer for a real origin.

## iOS: fixtures without an interceptor

`RequestHandler` is the one piece of interception iOS *can* keep, because a fixture's origin is
invented in the first place. `FixtureScheme` registers `vitre-fixture://` — a scheme WebKit is
willing to hand over — and `IosWebViewPool` moves a navigation onto it when, and only when, a
handler claims the URL. Handlers still see the `https` URL the workflow asked for, so the same
`Navigate("https://shop.test/search?q=…")` step runs unchanged everywhere — Android and desktop
answer it from their interceptors, iOS from the scheme handler.

Host and path are preserved through the mapping (`https://shop.test/a` ↔
`vitre-fixture://shop.test/a`) for two reasons: relative URLs inside a served document resolve
back into the scheme on their own, and each fixture host keeps a **distinct origin**, so the
sample's four shops are as genuinely cross-origin on iOS as they are on Android. The one thing that
needs help is a fixture's *absolute* `https://` reference to another fixture — Nordic Parts fetching
its own API — which the injected tap script retargets, and only ever inside a document that is
itself served from the scheme.

It is not a proxy. Nothing in it touches the network; a URL no handler claims is a 404, not a fetch.

Two things are therefore worse on iOS than on Android, and neither is recoverable without building
the proxy this design exists to avoid:

- **Nothing can rewrite a response.** A cross-origin `fetch` from inside a lane succeeds only if the
  server already allows it. The live probe scenario reports this honestly rather than hiding it.
- **The network tap sees only what the page's own script asked for.** With nothing below the page to
  watch, `ScriptedTap` patches `fetch` and `XMLHttpRequest` at document start and has the page
  report on itself. Document loads, images and stylesheets are invisible. What survives is the part
  that mattered most for extraction.

`InterceptionPolicy`'s `permissiveCors` is inert on iOS for the same reason; `handlers` is honoured.

## Traps, all of which look like "the lane didn't load"

Every failure mode here presents identically — a lane that renders nothing, or renders perfectly and
reports a timeout. They are worth naming because none of them is guessable from the symptom.

**1. Intercepting everything starves the page.** `shouldInterceptRequest` is synchronous and blocks
the resource it handles, and a real site is mostly images, fonts, CSS and script — none of which
need anything done to them, all of which compete with the document and the API calls that do.
`InterceptionPolicy.intercept` defaults to documents and data, which took `developer.mozilla.org`
in a lane from 200 intercepted requests to 22.

**2. `disconnect()` costs a TLS handshake per resource.** `HttpURLConnection.disconnect()` in a
`finally` looks like hygiene and is the opposite: it removes the socket from the keep-alive pool.
Consuming the response body is what returns it. On a page of eighty subresources the difference is
a lane that loads and a lane that does not.

**3. A page-initiated navigation swallows the script in flight.** Clicking a submit button navigates
the page, and every platform drops a pending script callback when the document it was submitted
against goes away — without ever invoking it. The reply is not late, it is never coming, and the
step would otherwise wait out its whole script timeout and report a page that is visibly fine as a
slow one. `WebViewSerializer.evaluate` therefore resubmits **once**, and only when it sees a new
document commit rather than a result arrive. Once, because a second loss is a genuine fault and
should look like one.

**4. `evaluateJavascript` never waits for a promise.** It hands back whatever the expression
evaluated to, and a `Promise` serialises as `{}` — so an asynchronous step returns an empty object
rather than its result, with no error anywhere to say the value was simply never waited for. That is
not a corner case: anything built on `fetch` is asynchronous, and a shop's own JSON API is frequently
the better extraction path. It was also invisible until recently, because the deleted iframe runtime
happened to await a thenable before replying, and iOS lanes used `callAsyncJavaScript`. Today
`evaluateJs` awaits unconditionally on every platform — the script is wrapped so a promise reports
itself back over the bridge the controller already owns, gated by a per-controller nonce and a
main-frame check, and the caller waits for that report instead of for the evaluate. A synchronous
script keeps the platform's own path and encoding untouched. See `docs/ASYNC-BRIDGE.md` for the
whole design, including why the old opt-in flag is gone.

Two smaller ones, for completeness: `Content-Encoding: gzip` left on a body the interceptor has
already decompressed makes the renderer try to gunzip plain text, which fails as an empty document;
and `Access-Control-Allow-Origin: *` is rejected outright alongside `Allow-Credentials: true`, so
the request's own `Origin` has to be reflected back rather than answered with a wildcard.

## The sample: Price scout

Four lanes, one query, four shops, one merged table sorted by price. It is the workflow this whole
mechanism exists for, and it ships twice:

- **Price scout (fixtures)** points the lanes at four synthetic shops served entirely by
  `RequestHandler`s at four *distinct* origins. Distinct is the point: they are genuinely
  cross-origin from each other, so the demo proves the mechanism rather than assuming it, and it
  does so offline and deterministically — which makes it the smoke test. The four shops deliberately
  disagree about markup: a `<ul>` of `data-sku` rows, a `<table>` with the price in a `data-cents`
  attribute, cards with the price split across two spans, and one that ships an empty shell and
  fills it from a **cross-origin** `GET /items` — the one that needs CORS relaxed, and the one where
  the tap beats the DOM. One of them, Keyclack, refuses a query in the URL at all: it has to be
  typed into the shop's own box and submitted, which is what automating someone else's site usually
  means, and which is why `Input` and `Click` are demonstrated rather than only `Extract`.
- **Live pages probe** points the lanes at four real sites — `github.com`, `developer.android.com`,
  `developer.mozilla.org`, `en.wikipedia.org` — and reports, from inside each one, the document it
  landed on, its title, how many nodes it can see, and what a cross-origin `fetch` returned. That
  last line is the one still worth watching: it comes back with a status on Android, where the
  interceptor can supply an `Access-Control-Allow-Origin` the server never sent, and blocked on iOS,
  where nothing can rewrite a response header.

  It is a diagnostic rather than a scraper, and it is the part that rots: sites change, and a bot
  challenge renders in a lane as "Webpage not available", which looks exactly like a lane failing
  to load when nothing is wrong.

## Consequences worth being explicit about

Reflecting `Origin` into `Access-Control-Allow-Origin` with credentials means a site's cookies ride
along with reads that the site's own CORS policy was written to refuse. This is Android's alone —
iOS reflects nothing — and it is why `InterceptionPolicy` is opt-in per WebView, defaults to
intercepting documents and data only, and is not something to point at a WebView that renders
untrusted content.

## The arrangement that was deleted, and what measuring it said

`AndroidFrameHost` put four `<iframe>`s in one WebView and used `shouldInterceptRequest` to strip
`X-Frame-Options` and CSP `frame-ancestors` on the way past, so that sites which refuse to be framed
would render in a lane anyway. Automation reached inside them with
`WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))`, which injects into every
frame including cross-origin ones, and `window.postMessage`, which is cross-origin by design. It
defeated nothing about the origin model: instead of reaching *into* a foreign document, it put an
agent inside and talked to it through the front door. It worked against the live web.

Both arrangements were run against *Price scout (fixtures)* on the `Medium_Phone_API_36.1` emulator
(`sdk_gphone64_arm64`, launched with `-memory 8192`, so `forDevice` granted the full four lanes).

**Renderer processes — the question that decided it.** Four `WebView`s produce **one** renderer
process. So does one `WebView` with four iframes. Counted with `ps -A | grep sandboxed_process`
against a baseline taken with the app force-stopped, before, during and after a run:

| | renderer processes attributable to the app |
|---|---|
| iframe host (1 WebView, 4 iframes) | 1 |
| WebView pool (4 WebViews) | 1 |

Android WebView shares a single renderer across every `WebView` in the app. **The parallelism
argument for one WebView per lane is an iOS argument and does not transfer** — but neither does the
cost argument for iframes, which is what mattered.

**Memory — closer than expected.** Warm, with both mounted in turn, the app's total PSS was 192 MB
under iframes and 188 MB under the pool. That difference is noise: the memory lives in the shared
renderer, so a second, third and fourth `WebView` do not each bring their own copy. Measured with
tiny in-memory fixture documents; four heavy third-party sites live at once is the case that would
separate them, and it was not measured.

**Wall-clock — no verdict.** Ten alternating runs each. Cold runs favoured the pool (8.4–12.9 s
against 13.7–18.3 s), warm runs landed on top of each other (0.7–1.4 s both ways), and the spread
within each arrangement was wider than the gap between them. The fixture scenario is served from
memory and finishes too fast to separate two arrangements on an emulator this noisy.

So the pool was never *faster* on Android. What it was, is smaller. Deleting the iframe arrangement
deleted a host document with a grid that collapsed unless it was `position: fixed`, a lane adoption
handshake with a minute-long retry window (four heavy sites parse on one thread, so the host frame's
turn to run could be seconds away), a navigation token to stop a stale `lane:ready` from satisfying
the wait for the incoming document, and `'unsafe-eval'` spliced into `script-src`, `script-src-elem`
**and** `default-src` of every site — because a document-start script runs in the page's own world,
so a strict-CSP site would load, answer the handshake, and then fail *every step* with a CSP
violation.

One piece of it was kept rather than deleted. The lane controller resubmitted a command once when
the page navigated out from under it, and that trap is not about iframes at all — it is about a
platform dropping a script callback when the document goes. It now lives in `WebViewSerializer`,
where every controller gets it, and it is trap 3 above.

It also gave up one capability, and it is worth knowing what it was: nothing can put a foreign site
*inside* a document of ours any more. An app that wanted its own chrome around a live third-party
page now renders that chrome in Compose around a lane instead.
