package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.CatalogParsers
import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.catalog.Freshness
import com.axiom.launcher.solis.cortex.BudgetCaps
import com.axiom.launcher.solis.cortex.BudgetExceeded
import com.axiom.launcher.solis.cortex.BudgetLedger
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DecisionMatrix
import com.axiom.launcher.solis.cortex.LocalAssessor
import com.axiom.launcher.solis.cortex.Need
import com.axiom.launcher.solis.cortex.NoEligibleModel
import com.axiom.launcher.solis.cortex.Outcome
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.PolicyLearner
import com.axiom.launcher.solis.cortex.PolicyStatus
import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.cortex.QualityPriors
import com.axiom.launcher.solis.cortex.Risk
import com.axiom.launcher.solis.cortex.RouteNeeds
import com.axiom.launcher.solis.cortex.RouteQuery
import com.axiom.launcher.solis.cortex.Router
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ModelRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CortexTest {
    private val providers = setOf("fake", "local")
    private fun book(): PolicyBook = PolicyBook(InMemoryBrainStore()).also { it.load() }
    private fun q(role: CallRole = CallRole.WORKER, tier: Tier = Tier.T1, tools: Boolean = false, budget: Double? = 1.0,
                  privacy: PrivacyConstraint = PrivacyConstraint(), pinned: ModelRef? = null, exclude: Set<ModelRef> = emptySet(),
                  vision: Boolean = false, avoid: String? = null) =
        RouteQuery(role, tier, RouteNeeds(tools = tools, vision = vision), 2000, 700, privacy, budget, exclude, avoid, pinned, "thread-1")

    private val local = Models.m("llama3.2:3b", 0.0, 0.0, null, tools = false, provider = "local", local = true)
    private val models = Models.all + local + Models.m("acme/old", 1e-7, 1e-7, 40.0).copy(expiresAt = System.currentTimeMillis() + 3600_000) +
        Models.m("acme/cheap:batch", 5e-8, 2e-7, 30.0).copy(isBatch = true) + Models.m("openrouter/auto", 0.0, 0.0, null).copy(inputPrice = null, outputPrice = null, isMeta = true)

    private fun route(query: RouteQuery, stats: StatsBook = StatsBook(), b: PolicyBook = book()) =
        Router(stats).route(query, models, providers, b.snapshot(), QualityPriors(models))

    @Test
    fun hardConstraintsComeFirst() {
        val d = route(q(tools = true, tier = Tier.T2))
        assertTrue(d.model.supportsTools)
        assertFalse(d.model.isBatch || d.model.isMeta)
        assertNotEquals("retiring models are skipped", "acme/old", d.model.id)
        assertTrue(d.explanation.any { it.startsWith("ruled out") })

        // Cloud forbidden -> only local models; tools needed + local model lacks tools -> nothing eligible.
        val localOnly = route(q(privacy = PrivacyConstraint(cloudAllowed = false)))
        assertTrue(localOnly.model.isLocal)
        try {
            route(q(tools = true, privacy = PrivacyConstraint(cloudAllowed = false)))
            fail("expected NoEligibleModel")
        } catch (e: NoEligibleModel) {
            assertTrue(e.message!!.contains("cloud not allowed") || e.message!!.contains("no tool calling"))
        }
    }

    @Test
    fun budgetExcludesExpensiveModels() {
        val d = route(q(tier = Tier.T3, budget = 0.005))
        val est = d.model.estimateCost(2000, 700)!!
        assertTrue("estimated $est within budget", est <= 0.005)
        try {
            route(q(tier = Tier.T3, budget = 0.0))
            // only free/local may remain
        } catch (e: NoEligibleModel) {
            assertTrue(e.reasons.containsKey("over budget/tier cap"))
        }
    }

    @Test
    fun userOverrideHonoredOnlyWhenEligible() {
        val ok = route(q(pinned = Models.cheap.ref, tools = true))
        assertEquals(Models.cheap.id, ok.model.id)
        assertTrue(ok.explanation.first().contains("your choice"))
        val skipped = route(q(pinned = Models.noTools.ref, tools = true))
        assertNotEquals("a pinned model that can't call tools is not used for a tool task", Models.noTools.id, skipped.model.id)
        assertTrue(skipped.explanation.any { it.contains("was skipped") })
    }

    @Test
    fun learnedBonusesNeverOverrideConstraints() {
        val store = InMemoryBrainStore()
        val b = PolicyBook(store).also { it.load() }
        // Give the tool-less model an enormous learned bonus...
        b.startTrial(mapOf(Router.bonusKey(Tier.T1, Models.noTools.ref) to 5.0), "test")
        b.promoteTrial("forced")
        val d = route(q(tools = true), b = b)
        assertNotEquals("...it still can't be picked for a task that needs tools", Models.noTools.id, d.model.id)
        // ...nor bypass the tier's quality floor (it is below T1's floor even without tools).
        val d2 = route(q(tools = false), b = b)
        assertNotEquals(Models.noTools.id, d2.model.id)
        // A bonus on an eligible model does change the ranking.
        val b2 = PolicyBook(InMemoryBrainStore()).also { it.load() }
        b2.startTrial(mapOf(Router.bonusKey(Tier.T1, Models.judge.ref) to 0.5), "test"); b2.promoteTrial("forced")
        assertEquals(Models.judge.id, route(q(tools = true), b = b2).model.id)
        // ...but even a huge bonus can't lift a model over the tier's cost cap (strong costs ~$0.022 > $0.02 at T1).
        val b3 = PolicyBook(InMemoryBrainStore()).also { it.load() }
        b3.startTrial(mapOf(Router.bonusKey(Tier.T1, Models.strong.ref) to 5.0), "test"); b3.promoteTrial("forced")
        assertNotEquals(Models.strong.id, route(q(tools = true), b = b3).model.id)
    }

    @Test
    fun effortOnlyWhereSupported() {
        val strong = route(q(tier = Tier.T3, pinned = Models.strong.ref))
        assertEquals(Effort.HIGH, strong.effort)
        val judge = route(q(tier = Tier.T3, pinned = Models.judge.ref))
        assertNull("judge publishes no effort control", judge.effort)
        // Desired effort above what's offered maps down to the closest supported level.
        val m = Models.m("x/y", 1e-6, 1e-6, 50.0, efforts = listOf(Effort.LOW, Effort.MEDIUM))
        assertEquals(Effort.MEDIUM, m.effortFor(Effort.HIGH))
        assertEquals(Effort.LOW, m.effortFor(Effort.LOW))
    }

    @Test
    fun verifierAvoidsWorkerFamilyAndFallbacksDiversify() {
        val worker = route(q(tier = Tier.T3, tools = true))
        val verifier = route(q(role = CallRole.VERIFIER, tier = Tier.T3, avoid = worker.model.family, exclude = setOf(worker.model.ref)))
        assertNotEquals(worker.model.family, verifier.model.family)
        if (worker.fallbacks.size >= 2) assertNotEquals(worker.fallbacks[0].family, worker.model.family)
    }

    @Test
    fun measuredOutcomesShiftQuality() {
        val stats = StatsBook()
        val r = Router(stats)
        val pri = QualityPriors(models)
        val before = r.quality(Models.mid, Tier.T1, pri)
        repeat(20) { stats.record(outcome(Models.mid.ref, Tier.T1, success = false)) }
        assertTrue(r.quality(Models.mid, Tier.T1, pri) < before)
    }

    @Test
    fun learnerNeedsEvidenceThenTrialsThenPromotesOrRollsBack() {
        val store = InMemoryBrainStore()
        val b = PolicyBook(store).also { it.load() }
        val learner = PolicyLearner(b)
        assertNull(learner.evaluate(List(5) { outcome(Models.mid.ref, Tier.T1, true) }).proposed)
        // Plenty of evidence: mid succeeds, judge fails often.
        val history = List(20) { outcome(Models.mid.ref, Tier.T1, true) } + List(20) { outcome(Models.judge.ref, Tier.T1, it % 2 == 0) }
        val report = learner.evaluate(history)
        val trial = report.proposed
        assertNotNull(trial)
        assertEquals(PolicyStatus.TRIAL, b.snapshot().trial?.status)
        assertTrue(trial!!.bonuses.getValue(Router.bonusKey(Tier.T1, Models.mid.ref)) > 0)
        assertTrue(trial.bonuses.getValue(Router.bonusKey(Tier.T1, Models.judge.ref)) < 0)
        // Trial outcomes beat control -> promoted.
        val after = history + List(15) { outcome(Models.mid.ref, Tier.T1, true, trial = true, version = trial.version) } +
            List(15) { outcome(Models.judge.ref, Tier.T1, it % 3 != 0, trial = false) }
        val promoted = learner.evaluate(after).promoted
        assertNotNull(promoted)
        assertEquals(trial.version, b.active().version)

        // A worse trial gets rolled back, and the user can always restore any earlier version.
        val b2 = PolicyBook(InMemoryBrainStore()).also { it.load() }
        val l2 = PolicyLearner(b2)
        val t2 = b2.startTrial(mapOf(Router.bonusKey(Tier.T1, Models.judge.ref) to 0.1), "bad idea")!!
        val worse = List(15) { outcome(Models.judge.ref, Tier.T1, it % 4 == 0, trial = true, version = t2.version) } +
            List(15) { outcome(Models.mid.ref, Tier.T1, true) }
        assertNotNull(l2.evaluate(worse).rolledBack)
        val restored = b2.rollbackTo(1)
        assertEquals(PolicyStatus.ACTIVE, restored.status)
        assertTrue(restored.version > t2.version)
        assertTrue(b2.history().size >= 3)
    }

    @Test
    fun userEditsAreVersionedAndReversible() {
        val b = book()
        val v1 = b.active().version
        val m = b.active().matrix
        val edited = b.applyUserEdit(DecisionMatrix.fromJson(m.toJson().put("note", "x")).copy(
            tiers = m.tiers + (Tier.T1 to m.policy(Tier.T1).copy(maxCallCostUsd = 0.005))), "cheaper T1")
        assertEquals(0.005, b.active().matrix.policy(Tier.T1).maxCallCostUsd, 1e-9)
        b.rollbackTo(v1)
        assertEquals(0.02, b.active().matrix.policy(Tier.T1).maxCallCostUsd, 1e-9)
        assertTrue(edited.version > v1)
        // JSON round trip keeps every field.
        val j = b.active().matrix.toJson()
        assertEquals(j.toString(), DecisionMatrix.fromJson(JSONObject(j.toString())).toJson().toString())
    }

    @Test
    fun budgetReservationsStopConcurrentOverspend() {
        val ledger = BudgetLedger(InMemoryBrainStore()).also { it.load() }
        val caps = BudgetCaps(dailyUsd = 0.10, monthlyUsd = 5.0, narratorDailyUsd = 0.01)
        val r1 = ledger.reserve(0.06, caps, "personal", null, "a", null, CallRole.WORKER)
        try {
            ledger.reserve(0.06, caps, "personal", null, "b", null, CallRole.WORKER)
            fail("second reservation must not fit")
        } catch (e: BudgetExceeded) { }
        ledger.commit(r1, "a", "personal", CallRole.WORKER, "fake|x", 0.02, 100, 10, false)
        assertEquals(0.08, ledger.remaining(caps, "personal", null, "b", null, CallRole.WORKER), 1e-9)
        assertEquals("narrator has its own cap", 0.01, ledger.remaining(caps, "personal", null, "b", null, CallRole.NARRATOR), 1e-9)
        assertEquals(0.0, ledger.remaining(caps, "work", 0.0, "b", null, CallRole.WORKER), 1e-9)
        assertEquals(0.02, ledger.summary().byRoleToday[CallRole.WORKER]!!, 1e-9)
    }

    @Test
    fun localAssessment() {
        val m = DecisionMatrix.default()
        val simple = LocalAssessor.assess("What's a good name for a grey cat?", m)
        assertEquals(Tier.T1, simple.tier)
        assertTrue(simple.confidence >= m.assessment.uncertaintyBelow)
        assertFalse(LocalAssessor.wantsCoordinator(simple, m))
        val tools = LocalAssessor.assess("set an alarm for 7 and open spotify", m)
        assertTrue(Need.TOOLS in tools.needs)
        val plan = LocalAssessor.assess("Research flights to Lisbon and then compare hotels, then plan a 3 day itinerary with a budget", m)
        assertTrue(plan.tier >= Tier.T2)
        assertTrue(LocalAssessor.wantsCoordinator(plan, m))
        val medical = LocalAssessor.assess("what dosage of ibuprofen for a fever?", m)
        assertEquals(Risk.HIGH, medical.risk)
        assertEquals(Tier.T3, medical.tier)
        val note = LocalAssessor.assess("note: buy oat milk", m)
        assertEquals("buying milk is not high risk", Tier.T1, note.tier)
        val img = LocalAssessor.assess("what is in this photo?", m, attachments = setOf(Need.VISION))
        assertTrue(Need.VISION in img.needs)
    }

    @Test
    fun parsesRealOpenRouterCatalog() {
        val json = javaClass.classLoader!!.getResource("openrouter_models_subset.json")!!.readText()
        val list = CatalogParsers.openRouter(json)
        assertTrue(list.size >= 20)
        val auto = list.first { it.id == "openrouter/auto" }
        assertTrue("-1 prices mean variable: meta-model with unknown price", auto.isMeta && auto.inputPrice == null)
        assertTrue(list.first { it.id.endsWith(":batch") }.isBatch)
        val haiku = list.first { it.id == "anthropic/claude-haiku-5.5" }
        assertTrue(haiku.supportsTools)
        assertTrue(haiku.priceOverrides.isNotEmpty())
        assertTrue("long prompts use the override tier", haiku.estimateCost(200_000, 100)!! > haiku.estimateCost(99_000, 100)!! * 2.0)
        assertTrue(Effort.MEDIUM in haiku.efforts)
        val opus = list.first { it.id == "anthropic/claude-opus-5.5" }
        assertTrue(opus.reasoningMandatory)
        assertEquals(Effort.LOW, opus.effortFor(Effort.MINIMAL))
        assertNotNull(opus.qualityIndex)
        assertTrue(list.any { it.expiresAt != null })
        assertTrue(list.any { it.aliasOf != null })
        assertTrue(list.any { it.isFree })
        assertTrue(list.any { !it.supportsTools })
        // Slim cache round trip keeps routing-relevant fields.
        val snap = CatalogSnapshot("openrouter", 1_000L, list)
        val back = CatalogParsers.fromCache(CatalogParsers.toCache(snap))
        assertEquals(list, back.models)
        assertEquals(Freshness.STALE, back.freshness(now = 1_000L + CatalogSnapshot.DEFAULT_TTL_MS + 1))
        assertEquals(Freshness.FRESH, back.freshness(now = 1_000L + 60_000))
        // Routing over the real catalog picks a sensible tool-capable model without hardcoded names.
        val d = Router(StatsBook()).route(
            RouteQuery(CallRole.WORKER, Tier.T2, RouteNeeds(tools = true), 3000, 1500, PrivacyConstraint(), 1.0, trialKey = "x"),
            list, setOf("openrouter"), book().snapshot(), QualityPriors(list))
        assertTrue(d.model.supportsTools && !d.model.isBatch && !d.model.isMeta)
        assertTrue(d.explanation.size >= 2)
    }

    private fun outcome(ref: ModelRef, tier: Tier, success: Boolean, trial: Boolean = false, version: Int = 1) = Outcome(
        UUID.randomUUID().toString(), System.currentTimeMillis(), "t", CallRole.WORKER, tier, ref, true, success, 1000, 0.001, 100, 50,
        policyVersion = version, trial = trial,
    )
}
