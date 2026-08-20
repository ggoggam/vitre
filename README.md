# Vitre

**WebView automation for Kotlin Multiplatform.** Describe what you want done to a page as a list of
steps, and run it inside an embedded WebView on Android, iOS and the desktop — from your app, from
a test, or
from an LLM agent over the Model Context Protocol.

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20Desktop-brightgreen)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

```kotlin
val workflow = workflow("hn-top-story", "Hacker News top story") {
    navigate("https://news.ycombinator.com/")
    waitFor(".titleline > a", timeoutMs = 15_000)
    extract(".titleline > a", into = "headline")
    extract(".titleline > a", into = "url", from = Source.Attribute("href"))
}

WorkflowEngine(controller).run(workflow).collect { event ->
    if (event is WorkflowEvent.Completed) println(event.variables["headline"])
}
```

The same vocabulary is available three ways: as the Kotlin DSL above, as a Compose composable you
drop into a screen, and as MCP tools an agent can call without knowing the page in advance.

The block is a *builder*, not a script. It runs once, up front, to assemble the step list that
`WorkflowEngine` then walks — so ordinary Kotlin control flow inside it chooses what the workflow
*contains*, and cannot branch on the page or on a variable an earlier step extracted. The
`WorkflowStep` constructors remain public and equivalent; they are what `vitre-mcp` uses, since
an agent's steps arrive as JSON rather than as Kotlin.

## Why

Mobile apps already embed WebViews, and everything people want to do with them — read a page,
fill a form, talk to the page's own script, scrape a table, run four sites at once — ends up as
one-off `evaluateJavascript` calls glued to a callback. Those calls race each other, they race the
UI, and none of it is shared between Android, iOS and the desktop.

Vitre makes the page a thing you can *drive*: one ordering guarantee, one step vocabulary, one
codebase for every platform, and a snapshot format an agent can read.

- **Declarative steps** — `Navigate`, `LoadHtml`, `WaitFor`, `Click`, `Input`, `Extract`,
  `ExtractRows`, `Snapshot`, `EvaluateJs`, `PostMessage`, `AwaitMessage`.
- **Three locator kinds** — CSS, XPath, and handles issued by a page snapshot.
- **Typed reads** — `evaluateJs` returns a script's result JSON-encoded on every platform, so
  `controller.evaluate<Boolean>(…)` decodes it instead of comparing it against `"true"`.
- **A real bridge** — `postMessage` in both directions, with an inbox so a message that arrives
  before you wait for it is not lost, and typed payloads on both ends —
  `bridge.request<Ack, Token>(…)` for a round trip, `decodePayload<Token>(…)` for a workflow's.
- **Up to four sites at once** — one WebView per lane, one workflow engine each, queued so six
  workflows on a two-lane device run three deep instead of losing four.
- **Agent-ready** — `vitre-mcp` exposes the whole vocabulary as MCP tools over an in-process
  transport.
- **Ordered by construction** — every platform call is confined to the WebView thread and totally
  ordered, so the engine, your UI, and an agent can drive the same page without special-casing each
  other.

> **Web target is out of scope.** Browser CORS rules make a general-purpose web automation framework
> impractical there; see [docs/PLAN.md](docs/PLAN.md).

## Requirements

| | |
|---|---|
| Kotlin | 2.3.10 (Multiplatform) |
| Android | minSdk 24, compileSdk 36 |
| iOS | 15.0+ (`WKWebView`) |
| Desktop | JVM 17+ (Chromium via [KCEF](https://github.com/DATL4G/KCEF)) — macOS, Linux, Windows |
| UI layer | Compose Multiplatform (optional — `vitre-core` has no Compose dependency) |

## Installation

Not published to Maven Central yet. For now, consume it as a source dependency — clone the repo
next to your project and include it from `settings.gradle.kts`:

```kotlin
includeBuild("../vitre")
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.ggoggam.vitre:vitre-core")
            implementation("dev.ggoggam.vitre:vitre-compose") // optional
            implementation("dev.ggoggam.vitre:vitre-mcp")     // optional
        }
    }
}
```

Or add the modules directly with `include(":vitre-core")` if you vendor the sources.

## Quick start

Mount a WebView, take its controller, run a workflow against it:

```kotlin
@Composable
fun Screen() {
    val state = rememberVitreWebViewState("https://example.com")
    val controller = state.controller

    LaunchedEffect(controller) {
        val page = controller ?: return@LaunchedEffect
        WorkflowEngine(page).run(SampleWorkflows.ExampleDotComTitle).collect { event ->
            when (event) {
                is WorkflowEvent.StepStarted -> log("→ ${event.step}")
                is WorkflowEvent.Completed -> log(event.variables["title"])
                is WorkflowEvent.Failed -> log("step ${event.stepIndex}: ${event.message}")
                else -> Unit
            }
        }
    }

    VitreWebView(state = state, modifier = Modifier.fillMaxSize())
}
```

`state.controller` is null before the WebView mounts and null again after it leaves the composition,
so an effect keyed on it starts when the page arrives and tears down when it goes away. Nothing runs
against a dead WebView.

## Example use cases

### 1. Read a page your app doesn't own

The smallest thing worth doing: navigate, wait for the element that means the page is ready, read
what you came for. Text is `textContent`; `href` is genuinely an attribute, so it is read as one.

```kotlin
workflow("hn-top-story", "Hacker News top story") {
    navigate("https://news.ycombinator.com/")
    waitFor(".titleline > a", timeoutMs = 15_000)
    extract(".titleline > a", into = "headline")
    extract(".titleline > a", into = "url", from = Source.Attribute("href"))
}
```

### 2. Pull a whole table out in one step

`ExtractRows` returns one JSON record per matching row, with each column resolved *within* that row.
That scoping is the point: a row missing a price yields an empty string in that record instead of
shifting every later record onto the wrong product.

```kotlin
extractRows(rows = xpath("//li[@data-sku]"), into = "results", limit = 10) {
    // "." is the row itself — read its own attribute.
    column("sku", xpath("."), from = Source.Attribute("data-sku"))
    column("price", xpath(".//span[@class='price']"))
    // Matched on the text a human reads, not on a class hook.
    column("stock", xpath(".//*[normalize-space()='In stock']"))
    // Up to the row and back down — CSS has no parent combinator.
    column("seller", xpath(".//span[@class='price']/ancestor::li[1]//span[@class='seller']"))
}
```

### 3. Talk to a page you *do* own (hybrid apps)

If the page is yours, the bridge beats scraping it. `PostMessage` sends a
`MessageEvent('vitre')` into the page; `AwaitMessage` waits for `window.vitre.postMessage`
coming back. The inbox buffers, so a handler that posts *synchronously* on click is still matched by
an `AwaitMessage` that starts afterwards — the case that silently loses messages elsewhere.

```kotlin
@Serializable data class Ack(val seen: Boolean)
@Serializable data class Token(val value: String, val expiresAt: Long)

loadHtml(html = checkoutHtml, baseUrl = "https://app.example.com")
waitFor("#pay")
click("#pay")
awaitMessage(type = "payment-token", into = "token", timeoutMs = 5_000)
postMessage(type = "ack", payload = Ack(seen = true), id = "ack-1")
```

Payloads are classes, not hand-typed envelope strings — `id` and `type` stay arguments because they
are protocol. The reply arrives in a variable, and the typing picks up again where the values are:

```kotlin
if (event is WorkflowEvent.Completed) use(event.decodePayload<Token>("token"))
```

That split is not an oversight. A workflow block is a *builder*: it runs once to assemble the step
list, and the steps run later, so there is no point in the block at which a reply could be returned
to it. When you want a round trip as one expression, you want the host API rather than a workflow —
`request` posts, correlates the answer by `replyTo`, and gives it back typed:

```kotlin
val token: Token = controller.bridge.request<Ack, Token>("issue-token", Ack(seen = true))
```

Post-then-await does not race: the inbox buffers, so a page handler that replies synchronously —
the normal case — is still matched by the wait that starts after it.

The same move applies to a script's own result. `evaluateJs` hands back the JSON encoding of what
the expression produced — that contract is the one thing every platform was made to agree on —
so a value read out of a page can be decoded rather than string-matched:

```kotlin
val ready: Boolean = controller.evaluate("document.readyState==='complete'")
val rows: List<Product> = controller.evaluate("Array.from(document.querySelectorAll('li')).map(toRow)")
```

### 4. Search four sites at once

One WebView per lane, each loading its site as a top-level document — which is what keeps sessions
first-party and `X-Frame-Options` out of the picture. Hand the pool every workflow and it drains
them across however many lanes the device could carry.

```kotlin
var pool by remember { mutableStateOf<FramePool?>(null) }

VitreFrameHost(
    laneCount = 4,
    policy = InterceptionPolicy(handlers = shopFixtures),
    onPoolReady = { pool = it },
)

// Six workflows on a two-lane device run three deep — nothing is dropped.
pool?.run(shops.map { it.workflow(query) })?.collect { (taskIndex, laneId, _, event) ->
    if (event is WorkflowEvent.Completed) merge(event.variables["results"])
}
```

The sample's *Price scout* does exactly this: four synthetic shops at four distinct origins, merged
and ranked by *delivered* price — which for most of the catalogue is a different shop from the
cheapest sticker price.

### 5. Offline, deterministic page tests

A `RequestHandler` answers requests from memory, so a test drives a real WebView against a real
origin with no network and no flake. This is how the parallel-lane demo stays a usable smoke test.

```kotlin
val fixtures = RequestHandler { request ->
    when (request.host) {
        "shop-a.test" -> InterceptedResponse(body = shopAHtml.encodeToByteArray())
        else -> null // fall through to the network
    }
}

VitreFrameHost(policy = InterceptionPolicy(handlers = listOf(fixtures)), …)
```

Interception is real on Android and the desktop, which both let an application answer a request
outright — `shouldInterceptRequest` on one, CEF's resource pipeline on the other. iOS is the
exception: `WKURLSchemeHandler` refuses to register for `https`, so fixtures there are served from a
private scheme and nothing can rewrite a response header. The failure modes are spelled out in
[docs/PARALLEL-LANES.md](docs/PARALLEL-LANES.md).

### 6. Let an agent drive the page

`Snapshot` answers the question a hand-written workflow never has to ask: *what is on this page?* It
returns the interactive and text-bearing elements as an indented outline with a handle each — the
same information as the HTML at roughly a third of the tokens.

```
Vitre fixture — https://fixture.vitre.test/
heading "Vitre fixture" [ref=e1]
textbox value="typed by handle" [ref=e3]
button "Send pong to native" [ref=e4]
```

From there the agent names no selectors at all:

```kotlin
snapshot(into = "page")
input(handle("e3"), text = "typed by handle")
click(handle("e4"))
extract(handle("e5"), into = "status")
```

`vitre-mcp` puts that behind an MCP server — `snapshot`, `navigate`, `click`, `type`,
`wait_for`, `extract`, `extract_rows`, `evaluate`, `send_message`, `await_message`, plus
`acquire_lease` / `release_lease` for holding a page across several calls. The host registers its
WebViews and the server drives exactly those:

```kotlin
val sessions = WebViewSessions().apply { register("main", controller, "the shopping tab") }
val server = McpServer(sessions, scope)
val transport = InProcessMcpTransport(server)
```

It ships an **in-process transport only**, on purpose: a loopback socket would expose page
automation to anything on the device that can reach the port, which on a WebView signed into the
user's accounts is not an automation leak but a session one. See [docs/MCP.md](docs/MCP.md).

## Locators

Every element-addressing step takes a `Locator`: `css("#results .item")`, `xpath("//li[@data-sku]")`
or `handle("e7")`. A bare string still means CSS.

| | Use it for |
|---|---|
| `css(…)` | The common case. Short, familiar, fast. |
| `xpath(…)` | Matching on visible text, walking *up* the tree with `ancestor::`, selecting an attribute as a node, positional predicates, `count()`. |
| `handle(…)` | Addressing an element a `Snapshot` already found — the agent's locator. |

Neither CSS nor XPath pierces shadow DOM; that is the page's doing, not the query language's.

A handle is the third kind and behaves differently by design: the other two describe *how to
search*, a handle *names* an element. It is issued by the page, dies with the document that issued
it, and is never recycled — so a handle from the previous page fails loudly instead of resolving
against a same-shaped element on the new one.

## Threading

A WebView owns a thread — the platform main thread — and it is not negotiable: `WKWebView` is UIKit,
and an Android `WebView` must be used on the thread that built it. So callers do not synchronise
with each other, they queue. One object, `WebViewSerializer`, confines every platform call to that
thread and totally orders them, which is what lets the workflow engine, the UI, and an agent drive
the same page without special-casing each other. The engine itself runs on `Dispatchers.Default` and
never sees the WebView thread.

See [docs/CONCURRENCY.md](docs/CONCURRENCY.md) for the model and the bugs it fixed.

## Modules

| Module | Purpose |
|---|---|
| `vitre-core` | Pure KMP library: workflow DSL, engine, bridge protocol, `WebViewController` actuals, lane pool, network interception. |
| `vitre-compose` | Compose Multiplatform layer: `VitreWebView` and `VitreFrameHost`. |
| `vitre-mcp` | MCP server over one or more WebViews: tool schemas, session registry, leases. |
| `sample/composeApp` | Shared sample UI — a workflow gallery demonstrating the library. |
| `sample/androidApp` | Sample Android launcher hosting `composeApp`. |
| `sample/iosApp` | Sample iOS Xcode project hosting `composeApp` via the KMP framework. |
| `sample/desktopApp` | Sample desktop launcher hosting `composeApp`, with the KCEF startup gate. |

## Building

```bash
mise install            # ktlint (mise.toml) + the android CLI (mise.local.toml)
mise run test           # core + mcp + sample allTests
mise run build          # library artifacts + Android sample APK + iOS debug framework
mise run lint           # ktlint
mise run fmt            # ktlint --format
```

A JDK is not declared in `mise.toml` — Gradle uses whatever JDK is on your machine, and the
toolchain is resolved via foojay. CI installs its own from `mise.ci.toml`. `mise run wrapper`
regenerates the Gradle wrapper and is the one task that needs a system `gradle`; you only need it
when bumping Gradle.

### Running the sample

```bash
mise run dev:android    # build + install + launch on a phone, or boot an AVD
mise run dev:ios        # build + install + launch on a simulator
mise run dev:desktop    # build + launch the desktop window
```

All three build and launch in one step, so none of them needs Android Studio or Xcode open.
`dev:android` prefers a plugged-in device over an emulator and boots the first AVD if nothing is
attached; `dev:ios` reuses a booted simulator, or set `VITRE_SIM` to pick one by name. They
live in `mise.local.toml` (dev-only — CI never runs them). `mise run android:install` is the plain
fallback: `gradlew installDebug` onto whatever adb already sees, no android CLI.

You can also open `sample/iosApp/iosApp.xcodeproj` in Xcode and hit Run — the target's "Compile
Kotlin Framework" phase builds the KMP framework first either way.

`dev:desktop` is the odd one out on first run: unlike a WebView, Chromium is not already on the
machine, so KCEF downloads and unpacks a bundle of a few hundred megabytes into
`~/.vitre/kcef-bundle` before the gallery appears. The sample shows that as a progress screen
and it happens once per machine; later launches go straight to the window.

### What's in the sample gallery

The gallery opens on the **agent chat**: a WebView with a conversation under it, where every action
on the page arrives as an MCP tool call and the calls and their results are shown rather than hidden
behind the answer. The model is mocked — no LLM API, no key — but nothing downstream of it is: each
turn issues a real `tools/call` over JSON-RPC and the answer is computed from what came back. One
scripted exchange guesses a CSS selector that does not match, so the transcript shows a tool failure
arriving as an `isError` *result* the model reads and corrects, rather than as an exception that
ends the run.

Below it, two **parallel-lane scenarios** — *Price scout* (four synthetic cross-origin shops, ranked
by delivered price) and *Live pages probe* (four real sites, reporting from inside each, including
whether a cross-origin `fetch` got through).

Then the **single-page workflows**. Two drive a page the sample ships, loaded straight into the
WebView with no network; those are the ones that can demonstrate the bridge at all — `AwaitMessage`
waits for `window.vitre.postMessage`, and no third-party site will ever call it — and they
double as the smoke test, since a failure means the library broke rather than that a website was
redesigned. The other two hit the live web to show the same steps against real pages, and are the
ones that will eventually rot.

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | Architecture, module layout, bridge design, the TDD use-case matrix. |
| [docs/CONCURRENCY.md](docs/CONCURRENCY.md) | The threading model, the bugs it fixed, how MCP slots in on top. |
| [docs/PARALLEL-LANES.md](docs/PARALLEL-LANES.md) | Lanes, interception, CORS, the traffic tap, platform differences. |
| [docs/MCP.md](docs/MCP.md) | Tool list, handle lifetime, leases, protocol and transport decisions. |
| [docs/ASYNC-BRIDGE.md](docs/ASYNC-BRIDGE.md) | The `postMessage` bridge protocol end to end. |

## Contributing

Issues and pull requests are welcome.

```bash
mise install
mise run fmt:check      # ktlint, same check CI runs
mise run test
```

The repo uses [prek](https://github.com/j178/prek) pre-commit hooks (`mise run pre-commit`), and
ktlint with the official Kotlin code style. A few conventions worth knowing before you send a patch:

- **Comments explain *why*.** Much of this codebase's value is in the reasoning recorded next to
  non-obvious decisions — platform seams, ordering guarantees, failure modes. Keep that up.
- **Tests come first for anything in `vitre-core`.** The engine, bridge and pool are covered by
  `commonTest` against a fake controller; a behaviour change should show up there before it shows up
  in a WebView.
- **Both platforms or neither.** If a change can only work on one, say so where a caller will read
  it, the way interception does.

## License

[MIT](LICENSE) © 2026 Joon Kwon
