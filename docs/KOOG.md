# `vitre-koog` — driving the WebView from a Koog agent

[Koog](https://github.com/JetBrains/koog) is JetBrains' Kotlin agent framework. This module puts
Vitre's page vocabulary into it: an agent built with Koog gets `snapshot`, `click`, `extract_rows`
and the rest as ordinary Koog tools, on Android, iOS and the desktop.

There are two ways in, and a plugin that makes both of them safe to leave running while a user is
looking at the same WebView.

## The short version

```kotlin
val sessions = WebViewSessions().apply { register("main", controller, "the shopping tab") }
val driver = PageDriver(sessions, scope)

val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4_1,   // yours; this module brings no LLM client
    systemPrompt = PageToolDocs.INSTRUCTIONS,
    toolRegistry = ToolRegistry { vitreWebView(driver) },
)

agent.run("Find the cheapest wireless keyboard and tell me the delivered price.")
```

`PageDriver` is the page; `ToolRegistry` is Koog's. Nothing else is needed, and no MCP server is
involved.

Two things the snippet does not show, both found by
`vitre-koog/src/jvmTest/.../LiveModelDrivesThePageTest.kt`, which runs this against a real model:

**An LLM client is not enough on its own.** Koog resolves its HTTP transport through a service-loader
lookup, so a build with `prompt-executor-anthropic-client` and nothing else compiles and then fails
at the first request with `No KoogHttpClient.Factory provider found`. Add `ai.koog:http-client-ktor`
(or another Factory provider) alongside whichever client you pick.

**The default strategy stops early on a model that narrates.** Anthropic models routinely answer with
one message holding both a text part and a tool call — `[Text("I'll search now."), Tool.Call(type)]`.
The default `AIAgent(promptExecutor, llmModel, …)` loop above takes the text branch on such a message
once tool results are already in the history, so the run ends after the *first* tool call and
delivers "I'll search now." as the final answer. Driving the same prompt and the same tools by hand
shows the model completing the whole task, so this is the graph's branch order rather than the model,
the tools or [`PageToolDocs`](../vitre-agent/src/commonMain/kotlin/dev/ggoggam/vitre/agent/PageToolDocs.kt).

Give it a loop that checks tool calls before text at both branch points:

```kotlin
val pageStrategy = strategy<String, String>("vitre-page") {
    val request by nodeLLMRequest()
    val runTools by nodeExecuteTools()
    val sendResults by nodeLLMSendToolResults()

    edge(nodeStart forwardTo request)
    edge(request forwardTo runTools onToolCalls { true })
    edge(request forwardTo nodeFinish onTextMessage { true })
    edge(runTools forwardTo sendResults)
    edge(sendResults forwardTo runTools onToolCalls { true })   // <- before the text edge
    edge(sendResults forwardTo nodeFinish onTextMessage { true })
}

val agent = AIAgent(executor, model, pageStrategy, systemPrompt = PageToolDocs.INSTRUCTIONS, …)
```

With the default loop the live test drove the page once and stopped; with this one it takes a
snapshot, types, clicks, snapshots again and reads the results table — ten page operations rather
than one.

## Why the semantics do not live in this module

`vitre-mcp` opens with the reason its tools build `WorkflowStep`s rather than generating their own
JavaScript: the library already had two implementations of "talk to a WebView" — the Android and iOS
actuals — and they drifted until a boolean meant `true` on one platform and `"1"` on the other,
which nothing noticed for as long as it took to write the smoke test.

A second *protocol* is the same hazard one level up. MCP and Koog both need "click, but fail if the
element was never there". Both need a handle guarded against a stale document, both need timeouts
clamped so a model cannot park a WebView for a day, both need to tell a model not to guess a
selector for a page it has not looked at. Written twice, those agree on the day they are written and
not much longer — and the divergence is invisible, because each adapter's tests pass against its own
copy.

So there is a module underneath both:

```
vitre-core     steps, engine, controllers, snapshots, handles
     │
vitre-agent    PageDriver ── the page actions, with their rules and their failure messages
     │         WebViewSessions, SessionLeases ── stateless call → stateful WebView
     │         PageToolDocs ── what a model is told about each tool
     ├──────────────┐
vitre-mcp      vitre-koog
  JSON-RPC,      Koog tools,
  schemas        descriptors, the lease feature
```

An adapter is only ever the part that is genuinely its own: **how arguments arrive, and how a
failure is spelled.** Which steps a click expands to, how long a timeout may be, what a stale handle
costs, and the sentence a model reads before choosing between a handle and a guessed selector are
all one layer down.

That last one is worth being concrete about. `PageToolDocs.REF` is the description of the `ref`
argument, and it is the same string in the MCP server's JSON Schema and in the Koog tool's
`@LLMDescription`. `VitreMcpBridgeTest` builds both adapters' descriptors and asserts every tool
description and every argument description is equal between them — which is an assertion that can
only live here, in the module that can see both. Two separate assertions, one per adapter, that
`ref` mentions `snapshot` would prove nothing: they read the same constant, so they pass and fail
together, and neither would notice the day one adapter stopped reading it.

## Path 1: native Koog tools

`vitreWebViewTools(driver)` returns thirteen `ToolBase` implementations with typed, `@Serializable`
arguments. This is the one to reach for.

| Tool | |
|---|---|
| `list_sessions` | Which WebViews are registered |
| `snapshot` | The page as handles — start here |
| `navigate` | Load a URL, invalidating every handle |
| `click`, `type`, `wait_for` | Act on one element |
| `extract`, `extract_rows` | Read one value, or a table |
| `evaluate` | The escape hatch |
| `send_message`, `await_message` | The bridge, for a page the app owns |
| `acquire_lease`, `release_lease` | Hold the page across a sequence |

Names and argument names match the MCP server's exactly — `timeout_ms`, not `timeoutMs` — so a
system prompt written against one adapter works unchanged against the other. They are also short
enough to collide with another toolset in the same registry; `ToolRegistry` fails loudly when that
happens, which is the right time to find out.

### How a failure reaches the model

A page failure is not a broken agent. "Timeout waiting for css `#buy`" is something a model can act
on — take a snapshot, pick a real handle, try again — so the tools raise `ToolException`, which Koog
turns into a `ValidationError` result carrying the message back to the LLM rather than ending the
run. Anything else propagates: a controller closed under the agent is not something a model can
retry its way out of.

This is the same decision MCP's `isError` encodes, in Koog's vocabulary.

## Path 2: bridging an MCP server you already run

An app that already exposes its WebViews over MCP — for a desktop client, or because its own agent
chat speaks the protocol — should not maintain two descriptions of the same thirteen tools.

```kotlin
val server = McpServer(sessions, scope)
val registry = vitreMcpToolRegistry(server)
```

Discovery is a `tools/list` round trip, so it suspends and cannot happen inside `ToolRegistry { }` —
which is what `vitreMcpToolRegistry` is for. `vitreMcpTools(server)` returns the bare list if you are
assembling a registry with other tools in it.

The tool list is discovered at runtime from `tools/list` and the JSON Schemas are translated into
Koog `ToolDescriptor`s, so a tool added to the server appears here without a line of code. The price
is that arguments are an untyped `JsonObject` checked by the server rather than by the compiler.

`vitreMcpInstructions(transport)` returns the server's `instructions` — the same job a Koog system
prompt does — so a host bridging one to the other does not retype it.

There is no socket anywhere in this. `InProcessMcpTransport` is a direct call, and it is the only
transport the module ships; see [MCP.md](MCP.md) for what a loopback listener would expose on a
WebView signed into the user's accounts.

### Which to use

Prefer the native tools. Reach for the bridge when the MCP server already exists in your app and you
want exactly one description of the toolset. Running both against one `PageDriver` is fine, and it is
what you want: `McpServer` exposes the driver it built, so `vitreWebView(server.driver)` puts both
adapters on one lease registry. Two registries corrupt nothing — a lease is ultimately a claim on the
controller, and the second registry's holder simply waits — but they issue ids the other has never
heard of, so a sequence started over MCP cannot be continued from Koog.

## The plugin: `VitrePageLease`

Vitre orders every operation on a WebView against every other, so no two callers can corrupt each
other's individual step. What ordering cannot do is make a *sequence* indivisible:

```
agent:  wait_for(".price")        the app's UI:  user taps "next page"
agent:  extract(".price")   ←  reads the price on the page the user just opened
```

Every one of those operations was properly serialised and the answer is still wrong. `acquire_lease`
already exposes the fix as a tool — but a tool the model has to remember to call, to thread the
resulting id through every later call, and to release afterwards. It will eventually not, and the
failure is silent: a plausible answer read off the wrong page.

So the feature takes the lease itself.

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4_1,
    systemPrompt = PageToolDocs.INSTRUCTIONS,
    toolRegistry = ToolRegistry { vitreWebView(driver, includeLeaseTools = false) },
) {
    install(VitrePageLease) {
        driver = pageDriver
        ttlMs = 120_000
    }
}
```

It acquires when the run starts, publishes the lease id — and the session it is on — as tool-call
metadata that every page tool picks up without the model seeing it, and releases when the run ends
however it ends, cancellation included. The agent never mentions a lease and never gets interleaved.

The bridged tools get it too: they have no metadata channel to the server, so the lease is written
into the call's `lease` argument on the way out. `release_lease` is left alone, because its `lease`
is required and names what the model wants back rather than what the run is holding.

Three things are worth knowing:

- **Pass `includeLeaseTools = false`.** With the feature installed the run already holds the page,
  and a model that then calls `acquire_lease` is queueing behind itself — a deadlock that resolves
  only when the feature's own lease expires.
- **A lease the model names still wins.** Koog merges caller-supplied metadata over
  feature-contributed metadata, so an explicit `lease` argument overrides the ambient one. That is
  the right precedence: naming a lease is a deliberate act. A call aimed at a *different* session
  drops the ambient lease rather than quoting it, since a claim on one WebView is refused for any
  other and the model has no argument with which to decline one it never asked for.
- **The TTL is not a formality.** The page is held for the whole run, including the seconds spent
  waiting on an LLM. On a WebView the user can also see and touch, that is a UI that stops
  responding to its own app, and the TTL is the bound on how long a stalled agent can do that. Leave
  it as short as the task allows. If it does expire mid-run the feature stops publishing it and the
  remaining calls go through unleased, rather than every one of them failing with "acquire a new
  one" — advice a model cannot take once `acquire_lease` has been taken out of its list.

`required = true` (the default) fails the run when the lease cannot be taken, on the grounds that a
feature installed to guarantee an uninterrupted page has not provided one. Set it false for a
best-effort hold.

## Tests

The host tests (`jvmTest`, `androidHostTest`, `iosSimulatorArm64Test`) drive the tools against a
fake controller that answers scripts from a lookup table. That is what makes them fast, and it is
also what makes them unable to prove the thing this module claims — so there is one test that runs
on a device:

```bash
mise run test:android          # :vitre-koog:connectedAndroidDeviceTest
```

`KoogAgentOnDeviceTest` builds a real `AIAgent` with `VitrePageLease` installed and a mock executor
scripted to snapshot, type, click and read, against a real Android `WebView`. The handles it acts on
are minted by the document, the click is a click, and the result is read back afterwards through a
second path into the same page so the test cannot pass on a tool that reported success without
touching anything. It also checks that a handle the document never issued fails loudly, and that a
held lease actually turns a second caller away.

Two things about its classpath are worth knowing, because both look like bugs when hit.
`kotlinx-coroutines-test` installs a main-dispatcher factory that displaces Android's, so it is
excluded from the device-test configuration — a WebView needs the platform main thread, which is the
whole point of the test. And `agents-core` declares a runtime edge to `ai.koog:serialization-jackson`,
whose `jackson-module-kotlin` D8 will not dex below minSdk 26; that one is excluded on the dependency
itself in `commonMain`, so the exclusion is published and a consuming app at minSdk 24 inherits it
rather than discovering it as a dex failure.

## Platforms

`agents-core` publishes Android, iOS and JVM artifacts, so this module targets all three and its
host tests run on all three. That is not true of Koog's own MCP integration, which is JVM-only because
the Kotlin MCP SDK is — which is why `vitre-koog` talks to `McpServer` directly through
`McpTransport` rather than through `ai.koog:agents-mcp`.

## What is not here

- **No prompt executor.** The module depends on `agents-core`, not the `koog-agents` umbrella, so it
  brings no LLM client. Pick your own and add the client that goes with it.
- **No sample.** The sample gallery's agent chat is deliberately mocked — no LLM API, no key — and
  wiring a real Koog agent into it would need both.
- **No live model, anywhere.** The on-device test below scripts the LLM with Koog's mock executor.
  What it is testing is the agent loop and the WebView under it; a real model's choice of which tool
  to call would make it a bill and a flake rather than a test.
