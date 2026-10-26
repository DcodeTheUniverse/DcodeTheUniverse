package com.axiom.launcher.solis

import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.memory.Space
import org.junit.Test

class DebugTest {
    private fun dump(rig: Rig, id: String, p: FakeProvider) {
        println("STATUS ${rig.thread(id).status} blocker=${rig.thread(id).blocker}")
        p.requests.forEachIndexed { i, r -> println("REQ $i model=${r.model} sys=${r.system.take(30)} user=${r.messages.lastOrNull()?.text?.take(80)?.replace("\n"," ")}") }
        rig.engine.transcript(id).value.forEach { println("  ${it.kind} | ${it.text.take(160)}") }
        println("errors=" + rig.errors)
    }

    @Test
    fun fallback() {
        val p = FakeProvider(script = { _, n ->
            if (n == 0) Script.Fail(ProviderException(ErrorKind.UNAVAILABLE, "upstream 503", 503)) else Script.text("Recovered answer")
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = (rig.engine.submit("What's a good name for a grey cat?", Space.PERSONAL, "personal") as SubmitResult.Accepted).threadId
        runCatching { awaitStatus(rig, id, ThreadStatus.DONE, ThreadStatus.FAILED, ThreadStatus.BLOCKED, timeoutMs = 4000) }
        dump(rig, id, p)
    }

    @Test
    fun alarm() {
        val p1 = FakeProvider(script = { _, n -> if (n == 0) Script.tool("set_alarm", """{"minute":30,"hour":6}""") else Script.Hang })
        val rig1 = Rig(providers = listOf(p1)).start()
        val id = (rig1.engine.submit("Set an alarm for 6:30 then tell me when I should sleep", Space.PERSONAL, "personal") as SubmitResult.Accepted).threadId
        Thread.sleep(1500)
        dump(rig1, id, p1)
        println("alarmRuns=${rig1.tools.alarmRuns.get()}")
    }
}
