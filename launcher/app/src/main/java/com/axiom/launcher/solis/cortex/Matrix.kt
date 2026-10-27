package com.axiom.launcher.solis.cortex

import com.axiom.launcher.solis.llm.Effort
import org.json.JSONObject

enum class Tier(val label: String, val blurb: String) {
    T0("T0", "Deterministic action or quick lookup — no model"),
    T1("T1", "Routine single-agent work"),
    T2("T2", "Planning and specialist delegation"),
    T3("T3", "Difficult or sensitive — stronger reasoning, independent verification");

    fun up(): Tier = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

/** Relative importance of each signal when ranking eligible models (they need not sum to 1). */
data class Weights(val quality: Double, val cost: Double, val latency: Double, val reliability: Double)

data class TierPolicy(
    val tier: Tier,
    /** Floor on the model's quality estimate (0..1, percentile within the catalog). */
    val minQuality: Double,
    /** Hard cap on the estimated cost of one model call at this tier. */
    val maxCallCostUsd: Double,
    val expectedOutputTokens: Int,
    val effort: Effort?,
    val weights: Weights,
    val verify: Boolean,
    val maxRetries: Int,
    val maxEscalations: Int,
    val allowDelegation: Boolean,
    val maxParallel: Int,
)

enum class CoordinatorMode { ALWAYS, WHEN_UNCERTAIN, NEVER }

data class AssessmentPolicy(
    /** Local complexity score at or below which work is T1. */
    val t1Max: Double = 0.35,
    /** ... at or below which it is T2 (above = T3). */
    val t2Max: Double = 0.72,
    /** Local confidence below which the coordinator model is asked to assess (in WHEN_UNCERTAIN mode). */
    val uncertaintyBelow: Double = 0.7,
    val coordinatorMode: CoordinatorMode = CoordinatorMode.WHEN_UNCERTAIN,
)

data class LearningPolicy(
    val enabled: Boolean = true,
    /** Outcomes needed per (tier, model) before a learned adjustment is considered. */
    val minEvidence: Int = 12,
    /** z for Wilson lower bounds; 1.64 ≈ 90% one-sided. */
    val z: Double = 1.64,
    val maxBonus: Double = 0.15,
    /** Share of eligible tasks that try a candidate policy before it can be promoted. */
    val trialShare: Double = 0.25,
    /** A trial may be up to this much worse (success rate) than control and still be promoted when it saves cost. */
    val promoteMargin: Double = 0.03,
)

data class NarratorPolicy(
    val enabled: Boolean = true,
    val coalesceMs: Long = 4_000,
    val minIntervalMs: Long = 30_000,
    val maxCallsPerThread: Int = 4,
    /** Only use a model when a thread had at least this many steps; otherwise status text is deterministic. */
    val minStepsForModel: Int = 2,
    val maxCallCostUsd: Double = 0.002,
)

/** Solis's versioned, user-editable decision matrix. */
data class DecisionMatrix(
    val version: Int,
    val note: String,
    val tiers: Map<Tier, TierPolicy>,
    val assessment: AssessmentPolicy = AssessmentPolicy(),
    val learning: LearningPolicy = LearningPolicy(),
    val narrator: NarratorPolicy = NarratorPolicy(),
    /** Topics that always get T3 treatment (stronger reasoning plus verification). */
    val sensitiveDomains: List<String> = listOf("medical", "legal", "financial", "security"),
    val coordinatorMaxCallCostUsd: Double = 0.02,
) {
    fun policy(t: Tier): TierPolicy = tiers[t] ?: tiers.getValue(Tier.T1)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version); put("note", note)
        put("tiers", JSONObject().apply {
            tiers.values.sortedBy { it.tier }.forEach { p ->
                put(p.tier.name, JSONObject().apply {
                    put("minQuality", p.minQuality); put("maxCallCostUsd", p.maxCallCostUsd)
                    put("expectedOutputTokens", p.expectedOutputTokens); put("effort", p.effort?.wire ?: JSONObject.NULL)
                    put("weights", JSONObject().put("quality", p.weights.quality).put("cost", p.weights.cost)
                        .put("latency", p.weights.latency).put("reliability", p.weights.reliability))
                    put("verify", p.verify); put("maxRetries", p.maxRetries); put("maxEscalations", p.maxEscalations)
                    put("allowDelegation", p.allowDelegation); put("maxParallel", p.maxParallel)
                })
            }
        })
        put("assessment", JSONObject().put("t1Max", assessment.t1Max).put("t2Max", assessment.t2Max)
            .put("uncertaintyBelow", assessment.uncertaintyBelow).put("coordinatorMode", assessment.coordinatorMode.name))
        put("learning", JSONObject().put("enabled", learning.enabled).put("minEvidence", learning.minEvidence)
            .put("z", learning.z).put("maxBonus", learning.maxBonus).put("trialShare", learning.trialShare)
            .put("promoteMargin", learning.promoteMargin))
        put("narrator", JSONObject().put("enabled", narrator.enabled).put("coalesceMs", narrator.coalesceMs)
            .put("minIntervalMs", narrator.minIntervalMs).put("maxCallsPerThread", narrator.maxCallsPerThread)
            .put("minStepsForModel", narrator.minStepsForModel).put("maxCallCostUsd", narrator.maxCallCostUsd))
        put("sensitiveDomains", org.json.JSONArray(sensitiveDomains))
        put("coordinatorMaxCallCostUsd", coordinatorMaxCallCostUsd)
    }

    companion object {
        fun default(): DecisionMatrix = DecisionMatrix(
            version = 1,
            note = "Built-in defaults",
            tiers = mapOf(
                Tier.T0 to TierPolicy(Tier.T0, 0.0, 0.0, 0, null, Weights(0.0, 1.0, 0.0, 0.0), false, 0, 0, false, 1),
                Tier.T1 to TierPolicy(Tier.T1, 0.30, 0.02, 700, Effort.LOW, Weights(0.35, 0.35, 0.2, 0.1), false, 1, 1, false, 1),
                Tier.T2 to TierPolicy(Tier.T2, 0.55, 0.12, 1500, Effort.MEDIUM, Weights(0.45, 0.25, 0.15, 0.15), false, 2, 1, true, 3),
                Tier.T3 to TierPolicy(Tier.T3, 0.75, 0.50, 3000, Effort.HIGH, Weights(0.6, 0.1, 0.1, 0.2), true, 2, 1, true, 2),
            ),
        )

        /** Parses an edited matrix. Missing fields fall back to defaults so partial edits stay valid. */
        fun fromJson(o: JSONObject): DecisionMatrix {
            val d = default()
            val tiersJ = o.optJSONObject("tiers") ?: JSONObject()
            val tiers = Tier.entries.associateWith { t ->
                val base = d.policy(t)
                val j = tiersJ.optJSONObject(t.name) ?: return@associateWith base
                val w = j.optJSONObject("weights")
                TierPolicy(
                    tier = t,
                    minQuality = j.optDouble("minQuality", base.minQuality).coerceIn(0.0, 1.0),
                    maxCallCostUsd = j.optDouble("maxCallCostUsd", base.maxCallCostUsd).coerceAtLeast(0.0),
                    expectedOutputTokens = j.optInt("expectedOutputTokens", base.expectedOutputTokens).coerceIn(0, 64_000),
                    effort = if (j.has("effort") && j.isNull("effort")) null else Effort.parse(j.optString("effort")) ?: base.effort,
                    weights = Weights(
                        w?.optDouble("quality", base.weights.quality) ?: base.weights.quality,
                        w?.optDouble("cost", base.weights.cost) ?: base.weights.cost,
                        w?.optDouble("latency", base.weights.latency) ?: base.weights.latency,
                        w?.optDouble("reliability", base.weights.reliability) ?: base.weights.reliability,
                    ),
                    verify = j.optBoolean("verify", base.verify),
                    maxRetries = j.optInt("maxRetries", base.maxRetries).coerceIn(0, 5),
                    maxEscalations = j.optInt("maxEscalations", base.maxEscalations).coerceIn(0, 3),
                    allowDelegation = j.optBoolean("allowDelegation", base.allowDelegation),
                    maxParallel = j.optInt("maxParallel", base.maxParallel).coerceIn(1, 6),
                )
            }
            val a = o.optJSONObject("assessment")
            val l = o.optJSONObject("learning")
            val n = o.optJSONObject("narrator")
            return DecisionMatrix(
                version = o.optInt("version", d.version),
                note = o.optString("note", ""),
                tiers = tiers,
                assessment = AssessmentPolicy(
                    t1Max = a?.optDouble("t1Max", d.assessment.t1Max) ?: d.assessment.t1Max,
                    t2Max = a?.optDouble("t2Max", d.assessment.t2Max) ?: d.assessment.t2Max,
                    uncertaintyBelow = a?.optDouble("uncertaintyBelow", d.assessment.uncertaintyBelow) ?: d.assessment.uncertaintyBelow,
                    coordinatorMode = runCatching { CoordinatorMode.valueOf(a?.optString("coordinatorMode") ?: "") }.getOrDefault(d.assessment.coordinatorMode),
                ),
                learning = LearningPolicy(
                    enabled = l?.optBoolean("enabled", true) ?: true,
                    minEvidence = (l?.optInt("minEvidence", d.learning.minEvidence) ?: d.learning.minEvidence).coerceAtLeast(3),
                    z = l?.optDouble("z", d.learning.z) ?: d.learning.z,
                    maxBonus = (l?.optDouble("maxBonus", d.learning.maxBonus) ?: d.learning.maxBonus).coerceIn(0.0, 0.5),
                    trialShare = (l?.optDouble("trialShare", d.learning.trialShare) ?: d.learning.trialShare).coerceIn(0.0, 1.0),
                    promoteMargin = l?.optDouble("promoteMargin", d.learning.promoteMargin) ?: d.learning.promoteMargin,
                ),
                narrator = NarratorPolicy(
                    enabled = n?.optBoolean("enabled", true) ?: true,
                    coalesceMs = n?.optLong("coalesceMs", d.narrator.coalesceMs) ?: d.narrator.coalesceMs,
                    minIntervalMs = n?.optLong("minIntervalMs", d.narrator.minIntervalMs) ?: d.narrator.minIntervalMs,
                    maxCallsPerThread = n?.optInt("maxCallsPerThread", d.narrator.maxCallsPerThread) ?: d.narrator.maxCallsPerThread,
                    minStepsForModel = n?.optInt("minStepsForModel", d.narrator.minStepsForModel) ?: d.narrator.minStepsForModel,
                    maxCallCostUsd = n?.optDouble("maxCallCostUsd", d.narrator.maxCallCostUsd) ?: d.narrator.maxCallCostUsd,
                ),
                sensitiveDomains = o.optJSONArray("sensitiveDomains")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: d.sensitiveDomains,
                coordinatorMaxCallCostUsd = o.optDouble("coordinatorMaxCallCostUsd", d.coordinatorMaxCallCostUsd),
            )
        }
    }
}
