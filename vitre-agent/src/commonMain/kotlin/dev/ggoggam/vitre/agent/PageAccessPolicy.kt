package dev.ggoggam.vitre.agent

import dev.ggoggam.vitre.core.webview.WebViewController
import dev.ggoggam.vitre.core.workflow.Locator
import dev.ggoggam.vitre.core.workflow.Template
import dev.ggoggam.vitre.core.workflow.WorkflowStep

/** Operations that can be enabled by the host, independently of model-supplied arguments. */
enum class PageActionKind(
    val toolName: String,
) {
    Snapshot("snapshot"),
    Navigate("navigate"),
    Click("click"),
    Input("type"),
    WaitFor("wait_for"),
    Extract("extract"),
    ExtractRows("extract_rows"),
    Evaluate("evaluate"),
    PostMessage("send_message"),
    AwaitMessage("await_message"),
    AcquireLease("acquire_lease"),
}

/**
 * The exact proposed action. [step] contains its typed locator, URL/text template, extraction
 * source, JavaScript, or message as appropriate. A click/input describes the requested operation,
 * not its implicit wait; navigation describes the URL, not its internal title read.
 *
 * These arguments may contain secrets. Hosts should display/store only what their approval UI needs.
 */
data class PageActionRequest(
    val sessionId: String,
    /** The exact controller that execution will use, including when an existing lease pins it. */
    val controller: WebViewController,
    val leaseId: String?,
    val kind: PageActionKind,
    val step: WorkflowStep? = null,
    /** Only set for [PageActionKind.AcquireLease], after applying the driver's TTL bounds. */
    val leaseTtlMs: Long? = null,
) {
    val requestedUrl: String? get() = ((step as? WorkflowStep.Navigate)?.url as? Template.Literal)?.value
    val locator: Locator? get() =
        when (val action = step) {
            is WorkflowStep.Click -> action.locator
            is WorkflowStep.Input -> action.locator
            is WorkflowStep.WaitFor -> action.locator
            is WorkflowStep.Extract -> action.locator
            is WorkflowStep.ExtractRows -> action.rows
            else -> null
        }
}

sealed interface PageActionDecision {
    data object Allow : PageActionDecision

    data class Deny(
        val reason: String,
    ) : PageActionDecision
}

/** May suspend while the host asks its user for approval. Cancellation must remain cooperative. */
fun interface PageActionAuthorizer {
    suspend fun authorize(request: PageActionRequest): PageActionDecision
}

/**
 * Host-only authorization for [PageDriver] and adapters sharing it. Disabled actions are omitted
 * from adapter tool lists and rejected by the driver even if invoked directly. Authorization runs
 * before acquiring a per-call WebView lock or a lease's use gate.
 *
 * This is an action boundary, not a browser sandbox: native redirects, page scripts, raw controllers
 * and direct WorkflowEngine/SessionLeases calls bypass it. It does not authorize current DOM state;
 * a page may change while approval is pending. Use snapshot handles for element identity.
 */
class PageAccessPolicy(
    enabledActions: Set<PageActionKind> = PageActionKind.entries.toSet(),
    val authorizer: PageActionAuthorizer = PageActionAuthorizer { PageActionDecision.Allow },
) {
    val enabledActions: Set<PageActionKind> = enabledActions.toSet()
}

/** Static tool availability for one resolved target; individual calls can still be denied. */
data class PageCapabilities(
    val sessionId: String,
    val operations: List<String>,
    val perActionAuthorization: Boolean = true,
    /** Screenshot and cookie management are not exposed by PageDriver or its current adapters. */
    val screenshot: Boolean = false,
    val cookies: Boolean = false,
    /** Whether the underlying controller offers a cookie store to native host code. */
    val nativeCookieStore: Boolean,
    val nativeRedirectFiltering: Boolean = false,
) {
    fun render(): String =
        "Session `$sessionId`: ${operations.joinToString(", ")}. " +
            "Individual actions require host authorization. " +
            "Screenshot and cookie-management tools are not exposed. " +
            "Native cookie store: $nativeCookieStore. Native redirect filtering: $nativeRedirectFiltering."
}
