package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.cortex.QualityPriors
import com.axiom.launcher.solis.cortex.RouteNeeds
import com.axiom.launcher.solis.cortex.RouteQuery
import com.axiom.launcher.solis.cortex.Router
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SolisSettings
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadPermissions
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.memory.Space
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receipts, hiding personal details, independent second opinions and imported batch answers, through the real engine. */
class TrustTest {
    @After fun stop() = Rig.killAll()

    private fun accepted(r: SubmitResult) = (r as SubmitResult.Accepted).threadId
    private fun isCoordinator(r: ChatRequest) = r.system.startsWith("You are the coordinator")

    @Test
    fun personalDetailsNeverReachTheCloudButToolsGetThem() {
        var seen = ""
        val p = FakeProvider(script = { r, _ ->
            if (isCoordinator(r)) Script.text("""{"tier":"T1","confidence":0.9,"ambiguity":0.1,"risk":"LOW","domain":"general","needs":["TOOLS"],"steps":[{"id":"s1","goal":"save it","dependsOn":[],"tier":"T1","needs":["TOOLS"]}],"clarify":null,"reasons":[]}""")
            else if (r.system == SolisEngine.VERIFIER_SYSTEM) Script.text("""{"verdict":"pass","issues":[],"confidence":0.9}""")
            else if (r.system == SolisEngine.NARRATOR_SYSTEM) Script.text("Working")
            else if (r.messages.none { it.role.name == "TOOL" }) { seen = r.messages.joinToString("\n") { it.text }; Script.tool("note_add", """{"text":"Call ⟦PHONE_1⟧ or mail ⟦EMAIL_1⟧"}""") }
            else Script.text("Saved a note to call ⟦PHONE_1⟧.")
        })
        val rig = Rig(providers = listOf(p), settings = SolisSettings(redactPersonal = true)).start()
        val id = accepted(rig.engine.submit("note: call Ana on +351 912 345 678 or mail ana@example.pt", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertFalse("the provider saw: $seen", seen.contains("912 345 678") || seen.contains("ana@example.pt"))
        assertTrue(seen, seen.contains("⟦PHONE_1⟧") && seen.contains("⟦EMAIL_1⟧"))
        assertTrue("the system prompt explains placeholders", p.requests.any { it.system.contains("⟦EMAIL_1⟧") })
        assertEquals("the tool ran with the real values", 1, rig.tools.noteRuns.get())
        assertEquals("Saved a note to call +351 912 345 678.", rig.thread(id).lastResult)
        val shown = rig.engine.transcript(id).value.filter { it.kind == EntryKind.ASSISTANT }.joinToString { it.text }
        assertFalse(shown, shown.contains("⟦"))

        val receipts = rig.engine.receipts(id)
        assertTrue(receipts.isNotEmpty())
        val worker = receipts.first { it.role == CallRole.WORKER }
        assertEquals(mapOf("phone" to 1, "email" to 1), worker.redacted)
        assertNotNull("finished calls carry usage", worker.inputTokens)
        val sent = rig.engine.lastRequest(id)!!
        assertTrue(sent, sent.startsWith("To Fake fake"))
        assertFalse("the request view shows what was sent, not the originals", sent.contains("ana@example.pt"))
        rig.engine.delete(id)
        assertTrue("receipts go with the task", rig.engine.receipts(id).isEmpty())
    }

    @Test
    fun localRuntimesAndTheSettingOffSendEverything() {
        val p = FakeProvider(script = { _, _ -> Script.text("ok") })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("what is ana@example.pt?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertTrue(p.requests.any { r -> r.messages.any { it.text.contains("ana@example.pt") } })
        assertTrue(rig.engine.receipts(id).all { it.redacted.isEmpty() })
    }

    @Test
    fun secondOpinionsComeFromAnotherMaker() {
        val claude = Models.m("anthropic/claude-x", 1e-6, 4e-6, 90.0)
        val gpt = Models.m("openai/gpt-y", 2e-6, 8e-6, 80.0)
        val models = listOf(claude, gpt)
        fun route(q: RouteQuery) = Router(StatsBook()).route(q, models, setOf("fake"), PolicyBook(InMemoryBrainStore()).also { it.load() }.snapshot(), QualityPriors(models))
        val base = RouteQuery(CallRole.WORKER, Tier.T2, RouteNeeds(tools = true), 2000, 800, PrivacyConstraint(), 1.0)
        assertEquals(claude.id, route(base).model.id)
        val other = route(base.copy(excludeFamily = "anthropic"))
        assertEquals(gpt.id, other.model.id)
        assertTrue(other.explanation.joinToString(), other.explanation.any { it.contains("independent of anthropic") })
        assertEquals("a pin to the excluded maker is skipped", gpt.id, route(base.copy(excludeFamily = "anthropic", pinned = claude.ref)).model.id)
        val only = Router(StatsBook()).route(base.copy(excludeFamily = "anthropic"), listOf(claude), setOf("fake"),
            PolicyBook(InMemoryBrainStore()).also { it.load() }.snapshot(), QualityPriors(listOf(claude)))
        assertEquals("falls back rather than failing", claude.id, only.model.id)
        assertTrue(only.explanation.any { it.contains("isn't independent") })

        // Through the engine: an independent thread never uses the excluded family while another is eligible.
        val p = FakeProvider(script = { _, _ -> Script.text("Looks fine. Verdict: merge.") })
        val rig = Rig(providers = listOf(p), models = listOf(claude, gpt)).start()
        val id = accepted(rig.engine.submit("Review this change", Space.PERSONAL, "personal", independentOf = "anthropic"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertTrue(p.requests.isNotEmpty())
        val calls = p.requests.filter { it.system != SolisEngine.NARRATOR_SYSTEM }
        assertTrue(calls.map { it.model }.toString(), calls.none { it.model == claude.id })
        assertEquals("anthropic", rig.thread(id).independentOf)
    }

    @Test
    fun importedBatchAnswersReadAndContinueLikeTasks() {
        val p = FakeProvider(script = { _, _ -> Script.text("Follow-up answer") })
        val rig = Rig(providers = listOf(p)).start()
        val id = rig.engine.importFinished("Essay plan", "Plan an essay on tides", "1. Moon\n2. Sun", Space.PERSONAL, "personal",
            ThreadPermissions(), ModelRef("anthropic", "claude-z"), 0.012, 900, 400, "batch")
        val t = rig.thread(id)
        assertEquals(ThreadStatus.DONE, t.status)
        assertEquals("batch", t.origin)
        assertEquals(0.012, t.usage.costUsd, 1e-9)
        assertEquals(listOf(EntryKind.USER, EntryKind.ASSISTANT), rig.engine.transcript(id).value.map { it.kind })
        accepted(rig.engine.submit("Make it shorter", Space.PERSONAL, "personal", threadId = id))
        awaitUntil(8000, "follow-up done") { rig.thread(id).turn == 2 && rig.thread(id).status == ThreadStatus.DONE }
        assertTrue("the earlier batch answer is context", p.requests.any { r -> r.messages.any { it.text.contains("1. Moon") } })
    }

    @Suppress("unused")
    private fun catalog(rig: Rig, list: List<com.axiom.launcher.solis.catalog.ModelInfo>) =
        rig.registry.catalog.replace(CatalogSnapshot("fake", System.currentTimeMillis(), list))
}
