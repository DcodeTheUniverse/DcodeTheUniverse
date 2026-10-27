package com.axiom.launcher.platform

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.axiom.launcher.solis.ondevice.GemmaTemplate
import com.axiom.launcher.solis.ondevice.LiteRtRuntime
import com.axiom.launcher.solis.ondevice.LocalEvent
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.ModelRuntime
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hosts the on-device model in its own process (":llm"). Its gigabytes of memory, and any crash in a GPU
 * driver, stay away from the home screen: if the system needs memory it kills this process, not the launcher.
 * The launcher binds while it needs the model and unbinds when idle, which frees it. One call runs at a time;
 * the rest wait their turn.
 */
class LocalModelService : Service() {

    private lateinit var runtime: ModelRuntime
    private lateinit var worker: ExecutorService
    private lateinit var messenger: Messenger
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var current: String? = null
    @Volatile private var listener: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        val templateCache = HashMap<String, String?>()
        runtime = runtimeFactory?.invoke(this) ?: LiteRtRuntime(
            cacheDir = File(cacheDir, CACHE_DIR),
            templates = { id -> templateCache.getOrPut(id) { if (id == GemmaTemplate.ID) runCatching { assets.open(GemmaTemplate.ASSET).bufferedReader().use { it.readText() } }.getOrNull() else null } },
            cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            log = { Log.i(TAG, it) },
        )
        worker = Executors.newSingleThreadExecutor { r -> Thread(r, "solis-llm") }
        messenger = Messenger(Handler(Looper.getMainLooper()) { m -> handle(m); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Nobody needs the model any more: stop whatever runs and give the memory back.
        current?.let { cancelled += it; runtime.cancel() }
        worker.execute { runtime.unload() }
        stopSelf()
        return false
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && current == null) worker.execute { runtime.unload(); state("idle", "") }
    }

    override fun onDestroy() {
        current?.let { runtime.cancel() }
        worker.execute { runtime.unload() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun handle(m: Message) {
        val d = m.data
        m.replyTo?.let { listener = it }
        when (m.what) {
            MSG_GENERATE, MSG_PREPARE -> {
                val id = d.getString(K_ID) ?: return
                val path = d.getString(K_PATH) ?: return
                val reply = m.replyTo ?: return
                val prepareOnly = m.what == MSG_PREPARE
                worker.execute { run(id, File(path), reply, prepareOnly) }
            }
            MSG_CANCEL -> d.getString(K_ID)?.let { id -> cancelled += id; if (current == id) runtime.cancel() }
            MSG_INSPECT -> {
                val id = d.getString(K_ID) ?: return
                val path = d.getString(K_PATH) ?: return
                val reply = m.replyTo ?: return
                worker.execute {
                    runCatching { runtime.inspect(path) }
                        .onSuccess { send(reply, EV_INFO, Bundle().apply { putString(K_ID, id); putString(K_JSON, it.toString()) }) }
                        .onFailure { send(reply, failed(id, "BAD_REQUEST", "Not a model Axiom can run: ${it.message ?: it.javaClass.simpleName}")) }
                }
            }
            MSG_UNLOAD -> worker.execute { if (current == null) { runtime.unload(); state("idle", "") } }
        }
    }

    private fun run(id: String, file: File, reply: Messenger, prepareOnly: Boolean) {
        if (cancelled.remove(id)) { send(reply, failed(id, "CANCELLED", "Stopped")); return }
        val req = runCatching { LocalRequest.fromJson(JSONObject(file.readText())) }.getOrElse {
            send(reply, failed(id, "BAD_REQUEST", "Couldn't read the request: ${it.message}")); return
        }
        current = id
        try {
            if (prepareOnly) {
                val ms = runCatching { runtime.prepare(req) { s, detail -> state(s, detail) } }
                    .getOrElse { send(reply, failed(id, "UNAVAILABLE", "Couldn't load the model: ${it.message ?: it.javaClass.simpleName}")); return }
                send(reply, EV_DONE, Bundle().apply { putString(K_ID, id); putString(K_JSON, JSONObject().put("load", ms).toString()) })
            } else {
                runtime.generate(req, { ev -> if (!send(reply, message(id, ev))) runtime.cancel() }) { s, detail -> state(s, detail) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "model call failed", t)
            send(reply, failed(id, "UNKNOWN", t.message ?: t.javaClass.simpleName))
        } finally {
            current = null
            cancelled.remove(id)
        }
    }

    /** Tells the launcher what the model is doing ("loading" on gpu, "gpu_failed", "ready", "idle"). */
    private fun state(s: String, detail: String) {
        listener?.let { send(it, EV_STATE, Bundle().apply { putString(K_STATE, s); putString(K_DETAIL, detail) }) }
    }

    private fun message(id: String, ev: LocalEvent): Message = when (ev) {
        is LocalEvent.Text -> msg(EV_TEXT, Bundle().apply { putString(K_ID, id); putString(K_TEXT, ev.text) })
        is LocalEvent.Thought -> msg(EV_THOUGHT, Bundle().apply { putString(K_ID, id); putString(K_TEXT, ev.text) })
        is LocalEvent.Call -> msg(EV_CALL, Bundle().apply { putString(K_ID, id); putString(K_NAME, ev.call.name); putString(K_ARGS, ev.call.argsJson) })
        is LocalEvent.Done -> msg(EV_DONE, Bundle().apply { putString(K_ID, id); putString(K_JSON, ev.stats.toJson().toString()) })
        is LocalEvent.Failed -> failed(id, ev.kind, ev.message)
    }

    private fun failed(id: String, kind: String, text: String) = msg(EV_FAILED, Bundle().apply { putString(K_ID, id); putString(K_KIND, kind); putString(K_TEXT, text) })

    private fun msg(what: Int, data: Bundle): Message = Message.obtain(null, what).also { it.data = data }

    private fun send(to: Messenger, what: Int, data: Bundle) = send(to, msg(what, data))

    /** False when the launcher is gone (then the running call is stopped). */
    private fun send(to: Messenger, m: Message): Boolean = try {
        to.send(m); true
    } catch (e: RemoteException) {
        false
    }

    companion object {
        private const val TAG = "SolisLocal"
        const val CACHE_DIR = "litertlm"

        const val MSG_GENERATE = 1
        const val MSG_CANCEL = 2
        const val MSG_INSPECT = 3
        const val MSG_UNLOAD = 4
        const val MSG_PREPARE = 5

        const val EV_TEXT = 101
        const val EV_THOUGHT = 102
        const val EV_CALL = 103
        const val EV_DONE = 104
        const val EV_FAILED = 105
        const val EV_STATE = 106
        const val EV_INFO = 107

        const val K_ID = "id"
        const val K_PATH = "path"
        const val K_TEXT = "text"
        const val K_NAME = "name"
        const val K_ARGS = "args"
        const val K_JSON = "json"
        const val K_KIND = "kind"
        const val K_STATE = "state"
        const val K_DETAIL = "detail"

        /** Tests swap the LiteRT-LM runtime for a scripted one. */
        @Volatile var runtimeFactory: ((Context) -> ModelRuntime)? = null
    }
}
