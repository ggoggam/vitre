# Plan: Bootstrap Vitre as a KMP webview-automation library with sample app

## Context

The legacy `vitre` project targeted web/mobile via a CORS-proxy + iframe approach. That approach is being abandoned because reliably bypassing browser CORS for arbitrary sites is impractical. The new direction is **mobile-only**, with Vitre positioned as a **Kotlin Multiplatform library** that downstream apps embed to define and run **automation workflows inside a WebView**. Native ↔ page communication uses the **`postMessage` API** (WebView WebMessage on Android, WKScriptMessageHandler on iOS) rather than network rewriting.

> **Update.** A *pool* came back — four sites driven at once — though not the iframe part. Android
> briefly had one: inside a WebView the app *is* the network stack, so framing headers and CORS were
> the app's to answer, and it worked against the live web. It was deleted after measurement showed
> it bought no parallelism (every WebView in the app shares one renderer either way) and cost six
> failure modes to keep. A lane is now one WebView loading its site top-level, on both platforms,
> which makes the framing headers moot rather than answering them. See
> [PARALLEL-LANES.md](PARALLEL-LANES.md). The `postMessage` bridge is unchanged and is what carries
> lane traffic.

Build system: **AGP 9** (version catalog pinned to AGP 9.0.1 / Kotlin 2.3.10 / Compose Multiplatform 1.10.1, modeled after [`watermelonKode/kmp-wizard-template-agp-9-build-logic`](https://github.com/watermelonKode/kmp-wizard-template-agp-9-build-logic) — but each module applies plugins directly, no shared convention-plugin layer). UI: **Compose Multiplatform**.

## Decisions

- **Gradle root = repo root** (no `mobile/` wrapper).
- **Library split**: `:vitre-core` (pure KMP — bridge + workflow DSL + engine) and `:vitre-compose` (Compose Multiplatform `VitreWebView` composable depending on core).
- **Sample app = workflow gallery** (list of example workflows, run one against a WebView, show step events + extracted variables). Sample modules live under `sample/`.
- **iOS entry**: stub Xcode project so the demo can compile/run on iOS once opened in Xcode.
- **Tool versions** are pinned in `mise.toml` (Java 21 + Gradle 9.1.0).

## Target layout

```
/vitre/
├── mise.toml                          # tool versions + tasks
├── docs/PLAN.md                       # this file
├── settings.gradle.kts                # rootProject "vitre"; includes :vitre-core / :vitre-compose / :sample:composeApp / :sample:androidApp
├── build.gradle.kts                   # apply false declarations
├── gradle.properties                  # AGP 9 flags + -Xss16m for the Kotlin/Native compiler
├── gradle/
│   ├── libs.versions.toml             # versions catalog
│   ├── gradle-daemon-jvm.properties   # JBR 21 toolchain
│   └── wrapper/{gradle-wrapper.properties,gradle-wrapper.jar}
├── gradlew, gradlew.bat
├── vitre-core/                   # KMP library; no UI
│   └── src/{commonMain,androidMain,iosMain,commonTest}/...
├── vitre-compose/                # Compose Multiplatform UI layer
│   └── src/{commonMain,androidMain,iosMain}/...
└── sample/
    ├── composeApp/                    # Sample shared UI — workflow gallery
    │   └── src/{commonMain,androidMain,iosMain}/...
    ├── androidApp/                    # Android launcher
    └── iosApp/                        # Stub Xcode project
```

## Module responsibilities

- **`:vitre-core`** owns the public API: `Workflow`, `WorkflowStep` (sealed), `WorkflowEvent` (sealed), `WorkflowEngine`, `WebViewBridge`, `BridgeMessage`, and the `WebViewController` interface plus Android/iOS actuals.
- **`:vitre-compose`** provides `VitreWebView` — a Compose composable that mounts a `WebView`/`WKWebView` and wires it into a `WebViewController`.
- **`:sample:composeApp`** is the sample shared UI: a workflow gallery (list + runner panel) consuming the library exactly the way a downstream developer would.
- **`:sample:androidApp`** / `sample/iosApp/` are platform launchers — minimal code, they just host `App()` from `:sample:composeApp`.

## Bridge design (the key new abstraction)

`dev.ggoggam.vitre.core.bridge.WebViewBridge`:

```kotlin
interface WebViewBridge {
    val incoming: SharedFlow<String>
    suspend fun postToWebView(message: String)
}
```

`BridgeMessage` is a `@Serializable` envelope `{ id, type, payload: JsonElement }` so workflows can correlate request/response pairs.

**Android** uses `androidx.webkit.WebViewCompat.addWebMessageListener("vitre", setOf("*"), listener)` — the page calls `window.vitre.postMessage(msg)` and native receives via `MessageListener.onPostMessage`. Outbound: `webView.evaluateJavascript("window.dispatchEvent(new MessageEvent('vitre', {data: ...}))", null)`.

**iOS** injects a `WKUserScript` at document-start that defines `window.vitre.postMessage = (m) => window.webkit.messageHandlers.vitre.postMessage(m)`, and registers a `WKScriptMessageHandler` for the `"vitre"` name. Outbound: same `evaluateJavaScript` shape.

## Workflow DSL

`WorkflowStep` (sealed):
- `Navigate(url)`
- `WaitFor(selector, timeoutMs)` — polls `document.querySelector(selector) !== null`
- `Click(selector)`
- `Input(selector, text)` — sets `value` + dispatches `input`/`change`
- `Extract(locator, into, from = Text | Attribute(name) | Property(name))`
- `ExtractRows(rows, columns, into, limit)` — one record per matching element, columns resolved
  *within* each row, stored as a JSON array. What a list of search results needs and what repeated
  `Extract` cannot express.
- `EvaluateJs(script, into?)` — escape hatch; must be an expression
- `LoadHtml(html, baseUrl?)` — load a document directly, for pages the caller owns
- `PostMessage(message)` — send a bridge message to the page
- `AwaitMessage(type, into, timeoutMs)` — suspends until the page posts a matching bridge message

Every element-addressing step takes a `Locator` — `css("…")` or `xpath("…")`. A bare string still
means CSS, so the shorthand constructors keep the common case short. XPath earns its keep where CSS
cannot reach: matching on visible text, walking up the tree (`ancestor::`), selecting an attribute
as a node, positional predicates, and `count()`. Neither pierces shadow DOM.

`WorkflowEngine.run(workflow): Flow<WorkflowEvent>` takes a `WebViewController` and emits `StepStarted` → `StepCompleted` per step, `Completed(variables)` at the end, or `Failed(stepIndex, message)`.

## Use-case-driven TDD

The framework is built test-first. Each test below describes an **end-user use case** of Vitre — what a downstream app developer wants to do. Write the test, watch it fail with a clear message, then implement the minimum to make it green before moving to the next.

### Test harness

All tests live in `vitre-core/src/commonTest/` and run as a Kotlin/Native binary on the iOS Simulator (`./gradlew :vitre-core:iosSimulatorArm64Test`, aggregated via `:vitre-core:allTests`). The current AGP 9 KMP-library plugin doesn't add a JVM host-test task; we picked the iOS Simulator runner because it exercises the same `commonTest` sources without needing an emulator. `FakeWebViewController` in `src/commonTest/kotlin/dev/ggoggam/vitre/core/testing/` implements `WebViewController` and records calls / replays scripted responses.

### Use cases → failing tests → implementation

Each use case maps to one `kotlin.test` test. We implement in this order; each row's "implementation" makes only that row's test pass.

| # | Use case (developer voice) | Test file | Implementation that turns it green |
|---|---|---|---|
| **1** | "I can describe a workflow as a list of typed steps and the engine reports `StepStarted`/`StepCompleted` events in order." | `WorkflowEngineHappyPathTest.kt` | `Workflow`, `WorkflowStep.Navigate`, `WorkflowEvent`, `WorkflowEngine` skeleton with `Navigate` dispatch only. Assert via `FakeWebViewController.navigations`. |
| **2** | "When a step throws, the engine emits `Failed(stepIndex, message)` and stops — no further steps run." | `WorkflowEngineFailureTest.kt` | Wrap the step loop in try/catch; emit `Failed` with the offending index. |
| **3** | "I can extract a value from the page into a named variable, and `Completed.variables` contains it." | `ExtractStepTest.kt` | `WorkflowStep.Extract`; engine generates the `document.querySelector(...).textContent` JS, reads via `nextEvalResult`, stores in the variables map. |
| **4** | "I can `WaitFor(selector)` and the engine polls until the page reports the element is present, then proceeds — or fails on timeout." | `WaitForStepTest.kt` | `WorkflowStep.WaitFor`; polling loop with `delay`, scripted fake responses. Uses `runTest` virtual time. |
| **5** | "I can `AwaitMessage(type=\"ready\")` and the engine suspends until the page posts a matching bridge message via `window.vitre.postMessage`." | `AwaitMessageStepTest.kt` | `WorkflowStep.AwaitMessage`; engine calls `bridge.awaitMessage`, parses envelope, matches on `type`. Test drives it with `fake.simulatePageMessage(...)`. Messages are buffered and consumed once, so a message that arrives *before* the step begins still matches — see `docs/CONCURRENCY.md`. |
| **6** | "I can post a message from native to the page and the right JS dispatch script is executed." | `BridgePostToWebViewTest.kt` | `WebViewBridge.postToWebView` impl that calls `evaluateJs("window.dispatchEvent(new MessageEvent('vitre', {data: ...}))")`. Asserts on `fake.evaluatedScripts`. |
| **7** | "The bridge envelope round-trips through `kotlinx.serialization` without losing typed payload fields." | `BridgeMessageSerializationTest.kt` | `@Serializable BridgeMessage(id, type, payload: JsonElement)`. |
| **8** | "I can `Click`/`Input` and the generated JS escapes selectors/text safely (single quote, backslash, newline)." | `JsEscapingTest.kt` | `Click`, `Input`, plus a private `jsString(...)` helper. Asserts script content. |

Tests 1–8 cover the **library's promise to downstream developers**. They are platform-agnostic, live in `commonTest`, and run on every push. As implemented the eight classes expand to 15 individual `@Test` methods (e.g. `ExtractStepTest` has both `textContent` and attribute variants); the full suite passes via `:vitre-core:allTests`.

### Platform actuals — covered by smoke tests, not unit tests

The Android and iOS actuals can't be unit-tested without an instrumented harness, so the sample app's gallery covers them. **"Bridge round trip" is the smoke test for each platform**: it loads a page the sample ships, types, clicks, receives a bridge message, sends one back, and reads the result, so a failure means the library broke.

It replaced "example.com title" in that role, which was a bad choice twice over. A live site can be redesigned out from under the test — and this one never passed on iOS at all: WebKit returns a JS `true` as the string `"1"`, so `WaitFor` polled until it timed out on every iOS workflow, and nothing noticed because the test double returned a third encoding again. The iOS actual now normalises through `JSON.stringify`, and the fixture needs no network to prove it.

## Build order

```
Step 0: Write plan to docs/PLAN.md + mise.toml.
Step 1: Gradle skeleton (settings, build, gradle.properties, libs.versions.toml).
Step 2: vitre-core scaffolding — interfaces + FakeWebViewController harness.
Step 3: Tests 1 → 8, one by one (red → green → next).
Step 4: vitre-compose expect/actuals + VitreWebView.
Step 5: sample/composeApp gallery + sample workflows.
Step 6: sample/androidApp entry.
Step 7: sample/iosApp Xcode stub.
Step 8: Verify Gradle build + TDD suite; flag iOS as user-verify.
```

## Verification

1. **Tests**: `./gradlew :vitre-core:allTests` — all 8 TDD case classes (15 tests total) green.
2. **Build**: `./gradlew :vitre-core:assemble :vitre-compose:assemble :sample:composeApp:linkDebugFrameworkIosSimulatorArm64 :sample:androidApp:assembleDebug` succeeds under AGP 9 — this is what `mise run build` invokes (the broader `:build` aggregate would also run the failing release iOS link; see Known issues).
3. **Android smoke**: `mise run dev:android` → gallery loads → "Bridge round trip" completes all 8 steps and `fromPage` holds the page's pong.
4. **iOS smoke**: `mise run dev:ios` → same gallery, same expected result.
5. **Configuration cache**: rerun any Gradle task with `--configuration-cache` — must hit the cache the second time.

## Known issues

- `:sample:composeApp:linkReleaseFrameworkIosX64` (and the matching `IosArm64`/`IosSimulatorArm64` release links) crash inside the Kotlin/Native `DevirtualizationAnalysis` LTO phase with the current Kotlin 2.3.10 + Compose Multiplatform 1.10.1 + AGP 9.0.1 toolchain. The debug-variant frameworks build cleanly and are what Xcode invokes by default for development, so the local dev loop is unaffected. `gradle.properties` already sets `-Xss16m` for the Gradle daemon; if releases are still required, the next workaround is to retry with a newer Kotlin/Native or disable LTO via `freeCompilerArgs`. The `mise run build` task therefore links only the debug iOS simulator framework.

## Explicit non-goals (this pass)

- No proxy / iframe pool / Zipline / QuickJS rewriter — replaced by the bridge model. (A lane pool exists, but its lanes are whole WebViews rather than frames.)
- No Ktor, Coil, or other transitive Compose-side dependencies that the watermelonKode template includes by default.
- No further library module splits (e.g. carving `:vitre-workflow` out of `:vitre-core`) — defer until pressure justifies.
- No CI / pre-commit / publishing config — follow-up.
- No tests on the WebView actuals — covered by the sample-app smoke test. A `commonTest` contract test every `WebViewController` must pass would be worth more: the fake was laxer than production in exactly the two places production was broken (it replayed bridge messages the real controllers dropped, and returned `"true"` where iOS returned `"1"`).
