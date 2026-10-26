package com.axiom.launcher

import android.content.ComponentName
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomTestKit.firstUser
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.AxiomTestKit.installFakeProvider
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.NotifItem
import com.axiom.launcher.data.Prefs
import com.axiom.launcher.modes.AiPrivacy
import com.axiom.launcher.modes.Routine
import com.axiom.launcher.modes.RoutineAction
import com.axiom.launcher.platform.FallbackBrainStore
import com.axiom.launcher.platform.ProfileKind
import com.axiom.launcher.platform.SqliteBrainStore
import com.axiom.launcher.solis.CountingTools
import com.axiom.launcher.solis.FakeProvider
import com.axiom.launcher.solis.Models
import com.axiom.launcher.solis.Script
import com.axiom.launcher.solis.SolisHost
import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.cortex.BudgetLedger
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DecisionMatrix
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.cortex.LedgerEntry
import com.axiom.launcher.solis.cortex.Outcome
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.PolicyOrigin
import com.axiom.launcher.solis.cortex.PolicyStatus
import com.axiom.launcher.solis.cortex.PolicyVersion
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.ActionRecord
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.Checkpoint
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.engine.Phase
import com.axiom.launcher.solis.engine.ProviderRegistry
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SolisSettings
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.TranscriptEntry
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.MemoryService
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Real Android wiring under Robolectric: SQLite store, app host, modes, routines and profile boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIntegrationTest {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ durable store

    @Test
    fun sqliteStoreRoundTripsEveryRecord() {
        val now = System.currentTimeMillis()
        val a = SqliteBrainStore(app, "roundtrip.db")
        val t = AgentThread("t1", "Plan trip", Space.WORK, "work", ThreadStatus.RUNNING, now, now, turn = 2, tier = Tier.T2,
            models = mapOf("WORKER" to "fake|acme/fast-1"), statusText = "Working", leaseOwner = "engine-a")
        a.upsertThread(t)
        a.appendEntry(TranscriptEntry("t1", 1, 1, EntryKind.USER, "Plan a trip", now))
        a.appendEntry(TranscriptEntry("t1", 2, 1, EntryKind.ROUTE, "T2 · coordinator", now, """{"explanation":["picked x"]}"""))
        a.putCheckpoint(Checkpoint("t1", 2, 1, Phase.PLANNED, """{"x":1}""", now, "planned 2 steps"))
        a.putAction(ActionRecord("k1", "t1", 2, "s1", "set_alarm", "{}", "ok", true, now))
        a.addOutcome(Outcome("o1", now, "t1", CallRole.WORKER, Tier.T1, ModelRef("fake", "acme/fast-1"), ok = true, success = true,
            latencyMs = 900, costUsd = 0.001, inputTokens = 100, outputTokens = 20, policyVersion = 1))
        a.upsertMemory(MemoryItem("m1", MemoryKind.FACT, "Likes window seats", Scope(Space.PERSONAL), "you", now, now))
        a.putPolicy(PolicyVersion(1, DecisionMatrix.default(), mapOf("T1|fake|acme/fast-1" to 0.05), PolicyStatus.ACTIVE, PolicyOrigin.DEFAULT, now, "defaults"))
        a.addLedger(LedgerEntry("l1", now, "t1", "work", CallRole.WORKER, "fake|acme/fast-1", 0.002, 100, 20, false))
        a.kvPut("catalog:fake", "cached")
        a.close()

        // A new instance on the same file = the app after process death or a reboot.
        val b = SqliteBrainStore(app, "roundtrip.db")
        assertEquals(t, b.loadThreads().single())
        assertEquals(listOf("Plan a trip", "T2 · coordinator"), b.entries("t1").map { it.text })
        assertEquals(Phase.PLANNED, b.lastCheckpoint("t1", 2)!!.phase)
        assertEquals("set_alarm", b.findAction("k1")!!.tool)
        assertEquals(1, b.loadOutcomes(now - 1000).size)
        assertEquals("Likes window seats", b.loadMemory().single().text)
        assertEquals(0.05, b.loadPolicies().single().bonuses.values.single(), 1e-9)
        assertEquals(0.002, b.loadLedger(now - 1000).single().costUsd, 1e-9)
        assertEquals("cached", b.kvGet("catalog:fake"))

        b.deleteThread("t1")
        assertTrue(b.loadThreads().isEmpty() && b.entries("t1").isEmpty() && b.checkpoints("t1").isEmpty() && b.actions("t1", 2).isEmpty())
        b.close()
    }

    @Test
    fun fallbackStoreReportsWhenNotDurable() {
        val broken = FallbackBrainStore({ error("disk full") }, { InMemoryBrainStore() })
        assertTrue(broken.loadThreads().isEmpty())
        assertFalse(broken.persistent)
        assertTrue(broken.openError!!.contains("disk full"))
        val ok = FallbackBrainStore({ SqliteBrainStore(app, "ok.db") }, { InMemoryBrainStore() })
        ok.kvPut("a", "b")
        assertTrue(ok.persistent)
    }

    private fun engineOn(store: SqliteBrainStore, provider: FakeProvider, tools: CountingTools, scope: CoroutineScope): SolisEngine {
        val registry = ProviderRegistry(store)
        registry.register(provider)
        registry.catalog.replace(CatalogSnapshot("fake", System.currentTimeMillis(), Models.all))
        return SolisEngine(scope, store, registry, MemoryService(store), PolicyBook(store), BudgetLedger(store), StatsBook(), tools,
            settings = { SolisSettings() }, device = { DeviceSnapshot() }).also { it.start() }
    }

    @Test
    fun interruptedTaskResumesFromSqliteCheckpointWithoutRepeatingActions() = runBlocking {
        val tools = CountingTools()
        val first = FakeProvider(script = { r, _ ->
            when {
                r.system.startsWith("You are the coordinator") -> Script.text("""{"tier":"T1","confidence":0.9,"steps":[{"id":"s1","goal":"alarm"}]}""")
                r.messages.none { it.role == ChatRole.TOOL } -> Script.tool("set_alarm", """{"hour":6,"minute":30}""")
                else -> Script.Hang // the process "dies" while the model is answering after the alarm was set
            }
        })
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val e1 = engineOn(SqliteBrainStore(app, "recover.db"), first, tools, scope1)
        e1.awaitStarted()
        val id = (e1.submit("Set an alarm for 6:30 and tell me when to sleep", Space.PERSONAL, "personal") as SubmitResult.Accepted).threadId
        com.axiom.launcher.solis.awaitUntil(what = "alarm set") { tools.alarmRuns.get() == 1 && e1.thread(id)!!.status == ThreadStatus.RUNNING }
        Thread.sleep(150)
        e1.flush()
        scope1.coroutineContext[Job]!!.cancel()

        val second = FakeProvider(script = { r, _ ->
            if (r.system.startsWith("You are the coordinator")) Script.text("""{"tier":"T1","confidence":0.9,"steps":[{"id":"s1","goal":"alarm"}]}""")
            else Script.text("Alarm is set for 6:30 — aim to sleep by 10:30.")
        })
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val e2 = engineOn(SqliteBrainStore(app, "recover.db"), second, tools, scope2)
        e2.awaitStarted()
        val t = e2.thread(id)!!
        assertEquals(ThreadStatus.INTERRUPTED, t.status)
        assertNotNull(t.blocker)
        e2.resume(id)
        com.axiom.launcher.solis.awaitUntil(what = "done after resume") { e2.thread(id)!!.status == ThreadStatus.DONE }
        assertEquals("the alarm is not set twice", 1, tools.alarmRuns.get())
        assertTrue(e2.thread(id)!!.lastResult!!.contains("6:30"))
        scope2.coroutineContext[Job]!!.cancel()
    }

    // ------------------------------------------------------------------ profile boundaries

    private fun appsWithProfiles(): List<AppInfo> {
        val u = Process.myUserHandle()
        fun a(n: String, k: ProfileKind) = AppInfo("k$n", n, n, ComponentName("p.$n", "M"), u, k == ProfileKind.WORK, AppCategory.OTHER, profile = k)
        return listOf(a("Spotify", ProfileKind.PERSONAL), a("Slack", ProfileKind.WORK), a("Vault", ProfileKind.PRIVATE))
    }

    @Test
    fun solisNeverSeesPrivateSpaceAndPersonalTasksNeverSeeWorkApps() {
        app.core.debugSetApps(appsWithProfiles())
        assertEquals(listOf("Spotify"), app.core.appsFor(Space.PERSONAL).map { it.label })
        assertEquals(setOf("Spotify", "Slack"), app.core.appsFor(Space.WORK).map { it.label }.toSet())

        val listApps = app.solis.tools.tools().first { it.name == "list_apps" }
        val personal = runBlocking { listApps.run(org.json.JSONObject(), com.axiom.launcher.solis.engine.ToolContext("t", 1, "s1", Space.PERSONAL, com.axiom.launcher.solis.engine.ThreadPermissions())) }
        assertFalse(personal.text.contains("Slack") || personal.text.contains("Vault"))
        val work = runBlocking { listApps.run(org.json.JSONObject(), com.axiom.launcher.solis.engine.ToolContext("t", 1, "s1", Space.WORK, com.axiom.launcher.solis.engine.ThreadPermissions())) }
        assertTrue(work.text.contains("Slack") && !work.text.contains("Vault"))
    }

    @Test
    fun notificationsRespectTheProfileBoundary() {
        val items = listOf(NotifItem("Signal", "Mom", "Dinner?", 1, "a", otherProfile = false), NotifItem("Slack", "#ops", "Deploy done", 2, "b", otherProfile = true))
        assertEquals(listOf("Signal"), SolisHost.visibleNotifications(items, Space.PERSONAL, workReadsPersonal = true).map { it.app })
        assertEquals(listOf("Slack"), SolisHost.visibleNotifications(items, Space.WORK, workReadsPersonal = false).map { it.app })
        assertEquals(2, SolisHost.visibleNotifications(items, Space.WORK, workReadsPersonal = true).size)
    }

    @Test
    fun modesShapeSpacePrivacyAndBudgetOfNewTasks() {
        app.installFakeProvider()
        app.core.modes.switch("work")
        val id = (app.solis.submit("Summarize what I should prepare for tomorrow's standup") as SubmitResult.Accepted).threadId
        val t = app.solis.engine.thread(id)!!
        assertEquals(Space.WORK, t.space)
        assertEquals("work", t.modeId)
        assertTrue("work mode requires zero data retention", t.permissions.privacy.zdr)
        assertEquals(0.50, app.solis.settings().modeDailyCaps["work"]!!, 1e-9)

        // A mode with AI off rejects tasks but still answers on-device commands.
        val focus = app.core.modes.mode("focus")!!
        app.core.modes.update(focus.copy(aiPrivacy = AiPrivacy.OFF))
        app.core.modes.switch("focus")
        assertTrue(app.solis.submit("Write me a poem") is SubmitResult.Rejected)
        val local = runBlocking { app.solis.deterministic("battery", Space.PERSONAL) }
        assertTrue(local!!.startsWith("Battery"))
    }

    // ------------------------------------------------------------------ T0 routing

    @Test
    fun deviceCommandsRunWithoutAnyModelButCompoundRequestsDoNot() {
        val p = app.installFakeProvider()
        val id = (app.solis.submit("timer 10 min") as SubmitResult.Accepted).threadId
        idleUntil("T0 done") { app.solis.engine.thread(id)?.status == ThreadStatus.DONE }
        assertEquals(Tier.T0, app.solis.engine.thread(id)!!.tier)
        assertTrue("no model call for a plain command", p.requests.isEmpty())

        assertNull(runBlocking { app.solis.deterministic("wake me at 6:30 and text Sam that I'm late", Space.PERSONAL) })
        val id2 = (app.solis.submit("Wake me at 6:30 tomorrow and remind me to pack my boarding pass") as SubmitResult.Accepted).threadId
        idleUntil("compound done") { app.solis.engine.thread(id2)?.status == ThreadStatus.DONE }
        assertTrue(p.requests.any { firstUser(it).contains("6:30") })
        assertTrue(app.core.notes.value.any { it.text.contains("boarding pass") })
    }

    // ------------------------------------------------------------------ modes & routines

    @Test
    fun temporaryModeRevertsWhenItsTimeIsUp() {
        var now = 1_000_000L
        val modes = ModeManager(Prefs(app)) { now }
        modes.switch("travel")
        modes.startTemporary("focus", 25)
        assertEquals("focus", modes.active.value.id)
        now += 24 * 60_000L
        assertFalse(modes.tick())
        now += 2 * 60_000L
        assertTrue(modes.tick())
        assertEquals("travel", modes.active.value.id)

        modes.switch("focus") // switched to permanently: "focus off" returns to where we were
        modes.stopFocus()
        assertFalse(modes.active.value.blockDistracting)
    }

    @Test
    fun routinesFireOncePerDayAndAiRoutinesWaitForConsent() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 9, 5, 0) } // a Monday, 09:05
        var now = cal.timeInMillis
        val modes = ModeManager(Prefs(app)) { now }
        val r = Routine(id = "r1", name = "Workday", hour = 9, minute = 0, action = RoutineAction.SwitchMode("work"),
            days = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY))
        modes.saveRoutine(r)
        assertEquals(listOf("r1"), modes.takeDueRoutines().map { it.id })
        assertTrue("at most once a day", modes.takeDueRoutines().isEmpty())
        now += 3 * 3600_000L
        assertTrue("past the catch-up window", modes.takeDueRoutines().isEmpty())

        app.installFakeProvider()
        val ask = Routine(id = "r2", name = "Morning brief", hour = 7, minute = 0,
            action = RoutineAction.AskSolis("Give me my brief", allowAi = false, budgetUsd = 0.03))
        val before = app.solis.engine.threads.value.size
        app.solis.runRoutine(ask)
        assertEquals("no AI started without consent", before, app.solis.engine.threads.value.size)
        assertEquals(listOf("r2"), app.solis.pendingAsks.value.map { it.id })
        app.solis.confirmAsk(ask)
        val t = app.solis.engine.threads.value.first()
        assertEquals("routine:r2", t.origin)
        assertEquals(0.03, t.permissions.maxCostUsd, 1e-9)
        assertTrue(app.solis.pendingAsks.value.isEmpty())
    }
}
