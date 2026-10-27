package com.axiom.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.platform.LocalModelService
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.ondevice.GemmaTemplate
import com.axiom.launcher.solis.ondevice.LocalEvent
import com.axiom.launcher.solis.ondevice.LocalMessage
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.LocalStats
import com.axiom.launcher.solis.ondevice.LocalToolCall
import com.axiom.launcher.solis.ondevice.ModelRuntime
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import com.axiom.launcher.solis.ondevice.OnDeviceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scripted stand-in for LiteRT-LM inside the model service (the real runtime is checked against the real model separately). */
class FakeRuntime(var script: (LocalRequest, Int) -> List<LocalEvent> = { _, _ -> listOf(LocalEvent.Text("ok"), LocalEvent.Done(LocalStats(decodeTokens = 9, backend = "gpu"))) }) : ModelRuntime {
    val requests: MutableList<LocalRequest> = Collections.synchronizedList(ArrayList())
    val cancels = AtomicInteger()
    val unloads = AtomicInteger()
    @Volatile var hang = false
    @Volatile var loadingBackend = "gpu"
    private var release = CountDownLatch(1)

    override fun generate(req: LocalRequest, emit: (LocalEvent) -> Unit, onState: (String, String) -> Unit) {
        val n = requests.size
        requests += req
        onState("loading", loadingBackend)
        if (hang) {
            release.await(20, TimeUnit.SECONDS)
            release = CountDownLatch(1)
            emit(LocalEvent.Failed("CANCELLED", "Stopped"))
            return
        }
        onState("ready", loadingBackend)
        script(req, n).forEach(emit)
    }

    override fun prepare(req: LocalRequest, onState: (String, String) -> Unit): Long { onState("ready", "gpu"); return 1234 }

    override fun cancel() { cancels.incrementAndGet(); release.countDown() }

    override fun inspect(path: String): JSONObject = JSONObject().put("type", "LLM").put("vision", true).put("maxContext", 32000)

    override fun unload() { unloads.incrementAndGet() }
}

/** The on-device model through the real app wiring: binder client ↔ model service ↔ runtime, provider, engine, settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnDeviceAppTest {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()
    private val runtime = FakeRuntime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val e4b = OnDeviceCatalog.GEMMA4_E4B

    @Before
    fun bindFakeService() {
        LocalModelService.runtimeFactory = { runtime }
        val service = Robolectric.buildService(LocalModelService::class.java).create().get()
        shadowOf(app as Application).setComponentNameAndServiceForBindService(ComponentName(app, LocalModelService::class.java), service.onBind(Intent()))
    }

    @After
    fun cleanup() {
        LocalModelService.runtimeFactory = null
        scope.cancel()
    }

    /** Puts a (tiny) stand-in for Gemma 4 E4B where the model manager looks for it. */
    private fun installE4b() {
        val od = app.solis.onDevice
        File(od.dir, e4b.file).writeText("not really a model")
        File(od.dir, e4b.file + ".json").writeText(JSONObject().put("id", e4b.id).put("name", e4b.name).put("source", "download")
            .put("vision", true).put("maxContext", 32768).toString())
        od.rescan()
        app.solis.refreshPhoneModel()
        idleUntil("phone model listed") { app.solis.registry.catalog.all().any { it.provider == OnDeviceProvider.ID } }
    }

    private fun request(id: String, text: String = "hi") = LocalRequest(id, e4b.id, "/m", "auto", 8192, "sys", listOf(LocalMessage("user", text)))

    @Test
    fun callsStreamAcrossTheProcessBoundary() {
        runtime.script = { _, _ -> listOf(LocalEvent.Thought("hm"), LocalEvent.Text("Par"), LocalEvent.Text("is"), LocalEvent.Call(LocalToolCall("open_app", """{"name":"Maps"}""")),
            LocalEvent.Done(LocalStats(prefillTokens = 30, decodeTokens = 12, decodeTps = 18.5, backend = "gpu", finish = "tool_calls"))) }
        val client = app.solis.onDevice.client
        val events = scope.async { client.generate(request("r1")).toList() }
        idleUntil("events") { events.isCompleted }
        val list = kotlinx.coroutines.runBlocking { events.await() }
        assertEquals(listOf("hm"), list.filterIsInstance<LocalEvent.Thought>().map { it.text })
        assertEquals("Paris", list.filterIsInstance<LocalEvent.Text>().joinToString("") { it.text })
        assertEquals("open_app", list.filterIsInstance<LocalEvent.Call>().single().call.name)
        assertEquals(18.5, (list.last() as LocalEvent.Done).stats.decodeTps, 0.0)
        assertEquals("the service read the request from the file the client wrote", "r1", runtime.requests.single().id)
        assertTrue("request files are cleaned up", File(app.cacheDir, "ondevice").listFiles()!!.none { it.name.startsWith("req-") })
        assertEquals("ready", client.status.value.state)
        assertEquals("gpu", client.status.value.backend)
    }

    @Test
    fun stoppingTheCallerStopsTheModel() {
        runtime.hang = true
        val client = app.solis.onDevice.client
        val job = scope.launch { client.generate(request("r1")).toList() }
        idleUntil("call started") { runtime.requests.size == 1 }
        job.cancel()
        idleUntil("model told to stop") { runtime.cancels.get() == 1 }
        // The next call works normally.
        runtime.hang = false
        val next = scope.async { client.generate(request("r2")).toList() }
        idleUntil("second call") { next.isCompleted }
        assertTrue(kotlinx.coroutines.runBlocking { next.await() }.last() is LocalEvent.Done)
    }

    @Test
    fun aModelProcessCrashFailsTheCallAndMovesThatModelToTheCpu() {
        installE4b()
        runtime.hang = true
        val od = app.solis.onDevice
        assertEquals("auto", od.config()!!.backend)
        val call = scope.async { runCatching { app.solis.phoneModel.stream(ChatRequest(e4b.id, "s", listOf(ChatMessage.user("hi")))).toList() } }
        idleUntil("loading on the GPU") { od.client.status.value.state == "loading" && od.client.status.value.backend == "gpu" }
        // The process dies while the GPU loads the model (what a driver crash looks like from the launcher).
        val conn = shadowOf(app as Application).boundServiceConnections.single()
        conn.onServiceDisconnected(ComponentName(app, LocalModelService::class.java))
        idleUntil("call failed") { call.isCompleted }
        val e = kotlinx.coroutines.runBlocking { call.await() }.exceptionOrNull() as ProviderException
        assertEquals(ErrorKind.UNAVAILABLE, e.kind)
        assertTrue(od.gpuBlocked(e4b.id))
        assertEquals("next time it runs on the CPU", "cpu", od.config()!!.backend)
        assertEquals("crashed", od.client.status.value.state)
        // A new Android build (driver update) tries the GPU again; so does the user's "Try the GPU again".
        assertTrue(app.solis.onDevice.gpuBlockReason(e4b.id)!!.isNotBlank())
        app.core.prefs.onDeviceGpuBlocked = mapOf(e4b.id to "other-build|old crash")
        assertFalse(od.gpuBlocked(e4b.id))
        od.clearGpuBlock(e4b.id)
        assertTrue(app.core.prefs.onDeviceGpuBlocked.isEmpty())
    }

    @Test
    fun openingSolisLoadsThePhoneModelAheadOfTheFirstQuestion() {
        val od = app.solis.onDevice
        val idle = scope.async { od.warm() }
        idleUntil("nothing to warm") { idle.isCompleted }
        assertEquals("no model installed: nothing loads", "off", od.client.status.value.state)
        installE4b()
        val warm = scope.async { od.warm() }
        idleUntil("warmed") { warm.isCompleted }
        assertEquals("ready", od.client.status.value.state)
        assertEquals("gpu", od.client.status.value.backend)
        assertTrue("warming answers nothing", runtime.requests.isEmpty())
    }

    @Test
    fun managerTurnsFilesAndSettingsIntoWhatSolisRuns() {
        val od = app.solis.onDevice
        assertNull("nothing installed yet", od.config())
        installE4b()
        val c = od.config()!!
        assertEquals(e4b.id, c.modelId)
        assertEquals(GemmaTemplate.ID, c.template)
        assertTrue(c.tools && c.vision && c.thinking)
        assertEquals(8192, c.contextTokens)
        assertTrue(app.solis.anyProvider)

        app.core.prefs.onDeviceBackend = "cpu"
        app.core.prefs.onDeviceThink = false
        app.core.prefs.onDeviceContext = 16384
        with(od.config()!!) { assertEquals("cpu", backend); assertFalse(thinking); assertEquals(16384, contextTokens) }
        app.core.prefs.onDeviceContext = 999_999
        assertEquals("never more than the model holds", 32768, od.config()!!.contextTokens)
        app.core.prefs.onDeviceOn = false
        assertNull(od.config())
        app.core.prefs.onDeviceOn = true

        // Deleting removes the file, its note and LiteRT-LM's caches for it.
        val cache = File(app.cacheDir, LocalModelService.CACHE_DIR).apply { mkdirs() }
        File(cache, e4b.file + "_123_456.xnnpack_cache").writeText("cache")
        File(cache, "other.litertlm_1_2.xnnpack_cache").writeText("other")
        od.delete(e4b.id)
        assertTrue(od.installed.value.isEmpty())
        assertFalse(File(od.dir, e4b.file).exists() || File(od.dir, e4b.file + ".json").exists())
        assertEquals(listOf("other.litertlm_1_2.xnnpack_cache"), cache.listFiles()!!.map { it.name })
        assertNull(od.config())
    }

    @Test
    fun importedFilesAreCheckedInTheModelProcess() {
        val src = File(app.cacheDir, "my-phi.litertlm").apply { writeText("a custom model") }
        val od = app.solis.onDevice
        val m = scope.async { od.import(Uri.fromFile(src)) }
        idleUntil("import") { m.isCompleted }
        val installed = kotlinx.coroutines.runBlocking { m.await() }
        assertTrue(installed.id.startsWith("local/"))
        assertNull("not one of Axiom's models", installed.catalog)
        val c = od.config()!!
        assertFalse("unknown models answer but don't drive the phone's tools", c.tools)
        assertNull("they keep their own chat format", c.template)
        assertTrue(installed.vision)

        // Something that isn't a .litertlm file is refused before copying.
        val bad = scope.async { runCatching { od.import(Uri.fromFile(File(app.cacheDir, "notes.txt").apply { writeText("x") })) } }
        idleUntil("refused") { bad.isCompleted }
        assertTrue(kotlinx.coroutines.runBlocking { bad.await() }.exceptionOrNull()!!.message!!.contains(".litertlm"))
    }

    @Test
    fun aQuestionRunsOnThePhoneThroughTheWholeApp() {
        installE4b()
        app.core.prefs.onDeviceUse = "always"
        app.solis.invalidateSettings()
        runtime.script = { req, n ->
            when {
                req.tools.isEmpty() && req.jsonMode -> listOf(LocalEvent.Text("""{"tier":"T1","confidence":0.9,"steps":[{"id":"s1","goal":"Answer"}]}"""), LocalEvent.Done(LocalStats(decodeTokens = 20, backend = "gpu")))
                req.messages.last().role == "tool" -> listOf(LocalEvent.Text("Timer set for 12 minutes."), LocalEvent.Done(LocalStats(decodeTokens = 9, backend = "gpu")))
                else -> listOf(LocalEvent.Call(LocalToolCall("set_timer", """{"seconds":720,"label":"pasta"}""")), LocalEvent.Done(LocalStats(decodeTokens = 20, backend = "gpu", finish = "tool_calls")))
            }
        }
        val id = (app.solis.submit("Set a pasta timer for 12 minutes and tell me when it's set please") as SubmitResult.Accepted).threadId
        idleUntil("answered on the phone", 20_000) { app.solis.engine.thread(id)?.status == ThreadStatus.DONE }
        assertEquals("Timer set for 12 minutes.", app.solis.engine.thread(id)!!.lastResult)

        val first = runtime.requests.first { it.tools.isNotEmpty() }
        assertEquals(GemmaTemplate.ID, first.template)
        assertTrue(first.system.contains(GemmaTemplate.TOOL_REPLY_RULE))
        assertTrue("lean tools: the clock is in", first.tools.any { it.name == "set_timer" })
        assertTrue("…coding and task spawning are not", first.tools.none { it.name.startsWith("code_") || it.name == "start_task" })
        val second = runtime.requests.last()
        assertEquals("the tool result went back to the model", "tool", second.messages.last().role)
        assertEquals("set_timer", second.messages.last().toolResults.single().name)
        assertTrue(app.solis.engine.receipts(id).all { it.onDevice })
        assertEquals(0.0, app.solis.engine.thread(id)!!.usage.costUsd, 0.0)
    }
}
