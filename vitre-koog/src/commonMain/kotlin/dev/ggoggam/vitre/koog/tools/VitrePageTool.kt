package dev.ggoggam.vitre.koog.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolException
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.PageDriverException
import dev.ggoggam.vitre.agent.PageTarget
import dev.ggoggam.vitre.agent.locatorFrom
import dev.ggoggam.vitre.agent.session.LeaseException
import dev.ggoggam.vitre.agent.session.NoSuchSessionException
import dev.ggoggam.vitre.core.workflow.Locator

/**
 * The [ToolCallMetadata] key the lease feature publishes the run's lease under.
 *
 * A plain string rather than a typed key because that is what the metadata bag is: an additive side
 * channel the framework merges into every call. Caller-supplied entries win over feature-contributed
 * ones, which is the precedence this wants — a model that names a lease explicitly has overridden
 * the ambient one on purpose.
 */
const val VITRE_LEASE_METADATA_KEY: String = "dev.ggoggam.vitre.koog.lease"

/**
 * The [ToolCallMetadata] key naming the session the ambient lease is held on.
 *
 * A lease is a claim on one WebView, and the registry refuses it for any other — so on a host with
 * more than one session, quoting the run's lease on a call the model aimed somewhere else turns
 * that call into "Lease `x` is held on session `a`, not `b`", for a lease the model never named and
 * has no argument to decline. This is what lets [ambientLease] leave it off.
 */
const val VITRE_LEASE_SESSION_METADATA_KEY: String = "dev.ggoggam.vitre.koog.lease.session"

/**
 * The lease a feature is holding for this run, if it applies to the session this call names.
 *
 * Null when there is none, or when the call is aimed at a different WebView than the one the claim
 * is on. A null [session] is the only registered one, which is necessarily the one being held.
 */
internal fun ambientLease(
    session: String?,
    metadata: ToolCallMetadata,
): String? {
    val id = metadata[VITRE_LEASE_METADATA_KEY] as? String ?: return null
    val on = metadata[VITRE_LEASE_SESSION_METADATA_KEY] as? String
    return if (session == null || on == null || session == on) id else null
}

/** Arguments every page tool accepts: which WebView, and under whose claim. */
interface PageToolArgs {
    val session: String?
    val lease: String?
}

/** Arguments for the tools that address one element. */
interface LocatorToolArgs : PageToolArgs {
    val ref: String?
    val css: String?
    val xpath: String?
}

/** @throws PageDriverException unless exactly one of the three is set. */
fun LocatorToolArgs.locator(): Locator = locatorFrom(ref = ref, css = css, xpath = xpath)

/**
 * A Koog tool over a Vitre WebView.
 *
 * Exists to do two things that every tool below would otherwise repeat, and one of them is easy to
 * get subtly wrong.
 *
 * The **failure mapping** is the easy one to state: a [PageDriverException] is a result the model
 * should read and correct — "Timeout waiting for css `#buy`", "give exactly one of ref, css, xpath"
 * — so it becomes a [ToolException], which Koog turns into a `ValidationError` result carrying the
 * message back to the LLM. Anything else propagates, because a controller that has been closed under
 * the agent is not something a model can retry its way out of.
 *
 * The **ambient lease** is the subtle one. A lease exists because single operations are ordered but
 * *sequences* are not, and an agent that has to remember to thread a lease id through every call
 * will sooner or later not. So the lease feature contributes the run's lease as call metadata and
 * this class picks it up, which means an agent under the feature gets an uninterrupted page without
 * naming a lease at all — while an agent that names one still wins, since caller-supplied metadata
 * takes precedence over a feature's.
 *
 * Extends [ToolBase] rather than [ai.koog.agents.core.tools.Tool] for exactly that reason: `Tool`'s
 * `execute` discards the metadata, and the ambient lease arrives in it.
 */
abstract class VitreTool<TArgs, TResult>(
    protected val driver: PageDriver,
    argsType: TypeToken,
    resultType: TypeToken,
    name: String,
    description: String,
) : ToolBase<TArgs, TResult>(argsType, resultType, name, description) {
    protected abstract suspend fun run(
        args: TArgs,
        metadata: ToolCallMetadata,
    ): TResult

    final override suspend fun execute(
        args: TArgs,
        metadata: ToolCallMetadata,
    ): TResult =
        try {
            run(args, metadata)
        } catch (failure: PageDriverException) {
            throw ToolException.ValidationFailure(failure.message)
        } catch (missing: NoSuchSessionException) {
            throw ToolException.ValidationFailure(missing.message ?: "No such session")
        } catch (lease: LeaseException) {
            throw ToolException.ValidationFailure(lease.message ?: "Lease unavailable")
        }
}

/** A [VitreTool] that acts on a page, with the session and lease already resolved. */
abstract class VitrePageTool<TArgs : PageToolArgs, TResult>(
    driver: PageDriver,
    argsType: TypeToken,
    resultType: TypeToken,
    name: String,
    description: String,
) : VitreTool<TArgs, TResult>(driver, argsType, resultType, name, description) {
    protected abstract suspend fun act(
        args: TArgs,
        target: PageTarget,
    ): TResult

    final override suspend fun run(
        args: TArgs,
        metadata: ToolCallMetadata,
    ): TResult =
        act(
            args,
            PageTarget(
                session = args.session,
                lease = args.lease ?: ambientLease(args.session, metadata),
            ),
        )
}

/**
 * A page tool whose result is the text the model should see.
 *
 * The result goes back verbatim rather than as a JSON string, for the same reason
 * [ai.koog.agents.core.tools.SimpleTool] does it: a page snapshot or an extracted value that arrives
 * quoted and backslash-escaped costs tokens to produce and tokens to read past, and reads as a
 * string literal rather than as what was on the page.
 */
abstract class VitrePageTextTool<TArgs : PageToolArgs>(
    driver: PageDriver,
    argsType: TypeToken,
    name: String,
    description: String,
) : VitrePageTool<TArgs, String>(driver, argsType, typeToken<String>(), name, description) {
    override fun encodeResultToString(
        result: String,
        serializer: JSONSerializer,
    ): String = result
}
