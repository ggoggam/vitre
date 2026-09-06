# Host authorization for agent actions

`PageAccessPolicy` lets the host disable operations or approve each requested action before it runs.
Pass it to `PageDriver` or `McpServer`; MCP and Koog enforce the same policy when they share a driver.
The default enables all operations and allows every request, preserving existing behavior.

```kotlin
val policy = PageAccessPolicy(
    enabledActions = PageActionKind.entries.toSet() - PageActionKind.Evaluate,
    authorizer = PageActionAuthorizer { request ->
        when {
            request.sessionId != "shopping" -> PageActionDecision.Deny("Use the shopping session")
            request.kind == PageActionKind.Click -> {
                // A suspending host UI function. Render the requested locator/action and resume
                // with the user's decision; tool arguments cannot supply this approval.
                if (askUserToApprove(request)) PageActionDecision.Allow
                else PageActionDecision.Deny("The user declined this click")
            }
            else -> PageActionDecision.Allow
        }
    },
)
val server = McpServer(sessions, scope, accessPolicy = policy)
val koogTools = vitreWebViewTools(server.driver)
```

`PageActionRequest` carries the session ID, exact controller, lease ID, operation kind, and typed
`WorkflowStep`. Convenience properties expose its locator and requested navigation URL. Input text,
JavaScript, extraction fields and bridge payloads are available in the step; those can contain
secrets, so approval screens and audit logs should select which arguments they display. Lease
acquisition carries its bounded TTL instead of a step.

Checks cover snapshots, navigation, clicks, input, waits, extraction, arbitrary evaluation, bridge
messages and lease acquisition. A click's implicit wait and navigation's internal title read belong
to the approved action; disabling arbitrary `evaluate` still permits those internal operations.
Session listing, capability discovery, lease status and lease release remain available for inspection
and cleanup. Their metadata does not read the page.

The authorizer runs before taking a per-call WebView lock or entering an existing lease's use gate.
Suspending for approval therefore lets the host keep using the page. An already-held lease still
owns its WebView until release/expiry. Cancellation propagates; denial and authorizer failures become
`PageDriverException`, MCP tool errors, or Koog validation failures. Each call needs its own decision.

The request is bound to the controller execution will use. A lease can still pin an older controller
after a session has been rebuilt, and the authorizer sees that controller. Registration is checked
after approval and again when a lease or a multi-step sequence acquires execution ownership. A change
at those boundaries fails the call and asks for a fresh review. All execution remains pinned to the
approved controller; it never switches to a replacement.

Standalone waits and arbitrary evaluations use the controller's normal operation ordering without
holding an extra lock across completion. Another caller can send the message, mutate the page, or
resolve the promise they need. A registration change while such an operation is already queued does
not revoke it: it can finish on the originally approved controller. Callers requiring uninterrupted
sequences should use an explicit lease, which intentionally keeps ownership while waiting.

## Capability discovery

Both adapters expose `capabilities(session?, lease?)`, and native hosts can call
`driver.capabilities(target)`. It reports statically enabled operations, per-action authorization,
and the current limitations. Disabled operations disappear from the default tool list and are still
rejected if a client calls them directly. Koog capability replies respect `includeLeaseTools`.

Screenshot and cookie-management tools are reported as unavailable because PageDriver does not
expose them. `nativeCookieStore` separately says whether native host code has a controller cookie
store. A raw controller may support screenshots; discovery does not attempt a capture or promise
support on custom controllers. Static availability never promises that the host will approve a
particular action.

## Scope of enforcement

This controls calls through PageDriver, not all behavior of the browser. Raw controller calls,
direct WorkflowEngine and SessionLeases usage, native redirects, page scripts, and network requests
are outside this hook. Checking `requestedUrl` can restrict explicit navigation requests but does
not implement an origin sandbox or block subsequent redirects. Disabling `evaluate` does not
prevent page JavaScript from running in response to allowed clicks, navigation, or bridge messages.

A page can change while the user considers an action. Snapshot handles protect element identity;
they do not freeze a button's meaning or the state a click will submit. Hosts needing restrictions
on submitted data or final destinations must enforce them in their own page/native integration.
Use `SnapshotPolicy` independently to redact routine page snapshots.
