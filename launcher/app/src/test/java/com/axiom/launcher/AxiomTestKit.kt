package com.axiom.launcher

import com.axiom.launcher.solis.FakeProvider
import com.axiom.launcher.solis.Models
import com.axiom.launcher.solis.Script
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.ToolCall
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Helpers for Robolectric tests that run the real app wiring (AxiomApp → SolisHost → engine → SQLite). */
object AxiomTestKit {
    val fast = Models.m("acme/fast-1", 0.0, 0.0, 45.0, provider = "fake", local = true)
    val deep = Models.m("zeta/deep-2", 0.0, 0.0, 72.0, provider = "fake", local = true, efforts = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH))

    /**
     * Registers a scripted on-device ("local") provider so tasks run without a network, and loads its
     * catalog through the normal refresh path.
     */
    fun AxiomApp.installFakeProvider(script: (ChatRequest, Int) -> Script = demoScript()): FakeProvider {
        runBlocking { solis.engine.awaitStarted() }
        val p = FakeProvider(id = "fake", isLocal = true, script = script, models = listOf(fast, deep))
        solis.registry.register(p)
        runBlocking { solis.registry.refresh("fake") }
        solis.engine.onConditionsChanged()
        return p
    }

    fun firstUser(r: ChatRequest) = r.messages.firstOrNull { it.role == ChatRole.USER }?.text.orEmpty()

    /** Plausible replies for the demo requests used in screenshots and integration tests. */
    fun demoScript(): (ChatRequest, Int) -> Script = { r, _ ->
        val u = firstUser(r)
        val toolRounds = r.messages.count { it.role == ChatRole.TOOL }
        when {
            r.system.startsWith("You are the coordinator") && u.contains("Lisbon") -> Script.text(
                """{"tier":"T2","confidence":0.82,"ambiguity":0.2,"risk":"LOW","domain":"planning","needs":["WEB"],
                   "steps":[{"id":"s1","goal":"Shortlist restaurants, tascas and markets by neighbourhood","dependsOn":[],"tier":"T1","needs":["WEB"]},
                            {"id":"s2","goal":"Find food events and festivals in Lisbon in October","dependsOn":[],"tier":"T1","needs":["WEB"]},
                            {"id":"s3","goal":"Draft a 3-day itinerary from the shortlist and events","dependsOn":["s1","s2"],"tier":"T2","needs":[]}],
                   "clarify":null,"reasons":["three parts","two can run in parallel"]}"""
            )
            r.system.startsWith("You are the coordinator") -> Script.text(
                """{"tier":"T1","confidence":0.86,"ambiguity":0.1,"risk":"LOW","domain":"planning","needs":["TOOLS"],
                   "steps":[{"id":"s1","goal":"Set the alarm and save the reminder note","dependsOn":[],"tier":"T1","needs":["TOOLS"]}],
                   "clarify":null,"reasons":["two phone actions","one step"]}"""
            )
            r.system == SolisEngine.NARRATOR_SYSTEM -> Script.text("Working through your request")
            r.system == SolisEngine.VERIFIER_SYSTEM -> Script.text("""{"verdict":"pass","issues":[],"confidence":0.9}""")
            u.contains("Lisbon") -> Script.Hang
            u.contains("6:30") && toolRounds == 0 -> Script.Reply(listOf(
                LlmEvent.ToolCallReady(ToolCall("c1", "set_alarm", """{"hour":6,"minute":30,"label":"Flight"}""")),
                LlmEvent.ToolCallReady(ToolCall("c2", "note_add", """{"text":"Pack boarding pass + passport"}""")),
                LlmEvent.Usage(420, 60, costUsd = 0.0),
                LlmEvent.Finish(FinishReason.TOOL_CALLS),
            ))
            u.contains("6:30") -> Script.text(
                "Done — alarm set for **6:30** (labelled *Flight*) and a note on your home screen:\n- Pack boarding pass + passport\n\nWant a reminder to leave for the airport too?"
            )
            else -> Script.text("Here's what I found.")
        }
    }

    /** Runs the main looper until [cond] holds; engine work happens on background threads meanwhile. */
    fun idleUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            // Advance the main looper's clock with real time so debounces and delays on Main fire.
            ShadowLooper.idleMainLooper(15, TimeUnit.MILLISECONDS)
            if (cond()) return
            Thread.sleep(15)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(15, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }
    }
}
