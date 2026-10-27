package com.axiom.launcher.solis.cortex

import com.axiom.launcher.solis.catalog.ModelInfo
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ModelRef
import java.util.Locale
import kotlin.math.abs

/** Which job a model call does. Every role's spend counts toward the task's total cost. */
enum class CallRole { COORDINATOR, WORKER, VERIFIER, COMBINER, NARRATOR }

/** One observed model call, used for stats and adaptive routing. */
data class Outcome(
    val id: String,
    val ts: Long,
    val threadId: String,
    val role: CallRole,
    val tier: Tier,
    val model: ModelRef,
    /** The call completed without a provider error. */
    val ok: Boolean,
    /** Task-level result: verified/accepted (true), rejected (false), or not judged (null). */
    val success: Boolean?,
    val latencyMs: Long,
    val costUsd: Double,
    val inputTokens: Int,
    val outputTokens: Int,
    val errorKind: String? = null,
    val policyVersion: Int = 0,
    val trial: Boolean = false,
)

/** Rolling per-model statistics derived from outcomes. */
class StatsBook {
    class ModelStats {
        var calls = 0; var ok = 0
        var latencyEwma = -1.0
        val judged = IntArray(Tier.entries.size)
        val successes = IntArray(Tier.entries.size)
        var costSum = 0.0
    }

    private val byModel = HashMap<String, ModelStats>()

    @Synchronized
    fun record(o: Outcome) {
        val s = byModel.getOrPut(o.model.key) { ModelStats() }
        s.calls++
        if (o.ok) {
            s.ok++
            s.latencyEwma = if (s.latencyEwma < 0) o.latencyMs.toDouble() else s.latencyEwma * 0.8 + o.latencyMs * 0.2
        }
        s.costSum += o.costUsd
        if (o.success != null && o.role != CallRole.NARRATOR) {
            s.judged[o.tier.ordinal]++
            if (o.success) s.successes[o.tier.ordinal]++
        }
    }

    @Synchronized
    fun get(ref: ModelRef): ModelStats? = byModel[ref.key]

    @Synchronized
    fun clear() = byModel.clear()
}

/** Prior quality estimates (0..1) from catalog metadata: benchmark percentile, else price percentile. */
class QualityPriors(models: List<ModelInfo>) {
    private val prior = HashMap<String, Double>()

    init {
        val pool = models.filter { !it.isBatch && !it.isMeta }
        val bench = pool.mapNotNull { m -> m.qualityIndex?.let { m.ref.key to it } }.sortedBy { it.second }
        bench.forEachIndexed { i, (k, _) -> prior[k] = if (bench.size == 1) 0.5 else i.toDouble() / (bench.size - 1) }
        val priced = pool.filter { it.ref.key !in prior && it.outputPrice != null && it.outputPrice > 0 }
            .sortedBy { it.outputPrice }
        // Price is a weak proxy for capability, so it is shrunk toward the middle.
        priced.forEachIndexed { i, m -> prior[m.ref.key] = 0.15 + 0.7 * (if (priced.size == 1) 0.5 else i.toDouble() / (priced.size - 1)) }
    }

    fun of(m: ModelInfo): Double = prior[m.ref.key] ?: m.priorQuality ?: when {
        m.isLocal -> 0.35
        m.isFree -> 0.3
        else -> 0.4
    }

    fun source(m: ModelInfo): String = when {
        m.qualityIndex != null -> "benchmark"
        prior.containsKey(m.ref.key) -> "price proxy"
        m.priorQuality != null -> "on-device estimate"
        else -> "default"
    }
}

data class RouteNeeds(
    val tools: Boolean = false,
    val vision: Boolean = false,
    val documents: Boolean = false,
    val minContext: Int = 0,
    /** Context needed by a model that only gets the request's relevant tools ([ModelInfo.compactTools]); 0 = same as [minContext]. */
    val leanMinContext: Int = 0,
)

/** Hard privacy limits. Never relaxed by learning or user overrides. */
data class PrivacyConstraint(
    val cloudAllowed: Boolean = true,
    val zdr: Boolean = false,
    val denyDataCollection: Boolean = true,
)

data class RouteQuery(
    val role: CallRole,
    val tier: Tier,
    val needs: RouteNeeds,
    val estInputTokens: Int,
    val estOutputTokens: Int,
    val privacy: PrivacyConstraint,
    /** Spending left for this call (task, thread, mode, day and month caps combined). Null = no cap. */
    val budgetRemainingUsd: Double?,
    val exclude: Set<ModelRef> = emptySet(),
    /** Prefer a different vendor family (independent verification). */
    val avoidFamily: String? = null,
    /** User override for this role, honored only when it passes every hard constraint. */
    val pinned: ModelRef? = null,
    /** Stable key (thread id) for deterministic trial bucketing. */
    val trialKey: String? = null,
    val allowUnknownPrice: Boolean = false,
    /** Use a model from any other vendor family when one is eligible (second opinions); else fall back with a note. */
    val excludeFamily: String? = null,
    /** The user wants this call on the phone's own model when one is eligible (quality floor aside); cloud stays the fallback. */
    val preferLocal: Boolean = false,
    /** Use the phone's own model only when no other model is eligible (the user keeps it for when it's needed). */
    val holdBackOnDevice: Boolean = false,
)

data class RouteDecision(
    val model: ModelInfo,
    val fallbacks: List<ModelInfo>,
    val effort: Effort?,
    val estCostUsd: Double?,
    val explanation: List<String>,
    val policyVersion: Int,
    val trial: Boolean,
    val score: Double,
)

class NoEligibleModel(message: String, val reasons: Map<String, Int>) : Exception(message)

/**
 * Picks a model for one call. Order matters: hard filters (provider, capability, privacy, spending,
 * expiry, exclusions) run first; only the survivors are ranked, and learned bonuses only adjust ranking.
 */
class Router(
    private val stats: StatsBook,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun route(
        q: RouteQuery,
        models: List<ModelInfo>,
        usableProviders: Set<String>,
        policy: PolicySnapshot,
        priors: QualityPriors,
    ): RouteDecision {
        val matrix = policy.matrix
        val tierPolicy = matrix.policy(q.tier)
        val now = clock()
        val excluded = LinkedHashMap<String, Int>()
        fun reject(reason: String): Boolean { excluded[reason] = (excluded[reason] ?: 0) + 1; return false }

        val capUsd = minOf(
            when (q.role) {
                CallRole.COORDINATOR -> matrix.coordinatorMaxCallCostUsd
                CallRole.NARRATOR -> matrix.narrator.maxCallCostUsd
                else -> tierPolicy.maxCallCostUsd
            },
            q.budgetRemainingUsd ?: Double.MAX_VALUE,
        )

        fun hardOk(m: ModelInfo, pinned: Boolean): Boolean = when {
            m.provider !in usableProviders -> reject("provider not set up")
            m.isBatch -> reject("batch-only")
            m.isMeta && !pinned -> reject("router meta-model")
            m.expiresAt != null && m.expiresAt < now + 3 * 86_400_000L -> reject("retiring")
            q.needs.tools && !m.supportsTools -> reject("no tool calling")
            q.needs.vision && !m.supportsImages() -> reject("no image input")
            q.needs.documents && !m.supportsFiles() && !m.supportsImages() -> reject("no document input")
            (if (m.compactTools && q.needs.leanMinContext > 0) q.needs.leanMinContext else q.needs.minContext) > m.contextLength -> reject("context too small")
            !q.privacy.cloudAllowed && !m.isLocal -> reject("cloud not allowed")
            m.ref in q.exclude -> reject("failed earlier")
            else -> {
                val est = m.estimateCost(q.estInputTokens, q.estOutputTokens)
                when {
                    est == null && !q.allowUnknownPrice && !m.isLocal -> reject("unknown price")
                    est != null && est > capUsd -> reject("over budget/tier cap")
                    else -> true
                }
            }
        }

        val explanation = ArrayList<String>()
        val trial = policy.trial?.takeIf { matrix.learning.enabled && q.trialKey != null && inTrial(q.trialKey, matrix.learning.trialShare) }
        val bonuses = trial?.bonuses ?: policy.active.bonuses

        // User override: honored only if it clears every hard constraint.
        q.pinned?.let { ref ->
            val m = models.firstOrNull { it.ref == ref }
            if (m != null && hardOk(m, pinned = true) && (q.excludeFamily == null || m.family != q.excludeFamily)) {
                val est = m.estimateCost(q.estInputTokens, q.estOutputTokens)
                val fb = ranked(models.filter { it.ref != ref && hardOk(it, false) }, q, tierPolicy, priors, bonuses).take(2).map { it.model }
                return RouteDecision(m, fb, effortFor(q, tierPolicy, m), est,
                    listOf("${q.role.name.lowercase()} · ${q.tier.label} · ${m.name} (your choice)", "est ${usd(est)}"),
                    policy.active.version, false, 1.0)
            }
            explanation += "Your ${q.role.name.lowercase()} choice ${m?.name ?: ref.model} was skipped: " +
                (if (m == null) "not in the catalog" else excluded.keys.lastOrNull() ?: "not eligible")
        }

        var eligible = models.filter { hardOk(it, false) }
        if (eligible.isEmpty()) {
            val why = excluded.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.value} ${it.key}" }
            throw NoEligibleModel("No model fits this ${q.role.name.lowercase()} call (${q.tier.label}): $why", excluded)
        }
        q.excludeFamily?.let { fam ->
            val others = eligible.filter { it.family != fam }
            if (others.isNotEmpty()) { eligible = others; explanation += "independent of $fam: a model from another maker" }
            else explanation += "no eligible model outside $fam, so this one isn't independent"
        }
        var localChoice = false
        if (q.preferLocal) {
            val locals = eligible.filter { it.isLocal }
            if (locals.isNotEmpty()) { eligible = locals; localChoice = true; explanation += "on this phone, as you asked (cloud only as a fallback)" }
        } else if (q.holdBackOnDevice) {
            val others = eligible.filter { !it.onDevice }
            if (others.isNotEmpty() && others.size < eligible.size) eligible = others
            else if (others.isEmpty()) explanation += "on this phone: no other model can take this now"
        }
        val floor = if (q.role == CallRole.NARRATOR || localChoice) 0.0 else tierPolicy.minQuality
        var pool = eligible.filter { quality(it, q.tier, priors) >= floor }
        if (pool.isEmpty()) {
            pool = eligible
            explanation += "No eligible model met the ${q.tier.label} quality floor ${"%.2f".format(Locale.US, floor)}; using the best available"
        }
        val ranked = ranked(pool, q, tierPolicy, priors, bonuses)
        val best = ranked.first()
        val m = best.model
        // Fallbacks: next best, preferring other vendor families so one outage doesn't take out all options.
        val rest = ranked.drop(1)
        val fallbacks = (rest.filter { it.model.family != m.family } + rest.filter { it.model.family == m.family }).take(2).map { it.model }

        val st = stats.get(m.ref)
        val judged = st?.judged?.get(q.tier.ordinal) ?: 0
        explanation.add(0, "${q.role.name.lowercase()} · ${q.tier.label} · ${m.name}")
        explanation += buildString {
            append("quality ${f2(best.quality)} (${priors.source(m)}")
            if (judged > 0) append(", ${st!!.successes[q.tier.ordinal]}/$judged successes")
            append(") · est ${usd(best.cost)}")
            if (st != null && st.latencyEwma > 0) append(" · ~${"%.1f".format(Locale.US, st.latencyEwma / 1000)}s")
        }
        if (abs(best.bonus) > 1e-6) explanation += "learned ${if (best.bonus > 0) "+" else ""}${f2(best.bonus)} (policy v${trial?.version ?: policy.active.version}${if (trial != null) " trial" else ""})"
        if (excluded.isNotEmpty()) explanation += "ruled out: " + excluded.entries.sortedByDescending { it.value }.take(4).joinToString(" · ") { "${it.value} ${it.key}" }
        if (fallbacks.isNotEmpty()) explanation += "fallbacks: " + fallbacks.joinToString { it.name }

        return RouteDecision(m, fallbacks, effortFor(q, tierPolicy, m), best.cost, explanation,
            trial?.version ?: policy.active.version, trial != null, best.score)
    }

    private class Scored(val model: ModelInfo, val score: Double, val quality: Double, val cost: Double?, val bonus: Double)

    private fun ranked(pool: List<ModelInfo>, q: RouteQuery, tp: TierPolicy, priors: QualityPriors, bonuses: Map<String, Double>): List<Scored> {
        val w = when (q.role) {
            CallRole.NARRATOR -> Weights(0.15, 0.6, 0.2, 0.05)
            CallRole.COORDINATOR -> Weights(0.55, 0.2, 0.15, 0.1)
            else -> tp.weights
        }
        val cap = tp.maxCallCostUsd.takeIf { it > 0 } ?: 0.01
        return pool.map { m ->
            val quality = quality(m, q.tier, priors)
            val est = m.estimateCost(q.estInputTokens, q.estOutputTokens)
            val costN = ((est ?: (cap * 0.5)) / cap).coerceIn(0.0, 1.5)
            val st = stats.get(m.ref)
            val lat = st?.latencyEwma?.takeIf { it > 0 }?.let { (it / 20_000.0).coerceAtMost(1.0) } ?: 0.3
            val rel = ((st?.ok ?: 0) + 9.5) / ((st?.calls ?: 0) + 10.0)
            val bonus = if (q.role == CallRole.NARRATOR) 0.0 else bonuses[bonusKey(q.tier, m.ref)] ?: 0.0
            val familyPenalty = if (q.avoidFamily != null && m.family == q.avoidFamily) 0.25 else 0.0
            val score = w.quality * quality + w.reliability * rel - w.cost * costN - w.latency * lat + bonus - familyPenalty
            Scored(m, score, quality, est, bonus)
        }.sortedByDescending { it.score }
    }

    /** Prior blended with measured success at this tier (Bayesian, prior weight 6). */
    fun quality(m: ModelInfo, tier: Tier, priors: QualityPriors): Double {
        val p = priors.of(m)
        val st = stats.get(m.ref) ?: return p
        val n = st.judged[tier.ordinal]
        if (n == 0) return p
        return (p * 6 + st.successes[tier.ordinal]) / (6 + n)
    }

    private fun effortFor(q: RouteQuery, tp: TierPolicy, m: ModelInfo): Effort? {
        val desired = when (q.role) {
            CallRole.NARRATOR -> Effort.MINIMAL
            CallRole.COORDINATOR -> Effort.LOW
            CallRole.VERIFIER -> tp.effort ?: Effort.MEDIUM
            else -> tp.effort
        }
        return m.effortFor(desired)
    }

    companion object {
        fun bonusKey(tier: Tier, ref: ModelRef) = "${tier.name}|${ref.key}"

        /** Deterministic bucketing so a thread consistently uses (or doesn't use) the trial policy. */
        fun inTrial(key: String, share: Double): Boolean {
            if (share <= 0) return false
            val h = (key.hashCode().toLong() and 0x7fffffff) % 1000
            return h < (share * 1000).toLong()
        }

        private fun f2(d: Double) = "%.2f".format(Locale.US, d)
        fun usd(d: Double?): String = when {
            d == null -> "unknown cost"
            d == 0.0 -> "free"
            d < 0.0001 -> "<$0.0001"
            d < 0.01 -> "$" + "%.4f".format(Locale.US, d)
            else -> "$" + "%.3f".format(Locale.US, d)
        }
    }
}
