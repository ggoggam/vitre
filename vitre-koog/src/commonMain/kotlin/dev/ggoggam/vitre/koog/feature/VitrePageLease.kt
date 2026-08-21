package dev.ggoggam.vitre.koog.feature

import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentFunctionalFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.AIAgentPlannerFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentFunctionalPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline
import ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline
import dev.ggoggam.vitre.agent.LeaseGrant
import dev.ggoggam.vitre.agent.PageDriver
import dev.ggoggam.vitre.agent.session.DEFAULT_LEASE_TTL_MS
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_METADATA_KEY
import dev.ggoggam.vitre.koog.tools.VITRE_LEASE_SESSION_METADATA_KEY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the WebView for the length of an agent run.
 *
 * ## What it is for
 *
 * Vitre orders every operation on a WebView against every other, so no two callers can corrupt each
 * other's individual step. What ordering cannot do is make a *sequence* indivisible, and an agent is
 * nothing but sequences:
 *
 * ```
 * agent:  wait_for(".price")        the app's UI:  user taps "next page"
 * agent:  extract(".price")   ←  reads the price on the page the user just opened
 * ```
 *
 * Every one of those operations was properly serialised and the answer is still wrong. The lease is
 * the fix, and `acquire_lease` already exposes it as a tool — but a tool the model has to remember
 * to call, and to thread the resulting id through every later call, and to release afterwards. It
 * will eventually not, and the failure is silent: a plausible answer read off the wrong page.
 *
 * So this feature takes the lease itself. It acquires when the run starts, publishes the id — and the
 * session it is on — as tool call metadata that [dev.ggoggam.vitre.koog.tools.VitrePageTool] picks up
 * without the model seeing it, and releases when the run ends however it ends, cancellation included.
 * The agent never mentions a lease and never gets interleaved.
 *
 * ```kotlin
 * val agent = AIAgent(
 *     promptExecutor = executor,
 *     llmModel = model,
 *     systemPrompt = PageToolDocs.INSTRUCTIONS,
 *     toolRegistry = ToolRegistry { vitreWebView(pageDriver, includeLeaseTools = false) },
 * ) {
 *     install(VitrePageLease) {
 *         driver = pageDriver
 *         ttlMs = 120_000
 *     }
 * }
 * ```
 *
 * ## What it costs
 *
 * The page is held for the whole run, including the seconds the agent spends waiting on an LLM. On
 * a WebView the user can also see and touch, that is a UI that stops responding to its own app, so
 * the TTL is not a formality: it is the bound on how long a stalled agent can do that. Leave it as
 * short as the task allows.
 *
 * Pair it with `vitreWebViewTools(driver, includeLeaseTools = false)`. With the feature installed
 * the run already holds the lock, and a model that then calls `acquire_lease` is queueing behind
 * itself — a deadlock that resolves only when the feature's own lease expires.
 */
class VitrePageLease internal constructor(
    private val driver: PageDriver,
) {
    /** Run id to the lease held for it. A single agent object can be running more than one. */
    private val held = MutableStateFlow<Map<String, LeaseGrant>>(emptyMap())

    /** The lease id held for [runId], for a host that wants to show it. */
    fun leaseFor(runId: String): String? = grantFor(runId)?.id

    /**
     * The live grant for [runId], or null once it has expired.
     *
     * A lease expires on its own — that is what the TTL is for — and the run carries on. If the id
     * kept being published after that, every remaining tool call would quote a lease the registry
     * has already dropped and be refused with "Lease is not active. Acquire a new one" — advice the
     * model cannot take, because with the feature installed `acquire_lease` is not in its list. So
     * an expired grant is forgotten instead, and the tools fall back to unleased calls: the
     * atomicity is gone either way, and only one of the two answers is also a dead agent.
     */
    internal fun grantFor(runId: String): LeaseGrant? {
        val grant = held.value[runId] ?: return null
        if (driver.isLeaseActive(grant.id)) return grant
        held.update { if (it[runId] === grant) it - runId else it }
        return null
    }

    internal suspend fun acquire(
        runId: String,
        config: Config,
    ) {
        val grant = driver.acquireLease(config.session, config.ttlMs)
        held.update { it + (runId to grant) }
    }

    internal fun release(runId: String) {
        // Removed before releasing, so a tool call racing the end of the run cannot pick up an id
        // whose claim is already on its way out.
        val grant = held.value[runId] ?: return
        held.update { it - runId }
        driver.releaseLease(grant.id)
    }

    /**
     * Gives back every lease this feature is still holding.
     *
     * The per-run hooks cover a run that finished or threw. They do not cover one that was
     * *cancelled* — a user tapping stop, a host scope torn down — because Koog rethrows a
     * cancellation without completing or failing the run. Without this, that leaves the user's
     * WebView exclusively held until the TTL runs out, which on the two-minute TTL the docs suggest
     * is two minutes of an app that does not respond to its own taps.
     */
    internal fun releaseAll() {
        val outstanding = held.value
        held.update { emptyMap() }
        outstanding.values.forEach { driver.releaseLease(it.id) }
    }

    /** Configuration for [VitrePageLease]. */
    class Config : FeatureConfig() {
        /**
         * The driver whose sessions to hold. Required — there is no sensible default, because the
         * WebViews belong to the host application and this feature never creates one.
         */
        var driver: PageDriver? = null

        /** Which session to hold. Null holds the only one, and fails if there is more than one. */
        var session: String? = null

        /**
         * How long the lease lives before it expires on its own, clamped by the driver.
         *
         * The default is Vitre's, which is tuned for a single tool call rather than a whole agent
         * run. Most runs want more; every run wants a bound, because this is what stops an agent
         * that has stalled on an LLM from holding the user's WebView indefinitely.
         */
        var ttlMs: Long = DEFAULT_LEASE_TTL_MS

        /**
         * Whether a run may proceed when the lease could not be taken.
         *
         * True — the default — fails the run, on the grounds that a feature installed to guarantee
         * an uninterrupted page has not provided one, and an agent that carries on without knowing
         * that produces exactly the quietly-wrong answers the lease exists to prevent. Set it false
         * for a best-effort hold where a contended page is better driven interleaved than not at all.
         */
        var required: Boolean = true
    }

    /** Installs [VitrePageLease] into an agent. */
    companion object Feature :
        AIAgentGraphFeature<Config, VitrePageLease>,
        AIAgentFunctionalFeature<Config, VitrePageLease>,
        AIAgentPlannerFeature<Config, VitrePageLease> {
        override val key: AIAgentStorageKey<VitrePageLease> = createStorageKey("vitre-page-lease")

        override fun createInitialConfig(agentConfig: ai.koog.agents.core.agent.config.AIAgentConfig): Config = Config()

        override fun install(
            config: Config,
            pipeline: AIAgentGraphPipeline,
        ): VitrePageLease = install(config, pipeline as AIAgentPipeline)

        override fun install(
            config: Config,
            pipeline: AIAgentFunctionalPipeline,
        ): VitrePageLease = install(config, pipeline as AIAgentPipeline)

        override fun install(
            config: Config,
            pipeline: AIAgentPlannerPipeline,
        ): VitrePageLease = install(config, pipeline as AIAgentPipeline)

        /**
         * One implementation for all three pipeline kinds.
         *
         * Everything this feature hooks — the run starting, the run ending, the metadata every tool
         * call carries — is on the pipeline they share. A graph agent's nodes and a planner's steps
         * are not distinctions a page lease has any opinion about.
         */
        private fun install(
            config: Config,
            pipeline: AIAgentPipeline,
        ): VitrePageLease {
            val driver =
                requireNotNull(config.driver) {
                    "VitrePageLease needs a `driver`: install(VitrePageLease) { driver = … }. The " +
                        "WebViews belong to your application, so the feature cannot make one up."
                }
            val feature = VitrePageLease(driver)

            pipeline.interceptAgentStarting(this) { event ->
                try {
                    feature.acquire(event.runId, config)
                } catch (cancellation: CancellationException) {
                    // Never swallowed, whatever `required` says: a cancelled run is not a run that
                    // could not get a lease, and returning normally here would let the agent start
                    // inside a job that is already going away.
                    throw cancellation
                } catch (failure: Throwable) {
                    // Rethrown as itself when the lease is required: the message the lease registry
                    // produces already says whether this was contention or a WebView that went away,
                    // and both are things the host — not the model — has to act on.
                    if (config.required) throw failure
                }
            }

            pipeline.provideToolCallMetadata(this) { event ->
                feature
                    .grantFor(event.runId)
                    ?.let {
                        mapOf(
                            VITRE_LEASE_METADATA_KEY to it.id,
                            // Which WebView the claim is on. A call naming a *different* session must
                            // not quote it: the registry checks the pair, so an ambient lease pinned
                            // to session A would fail every call the model makes against session B —
                            // for a lease the model never asked for and cannot drop.
                            VITRE_LEASE_SESSION_METADATA_KEY to it.sessionId,
                        )
                    }
                    ?: emptyMap()
            }

            // Both endings, because only one of them fires per run and a lease that survives its
            // run is the failure mode this feature would otherwise introduce. Releasing an id that
            // is already gone is a no-op, so the overlap is safe.
            pipeline.interceptAgentCompleted(this) { event -> feature.release(event.runId) }
            pipeline.interceptAgentExecutionFailed(this) { event -> feature.release(event.runId) }
            // And the backstop for the ending neither of those sees: a cancelled run, which Koog
            // rethrows without completing or failing.
            pipeline.interceptAgentClosing(this) { feature.releaseAll() }

            return feature
        }
    }
}
