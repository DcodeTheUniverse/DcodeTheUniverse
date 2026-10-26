package com.axiom.launcher.solis

import com.axiom.launcher.solis.cortex.BudgetCaps
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.CoordinatorProtocol
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.DeterministicHandler
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.SharingRules
import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    @org.junit.After
    fun stopEngines() = Rig.killAll()

    private fun accepted(r: SubmitResult) = (r as SubmitResult.Accepted).threadId
    private fun userText(r: ChatRequest) = r.messages.lastOrNull()?.text.orEmpty()
    private fun isCoordinator(r: ChatRequest) = r.system.startsWith("You are the coordinator")
    private fun isVerifier(r: ChatRequest) = r.system == SolisEngine.VERIFIER_SYSTEM
    private fun isNarrator(r: ChatRequest) = r.system == SolisEngine.NARRATOR_SYSTEM

    @Test
    fun deterministicRequestsBypassInference() {
        val det = DeterministicHandler { text, _ -> if (text.startsWith("timer")) "Timer set for 5 min" else null }
        val rig = Rig(deterministic = det).start()
        val id = accepted(rig.engine.submit("timer 5 min", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        val t = rig.thread(id)
        assertEquals(Tier.T0, t.tier)
        assertEquals("Timer set for 5 min", t.lastResult)
        assertEquals(0, rig.providers[0].requests.size)
        assertEquals(0.0, t.usage.costUsd, 0.0)
    }

    @Test
    fun routineRequestRunsToolLoopAndCountsCost() {
        val p = FakeProvider(script = { r, n ->
            if (n == 0) Script.tool("note_add", """{"text":"buy oat milk"}""") else Script.text("Saved your note.")
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("note: buy oat milk", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertEquals(1, rig.tools.noteRuns.get())
        val t = rig.thread(id)
        assertEquals("Saved your note.", t.lastResult)
        assertTrue("usage recorded", t.usage.costUsd > 0)
        assertEquals("both calls in the ledger", 2, rig.engine.ledger.summary().callsToday)
        // The second call carries the tool result back to the same model.
        assertEquals(p.requests[0].model, p.requests[1].model)
        assertTrue(rig.engine.transcript(id).value.any { it.kind == EntryKind.TOOL && it.text.contains("note saved") })
        // T1 routine work skips the coordinator entirely.
        assertTrue(p.requests.none { isCoordinator(it) })
    }

    @Test
    fun modelFallbackOnProviderOutage() {
        val p = FakeProvider(script = { _, n ->
            if (n == 0) Script.Fail(ProviderException(ErrorKind.UNAVAILABLE, "upstream 503", 503)) else Script.text("Recovered answer")
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("What's a good name for a grey cat?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertEquals("Recovered answer", rig.thread(id).lastResult)
        assertEquals(2, p.requests.size)
        assertNotEquals("fell back to a different model", p.requests[0].model, p.requests[1].model)
        val failed = rig.engine.stats.get(com.axiom.launcher.solis.llm.ModelRef("fake", p.requests[0].model))!!
        assertEquals(1, failed.calls)
        assertEquals(0, failed.ok)
        assertTrue(rig.engine.transcript(id).value.any { it.kind == EntryKind.STATUS && it.text.contains("falling back") })
    }

    @Test
    fun coordinatorPlansParallelStepsThenCombines() {
        val plan = """Here you go: {"tier":"T2","confidence":0.8,"ambiguity":0.1,"risk":"LOW","domain":"planning","needs":[],
            "steps":[{"id":"s1","goal":"Find trails","dependsOn":[],"tier":"T1"},
                     {"id":"s2","goal":"Check weather","dependsOn":[],"tier":"T1"},
                     {"id":"s3","goal":"Write itinerary","dependsOn":["s1","s2"],"tier":"T2"}],"clarify":null,"reasons":["3 parts"]}"""
        val p = FakeProvider(script = { r, _ ->
            val u = userText(r)
            when {
                isCoordinator(r) -> Script.text(plan)
                u.contains("Your step: Find trails") -> Script.text("Trails: Mesa, Chautauqua", delayMs = 300)
                u.contains("Your step: Check weather") -> Script.text("Weather: sunny Saturday", delayMs = 300)
                u.contains("Your step: Write itinerary") -> {
                    assertTrue("s3 sees earlier results", u.contains("Mesa") && u.contains("sunny"))
                    Script.text("Sat: Mesa trail")
                }
                u.contains("Results from the plan's steps") -> Script.text("Final: hike Mesa on sunny Saturday")
                isNarrator(r) -> Script.text("Planned a sunny Mesa hike")
                else -> Script.text("?")
            }
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("Research hiking trails near Boulder and then compare the weather, then plan a weekend itinerary",
            Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        val t = rig.thread(id)
        assertEquals("Final: hike Mesa on sunny Saturday", t.lastResult)
        assertEquals(3, t.stepsTotal)
        assertTrue("independent steps ran concurrently", p.maxInFlight >= 2)
        assertEquals(1, p.requests.count { isCoordinator(it) })
        assertTrue("coordinator cost counted", (t.usage.byRole[CallRole.COORDINATOR.name] ?: 0.0) > 0)
        assertTrue("combiner cost counted", (t.usage.byRole[CallRole.COMBINER.name] ?: 0.0) > 0)
    }

    @Test
    fun sensitiveWorkIsVerifiedByAnIndependentModelAndEscalates() {
        var verifierCalls = 0
        val p = FakeProvider(script = { r, _ ->
            when {
                isCoordinator(r) -> Script.text("""{"tier":"T2","confidence":0.9,"risk":"HIGH","domain":"medical","steps":[{"id":"s1","goal":"answer","tier":"T2"}]}""")
                isVerifier(r) -> { verifierCalls++; if (verifierCalls == 1) Script.text("""{"verdict":"fail","issues":["missing advice to see a doctor"],"confidence":0.8}""")
                    else Script.text("""{"verdict":"pass","issues":[],"confidence":0.9}""") }
                userText(r).contains("verifier flagged") -> Script.text("Rest, fluids, and see a doctor if the fever lasts.")
                else -> Script.text("Rest and fluids.")
            }
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("I have a headache and fever, what medication dosage should I take?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        val t = rig.thread(id)
        assertEquals("safety floor keeps sensitive work at T3", Tier.T3, t.tier)
        assertEquals(2, verifierCalls)
        assertTrue(t.lastResult!!.contains("see a doctor"))
        val worker = p.requests.last { !isCoordinator(it) && !isVerifier(it) && !isNarrator(it) }.model
        val verifier = p.requests.last { isVerifier(it) }.model
        assertNotEquals("verifier is independent of the worker", worker.substringBefore('/'), verifier.substringBefore('/'))
        assertTrue(rig.engine.transcript(id).value.any { it.text.contains("Independently verified") })
    }

    @Test
    fun threadsAreIsolated() {
        val p = FakeProvider(script = { r, _ -> Script.text("echo: " + userText(r).takeLast(40), delayMs = 150) })
        val rig = Rig(providers = listOf(p)).start()
        val a = accepted(rig.engine.submit("Tell me about the Falcon project", Space.PERSONAL, "personal"))
        rig.memory.remember("Falcon launch code is 4242", MemoryKind.NOTE, Scope(Space.PERSONAL, a), "you")
        val b = accepted(rig.engine.submit("What is the Falcon launch code?", Space.PERSONAL, "personal"))
        awaitStatus(rig, a, ThreadStatus.DONE)
        awaitStatus(rig, b, ThreadStatus.DONE)
        val reqA = p.requests.filter { userText(it).contains("Tell me about the Falcon") }
        val reqB = p.requests.filter { userText(it).contains("What is the Falcon launch code") }
        assertTrue("requests: " + p.requests.map { it.model + " :: " + userText(it).replace("\n", " ").take(200) }, reqA.isNotEmpty() && reqB.isNotEmpty())
        assertTrue("B never sees A's request", reqB.none { userText(it).contains("Tell me about") })
        assertTrue("B can't read A's thread memory", reqB.none { userText(it).contains("4242") })
        // A follow-up in thread A does see its own thread memory.
        rig.engine.submit("And the launch code?", Space.PERSONAL, "personal", threadId = a)
        awaitUntil(what = "follow-up done") { p.requests.any { userText(it).contains("And the launch code?") } && rig.thread(a).status == ThreadStatus.DONE }
        assertTrue(p.requests.filter { userText(it).contains("And the launch code?") }.any { userText(it).contains("4242") })
        // ...and its earlier turn as plain-text history, without the other thread's.
        val follow = p.requests.last { userText(it).contains("And the launch code?") }
        assertTrue(follow.messages.any { it.text.contains("Tell me about the Falcon project") })
        assertTrue(follow.messages.none { it.text.contains("What is the Falcon launch code") })
    }

    @Test
    fun concurrencyLimitIsRespected() {
        val p = FakeProvider(script = { _, _ -> Script.text("done", delayMs = 250) })
        val rig = Rig(providers = listOf(p), settings = com.axiom.launcher.solis.engine.SolisSettings(maxConcurrentThreads = 1)).start()
        val ids = (1..3).map { accepted(rig.engine.submit("question number $it about tea", Space.PERSONAL, "personal")) }
        ids.forEach { awaitStatus(rig, it, ThreadStatus.DONE) }
        assertEquals("never more than one model call in flight", 1, p.maxInFlight)
    }

    @Test
    fun queueRejectsWhenFull() {
        val p = FakeProvider(script = { _, _ -> Script.Hang })
        val rig = Rig(providers = listOf(p), settings = com.axiom.launcher.solis.engine.SolisSettings(maxConcurrentThreads = 1, maxQueued = 2)).start()
        val first = accepted(rig.engine.submit("long task one", Space.PERSONAL, "personal"))
        awaitStatus(rig, first, ThreadStatus.RUNNING)
        accepted(rig.engine.submit("long task two", Space.PERSONAL, "personal"))
        accepted(rig.engine.submit("long task three", Space.PERSONAL, "personal"))
        val r = rig.engine.submit("long task four", Space.PERSONAL, "personal")
        assertTrue(r is SubmitResult.Rejected)
        rig.kill()
    }

    @Test
    fun dependenciesWaitForUpstreamThread() {
        val p = FakeProvider(script = { r, _ -> Script.text("ok " + userText(r).take(10), delayMs = if (userText(r).contains("first")) 400 else 0) })
        val rig = Rig(providers = listOf(p)).start()
        val a = accepted(rig.engine.submit("first collect the facts", Space.PERSONAL, "personal"))
        val b = accepted(rig.engine.submit("second write the summary", Space.PERSONAL, "personal", dependsOn = listOf(a)))
        awaitStatus(rig, b, ThreadStatus.WAITING)
        assertEquals(ThreadStatus.RUNNING, rig.thread(a).status)
        awaitStatus(rig, b, ThreadStatus.DONE)
        val firstB = p.requests.indexOfFirst { userText(it).contains("second write") }
        val lastA = p.requests.indexOfLast { userText(it).contains("first collect") }
        assertTrue("B ran after A: " + p.requests.map { userText(it).takeLast(30) }, firstB > lastA)
    }

    @Test
    fun pauseResumeAndCancel() {
        var hang = true
        val p = FakeProvider(script = { _, _ -> if (hang) Script.Hang else Script.text("resumed fine") })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("explain tides simply", Space.PERSONAL, "personal"))
        awaitUntil(what = "call in flight") { p.inFlight.get() == 1 }
        rig.engine.pause(id)
        awaitStatus(rig, id, ThreadStatus.PAUSED)
        assertEquals("pausing aborts the in-flight call", 0, p.inFlight.get())
        hang = false
        rig.engine.resume(id)
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertEquals("resumed fine", rig.thread(id).lastResult)

        hang = true
        val c = accepted(rig.engine.submit("explain gravity simply", Space.PERSONAL, "personal"))
        awaitUntil(what = "second call in flight") { p.inFlight.get() == 1 }
        rig.engine.cancel(c)
        awaitStatus(rig, c, ThreadStatus.CANCELLED)
    }

    @Test
    fun checkpointRecoveryDoesNotRepeatSideEffects() = runBlocking {
        val store = CrashableStore()
        val p1 = FakeProvider(script = { _, n -> if (n == 0) Script.tool("set_alarm", """{"minute":30,"hour":6}""") else Script.Hang })
        val rig1 = Rig(store = store, providers = listOf(p1)).start()
        val id = accepted(rig1.engine.submit("Set an alarm for 6:30 then tell me when I should sleep", Space.PERSONAL, "personal"))
        awaitUntil(what = "alarm set then hanging") { rig1.tools.alarmRuns.get() == 1 && p1.requests.size == 2 }
        rig1.engine.flush()
        store.frozen = true          // the process dies: nothing more reaches disk
        rig1.kill()

        store.frozen = false
        val p2 = FakeProvider(script = { r, n ->
            if (n == 0) {
                assertTrue("worker told what already happened", userText(r).contains("<already_done>"))
                Script.tool("set_alarm", """{"hour":6,"minute":30}""")
            } else Script.text("Alarm is set for 6:30; sleep by 22:30.")
        })
        val rig2 = Rig(store = store, providers = listOf(p2)).start()
        rig2.engine.awaitStarted()
        awaitStatus(rig2, id, ThreadStatus.INTERRUPTED)
        assertNotNull(rig2.thread(id).blocker)
        assertTrue(store.checkpoints(id).isNotEmpty())
        rig2.engine.resume(id)
        awaitStatus(rig2, id, ThreadStatus.DONE)
        assertEquals("side effect not repeated after recovery", 0, rig2.tools.alarmRuns.get())
        assertTrue(rig2.engine.transcript(id).value.any { it.kind == EntryKind.TOOL && it.text.contains("already done") })
        assertEquals("Alarm is set for 6:30; sleep by 22:30.", rig2.thread(id).lastResult)
    }

    @Test
    fun autoResumeThreadsContinueAfterRestart() = runBlocking {
        val store = CrashableStore()
        val p1 = FakeProvider(script = { _, _ -> Script.Hang })
        val rig1 = Rig(store = store, providers = listOf(p1)).start()
        val id = accepted(rig1.engine.submit("summarize the history of tea", Space.PERSONAL, "personal",
            permissions = com.axiom.launcher.solis.engine.ThreadPermissions(autoResume = true)))
        awaitUntil(what = "in flight") { p1.inFlight.get() == 1 }
        rig1.engine.flush(); store.frozen = true; rig1.kill(); store.frozen = false
        val rig2 = Rig(store = store, providers = listOf(FakeProvider(script = { _, _ -> Script.text("Tea began in China.") }))).start()
        awaitStatus(rig2, id, ThreadStatus.DONE)
    }

    @Test
    fun spendingCapBlocksBeforeAnyCall() {
        val p = FakeProvider()
        val rig = Rig(providers = listOf(p), settings = com.axiom.launcher.solis.engine.SolisSettings(caps = BudgetCaps(dailyUsd = 0.0000001))).start()
        val id = accepted(rig.engine.submit("What's the capital of Peru?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.BLOCKED)
        assertEquals(0, p.requests.size)
        assertTrue(rig.thread(id).blocker!!.contains("budget"))
    }

    @Test
    fun narratorCoalescesAndStaysQuietForSimpleWork() = runBlocking {
        val plan = """{"tier":"T2","confidence":0.8,"steps":[{"id":"s1","goal":"part one"},{"id":"s2","goal":"part two"},{"id":"s3","goal":"part three","dependsOn":["s1","s2"]}]}"""
        val p = FakeProvider(script = { r, _ ->
            when {
                isCoordinator(r) -> Script.text(plan)
                isNarrator(r) -> Script.text("Three parts done; summary ready")
                else -> Script.text("result " + userText(r).takeLast(12))
            }
        })
        val rig = Rig(providers = listOf(p)).start()
        rig.engine.awaitStarted()
        val m = rig.engine.policies.active().matrix
        // A window wide enough that a busy test JVM still delivers the steps and the completion in one batch,
        // and a minimum interval so a late event can't buy a second call.
        rig.engine.policies.applyUserEdit(m.copy(narrator = m.narrator.copy(coalesceMs = 1_200, minIntervalMs = 60_000)), "narrator for test")
        val simple = accepted(rig.engine.submit("What's 2+2 in words?", Space.PERSONAL, "personal"))
        awaitStatus(rig, simple, ThreadStatus.DONE)
        val multi = accepted(rig.engine.submit("Research A and then compare B, then plan C carefully with a detailed itinerary", Space.PERSONAL, "personal"))
        awaitStatus(rig, multi, ThreadStatus.DONE)
        awaitUntil(what = "narration") { rig.thread(multi).narration != null }
        Thread.sleep(400)
        assertEquals("one coalesced narrator call for the multi-step task, none for the simple one", 1, p.requests.count { isNarrator(it) })
        assertEquals("Three parts done; summary ready", rig.thread(multi).narration)
        assertEquals(null, rig.thread(simple).narration)
        assertTrue((rig.thread(multi).usage.byRole[CallRole.NARRATOR.name] ?: 0.0) > 0)
    }

    @Test
    fun offlineWaitsThenRunsWhenConnectivityReturns() {
        val p = FakeProvider(script = { _, _ -> Script.text("online now") })
        val rig = Rig(providers = listOf(p), device = DeviceSnapshot(online = false)).start()
        val id = accepted(rig.engine.submit("what is a quark?", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.WAITING)
        assertEquals(0, p.requests.size)
        assertEquals(com.axiom.launcher.solis.engine.Mood.OFFLINE, rig.engine.mood.value)
        rig.device = DeviceSnapshot(online = true)
        rig.engine.onConditionsChanged()
        awaitStatus(rig, id, ThreadStatus.DONE)
    }

    @Test
    fun workSpaceCannotReadPersonalMemoryUnlessShared() {
        val p = FakeProvider(script = { _, _ -> Script.text("ok") })
        val rig = Rig(providers = listOf(p)).start()
        rig.memory.remember("My salary is 100k", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        val w = accepted(rig.engine.submit("what is my salary?", Space.WORK, "work"))
        awaitStatus(rig, w, ThreadStatus.DONE)
        assertTrue(p.requests.none { userText(it).contains("100k") })

        rig.settings = rig.settings.copy(sharing = SharingRules(workReadsPersonal = true))
        val w2 = accepted(rig.engine.submit("what is my salary now?", Space.WORK, "work"))
        awaitStatus(rig, w2, ThreadStatus.DONE)
        assertTrue(p.requests.filter { userText(it).contains("salary now") }.any { userText(it).contains("100k") })
        // Work threads see work-profile apps from the tool host, personal threads don't.
        assertFalse(SystemPromptCheck.workPromptMentionsSeparation(rig.thread(w).system).not())
    }

    @Test
    fun refusalFallsBackToAnotherModel() {
        val p = FakeProvider(script = { _, n ->
            if (n == 0) Script.Reply(listOf(com.axiom.launcher.solis.llm.LlmEvent.Finish(com.axiom.launcher.solis.llm.FinishReason.REFUSAL)))
            else Script.text("Here is a safe answer")
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = accepted(rig.engine.submit("tell me about lock picking as a hobby", Space.PERSONAL, "personal"))
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertNotEquals(p.requests[0].model, p.requests[1].model)
    }

    @Test
    fun coordinatorParsingIsDefensive() {
        val local = com.axiom.launcher.solis.cortex.LocalAssessor.assess("plan my week", com.axiom.launcher.solis.cortex.DecisionMatrix.default())
        assertEquals(null, CoordinatorProtocol.parse("no json here", local))
        val cyc = CoordinatorProtocol.parse("""{"tier":"T2","steps":[{"id":"a","goal":"x","dependsOn":["b"]},{"id":"b","goal":"y","dependsOn":["a"]}]}""", local)!!
        assertTrue("cycle broken", cyc.steps.any { it.dependsOn.isEmpty() })
    }
}

object SystemPromptCheck {
    fun workPromptMentionsSeparation(s: String) = s.contains("work space") && s.contains("separate")
}
