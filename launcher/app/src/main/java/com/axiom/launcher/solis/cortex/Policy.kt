package com.axiom.launcher.solis.cortex

import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

enum class PolicyStatus { ACTIVE, TRIAL, RETIRED, ROLLED_BACK }
enum class PolicyOrigin { DEFAULT, USER_EDIT, LEARNED, ROLLBACK }

/**
 * One version of Solis's routing policy: the decision matrix plus learned per-(tier, model) ranking
 * bonuses. Bonuses can only reorder models that already passed every hard constraint.
 */
data class PolicyVersion(
    val version: Int,
    val matrix: DecisionMatrix,
    val bonuses: Map<String, Double>,
    val status: PolicyStatus,
    val origin: PolicyOrigin,
    val createdAt: Long,
    val note: String,
    val parent: Int? = null,
    val evaluation: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version); put("matrix", matrix.toJson()); put("bonuses", JSONObject(bonuses))
        put("status", status.name); put("origin", origin.name); put("createdAt", createdAt); put("note", note)
        put("parent", parent ?: JSONObject.NULL); put("evaluation", evaluation ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): PolicyVersion = PolicyVersion(
            version = o.getInt("version"),
            matrix = DecisionMatrix.fromJson(o.getJSONObject("matrix")),
            bonuses = o.optJSONObject("bonuses")?.let { b -> b.keys().asSequence().associateWith { b.getDouble(it) } } ?: emptyMap(),
            status = PolicyStatus.valueOf(o.getString("status")),
            origin = PolicyOrigin.valueOf(o.getString("origin")),
            createdAt = o.getLong("createdAt"),
            note = o.optString("note"),
            parent = if (o.isNull("parent")) null else o.optInt("parent"),
            evaluation = if (o.isNull("evaluation")) null else o.optString("evaluation"),
        )
    }
}

/** What the router needs: the active version and an optional trial candidate. */
data class PolicySnapshot(val active: PolicyVersion, val trial: PolicyVersion?) {
    val matrix: DecisionMatrix get() = active.matrix
}

interface PolicyPersistence {
    fun loadPolicies(): List<PolicyVersion>
    fun putPolicy(p: PolicyVersion)
}

/** Version history with explicit activation, rollback and trial bookkeeping. */
class PolicyBook(private val store: PolicyPersistence, private val clock: () -> Long = System::currentTimeMillis) {
    private val versions = sortedMapOf<Int, PolicyVersion>()

    @Synchronized
    fun load() {
        versions.clear()
        store.loadPolicies().forEach { versions[it.version] = it }
        if (versions.values.none { it.status == PolicyStatus.ACTIVE }) {
            save(PolicyVersion(nextVersion(), DecisionMatrix.default().copy(version = nextVersion()), emptyMap(),
                PolicyStatus.ACTIVE, PolicyOrigin.DEFAULT, clock(), "Built-in defaults"))
        }
    }

    @Synchronized
    fun snapshot(): PolicySnapshot = PolicySnapshot(active(), versions.values.lastOrNull { it.status == PolicyStatus.TRIAL })

    @Synchronized
    fun history(): List<PolicyVersion> = versions.values.sortedByDescending { it.version }

    @Synchronized
    fun active(): PolicyVersion = versions.values.last { it.status == PolicyStatus.ACTIVE }

    private fun nextVersion() = (versions.keys.maxOrNull() ?: 0) + 1

    private fun save(p: PolicyVersion) { versions[p.version] = p; store.putPolicy(p) }

    /** A user edit takes effect immediately (it is an explicit choice) and stays reversible. */
    @Synchronized
    fun applyUserEdit(matrix: DecisionMatrix, note: String): PolicyVersion {
        val cur = active()
        save(cur.copy(status = PolicyStatus.RETIRED))
        cancelTrial("superseded by your edit")
        val v = nextVersion()
        val p = PolicyVersion(v, matrix.copy(version = v, note = note), cur.bonuses, PolicyStatus.ACTIVE, PolicyOrigin.USER_EDIT, clock(), note, cur.version)
        save(p)
        return p
    }

    /** Reactivates an earlier version as a new version, so the history itself is never rewritten. */
    @Synchronized
    fun rollbackTo(version: Int): PolicyVersion {
        val target = versions[version] ?: throw IllegalArgumentException("No policy v$version")
        val cur = active()
        save(cur.copy(status = PolicyStatus.RETIRED))
        cancelTrial("rolled back to v$version")
        val v = nextVersion()
        val p = target.copy(version = v, matrix = target.matrix.copy(version = v), status = PolicyStatus.ACTIVE,
            origin = PolicyOrigin.ROLLBACK, createdAt = clock(), note = "Restored v$version", parent = cur.version, evaluation = null)
        save(p)
        return p
    }

    @Synchronized
    fun startTrial(bonuses: Map<String, Double>, note: String): PolicyVersion? {
        if (versions.values.any { it.status == PolicyStatus.TRIAL }) return null
        val cur = active()
        val v = nextVersion()
        val p = PolicyVersion(v, cur.matrix.copy(version = v), bonuses, PolicyStatus.TRIAL, PolicyOrigin.LEARNED, clock(), note, cur.version)
        save(p)
        return p
    }

    @Synchronized
    fun promoteTrial(evaluation: String): PolicyVersion? {
        val t = versions.values.lastOrNull { it.status == PolicyStatus.TRIAL } ?: return null
        save(active().copy(status = PolicyStatus.RETIRED))
        val p = t.copy(status = PolicyStatus.ACTIVE, evaluation = evaluation)
        save(p)
        return p
    }

    @Synchronized
    fun cancelTrial(evaluation: String): PolicyVersion? {
        val t = versions.values.lastOrNull { it.status == PolicyStatus.TRIAL } ?: return null
        val p = t.copy(status = PolicyStatus.ROLLED_BACK, evaluation = evaluation)
        save(p)
        return p
    }
}

/**
 * Learns ranking adjustments from observed outcomes, conservatively:
 *  - needs [LearningPolicy.minEvidence] judged outcomes per (tier, model),
 *  - uses Wilson lower bounds, so small samples don't swing routing,
 *  - proposes changes as a trial on a share of tasks, then promotes or rolls back by comparing to control.
 */
class PolicyLearner(private val book: PolicyBook) {

    data class Report(val proposed: PolicyVersion? = null, val promoted: PolicyVersion? = null, val rolledBack: PolicyVersion? = null, val note: String = "")

    fun evaluate(outcomes: List<Outcome>): Report {
        val snap = book.snapshot()
        val lp = snap.matrix.learning
        if (!lp.enabled) return Report(note = "learning disabled")
        val trial = snap.trial
        if (trial != null) return judgeTrial(trial, snap.active, outcomes.filter { it.ts >= trial.createdAt }, lp)

        // Propose: bonus = how much a model's lower-bound success beats (or trails) the tier's pooled rate.
        val judged = outcomes.filter { it.success != null && it.role != CallRole.NARRATOR }
        val bonuses = HashMap<String, Double>()
        for (tier in Tier.entries) {
            val atTier = judged.filter { it.tier == tier }
            if (atTier.size < lp.minEvidence) continue
            val pooled = atTier.count { it.success == true }.toDouble() / atTier.size
            atTier.groupBy { it.model }.forEach { (ref, list) ->
                if (list.size < lp.minEvidence) return@forEach
                val s = list.count { it.success == true }
                val lb = wilsonLower(s, list.size, lp.z)
                val ub = wilsonUpper(s, list.size, lp.z)
                val b = when {
                    lb > pooled -> (lb - pooled)          // reliably better than the pool
                    ub < pooled -> (ub - pooled)          // reliably worse
                    else -> 0.0
                }.coerceIn(-lp.maxBonus, lp.maxBonus)
                if (kotlin.math.abs(b) >= 0.02) bonuses[Router.bonusKey(tier, ref)] = round3(b)
            }
        }
        if (bonuses.isEmpty() || sameBonuses(bonuses, snap.active.bonuses)) return Report(note = "no change: not enough evidence or nothing to adjust")
        val p = book.startTrial(bonuses, "Learned from ${judged.size} judged outcomes")
        return Report(proposed = p, note = "trial started")
    }

    private fun judgeTrial(trial: PolicyVersion, active: PolicyVersion, since: List<Outcome>, lp: LearningPolicy): Report {
        val judged = since.filter { it.success != null && it.role != CallRole.NARRATOR }
        val t = judged.filter { it.trial && it.policyVersion == trial.version }
        val c = judged.filter { !it.trial }
        if (t.size < lp.minEvidence || c.size < lp.minEvidence) return Report(note = "trial collecting evidence (${t.size}/${lp.minEvidence})")
        val tRate = t.count { it.success == true }.toDouble() / t.size
        val cRate = c.count { it.success == true }.toDouble() / c.size
        val tLb = wilsonLower(t.count { it.success == true }, t.size, lp.z)
        val tCost = t.map { it.costUsd }.average()
        val cCost = c.map { it.costUsd }.average()
        val summary = "trial ${pct(tRate)} success (n=${t.size}, $${"%.4f".format(Locale.US, tCost)}/call) vs control ${pct(cRate)} (n=${c.size}, $${"%.4f".format(Locale.US, cCost)}/call)"
        val better = tLb >= cRate
        val cheaperAndNearlyAsGood = tCost < cCost * 0.9 && tRate >= cRate - lp.promoteMargin
        return if (better || cheaperAndNearlyAsGood) {
            Report(promoted = book.promoteTrial("Promoted: $summary"), note = "promoted")
        } else if (tRate < cRate - lp.promoteMargin || t.size >= lp.minEvidence * 3) {
            Report(rolledBack = book.cancelTrial("Rolled back: $summary"), note = "rolled back")
        } else Report(note = "trial inconclusive: $summary")
    }

    private fun sameBonuses(a: Map<String, Double>, b: Map<String, Double>) =
        a.keys == b.keys && a.all { (k, v) -> kotlin.math.abs(v - (b[k] ?: 0.0)) < 0.01 }

    companion object {
        fun wilsonLower(s: Int, n: Int, z: Double): Double {
            if (n == 0) return 0.0
            val p = s.toDouble() / n
            val d = 1 + z * z / n
            return ((p + z * z / (2 * n)) - z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))) / d
        }

        fun wilsonUpper(s: Int, n: Int, z: Double): Double {
            if (n == 0) return 1.0
            val p = s.toDouble() / n
            val d = 1 + z * z / n
            return ((p + z * z / (2 * n)) + z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))) / d
        }

        private fun round3(d: Double) = Math.round(d * 1000) / 1000.0
        private fun pct(d: Double) = "${(d * 100).toInt()}%"
    }
}
