package com.axiom.launcher.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.axiom.launcher.platform.LocalModelService.Companion.EV_CALL
import com.axiom.launcher.platform.LocalModelService.Companion.EV_DONE
import com.axiom.launcher.platform.LocalModelService.Companion.EV_FAILED
import com.axiom.launcher.platform.LocalModelService.Companion.EV_INFO
import com.axiom.launcher.platform.LocalModelService.Companion.EV_STATE
import com.axiom.launcher.platform.LocalModelService.Companion.EV_TEXT
import com.axiom.launcher.platform.LocalModelService.Companion.EV_THOUGHT
import com.axiom.launcher.platform.LocalModelService.Companion.K_ARGS
import com.axiom.launcher.platform.LocalModelService.Companion.K_DETAIL
import com.axiom.launcher.platform.LocalModelService.Companion.K_ID
import com.axiom.launcher.platform.LocalModelService.Companion.K_JSON
import com.axiom.launcher.platform.LocalModelService.Companion.K_KIND
import com.axiom.launcher.platform.LocalModelService.Companion.K_NAME
import com.axiom.launcher.platform.LocalModelService.Companion.K_PATH
import com.axiom.launcher.platform.LocalModelService.Companion.K_STATE
import com.axiom.launcher.platform.LocalModelService.Companion.K_TEXT
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.ondevice.LocalEvent
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.LocalStats
import com.axiom.launcher.solis.ondevice.LocalToolCall
import com.axiom.launcher.solis.ondevice.OnDeviceRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the model process is doing, for settings and the self-test. */
data class LocalModelStatus(
    /** "off" (not loaded), "loading", "ready", "busy" or "crashed". */
    val state: String = "off",
    val backend: String = "",
    val detail: String = "",
    val running: Int = 0,
)

/**
 * The launcher's side of the model process: binds to [LocalModelService] when a call comes in, streams its
 * events back as a Flow, and unbinds after a quiet spell so the memory goes back to the phone. If the
 * process dies (out of memory, a driver crash), calls in flight fail cleanly and the next one starts it again.
 */
class LocalModelClient(
    private val app: Context,
    /** How long the model stays loaded after the last call. */
    private val keepLoadedMs: () -> Long,
    /** The GPU failed to load this model (or crashed while loading it): use the CPU from now on. */
    private val onGpuFailed: (String) -> Unit = {},
) : OnDeviceRuntime {

    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { m -> onEvent(m); true })
    private val pending = ConcurrentHashMap<String, SendChannel<LocalEvent>>()
    private val infos = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val seq = AtomicLong()
    private val dir = File(app.cacheDir, "ondevice").apply { mkdirs() }

    @Volatile private var service: Messenger? = null
    @Volatile private var connecting: CompletableDeferred<Messenger>? = null
    @Volatile private var bound = false

    private val _status = MutableStateFlow(LocalModelStatus())
    val status: StateFlow<LocalModelStatus> = _status.asStateFlow()

    private val idle = Runnable { if (pending.isEmpty() && infos.isEmpty()) unbind() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder ?: return)
            runCatching { binder.linkToDeath({ main.post { died() } }, 0) }
            service = m
            connecting?.complete(m)
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun onBindingDied(name: ComponentName?) = died()
    }

    init {
        // Request files and images from earlier runs (the process may have died mid-call).
        main.post { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 2 * 86_400_000L }?.forEach { it.deleteRecursively() } }
    }

    override fun generate(req: LocalRequest): Flow<LocalEvent> = callbackFlow {
        pending[req.id] = channel
        main.removeCallbacks(idle)
        _status.update { it.copy(running = pending.size) }
        val file = File(dir, "req-${req.id}.json")
        var finished = false
        try {
            withContext(Dispatchers.IO) { file.writeText(req.toJson().toString()) }
            val svc = connect()
            svc.send(Message.obtain(null, LocalModelService.MSG_GENERATE).apply {
                data = Bundle().apply { putString(K_ID, req.id); putString(K_PATH, file.absolutePath) }
                replyTo = incoming
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            finished = true
            pending.remove(req.id)
            channel.trySend(LocalEvent.Failed("UNAVAILABLE", "Couldn't reach the on-device model: ${e.message ?: e.javaClass.simpleName}"))
            channel.close()
        }
        awaitClose {
            // The caller stopped listening (cancelled, timed out) before the model finished: stop it too.
            if (!finished && pending.remove(req.id) != null) send(LocalModelService.MSG_CANCEL, req.id)
            file.delete()
            _status.update { it.copy(running = pending.size) }
            scheduleIdle()
        }
    }.buffer(Channel.UNLIMITED) // events arrive with trySend from the main thread; none may be dropped

    /** Loads the model ahead of time (when the user opens Solis, or for a speed test). Returns the load time. */
    suspend fun prepare(req: LocalRequest): Long {
        var load = 0L
        callbackFlowFor(req, LocalModelService.MSG_PREPARE).collect { ev ->
            when (ev) {
                is LocalEvent.Done -> load = ev.stats.loadMs
                is LocalEvent.Failed -> throw IllegalStateException(ev.message)
                else -> Unit
            }
        }
        return load
    }

    private fun callbackFlowFor(req: LocalRequest, what: Int): Flow<LocalEvent> = callbackFlow {
        pending[req.id] = channel
        main.removeCallbacks(idle)
        val file = File(dir, "req-${req.id}.json")
        try {
            withContext(Dispatchers.IO) { file.writeText(req.toJson().toString()) }
            connect().send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString(K_ID, req.id); putString(K_PATH, file.absolutePath) }
                replyTo = incoming
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            pending.remove(req.id)
            channel.trySend(LocalEvent.Failed("UNAVAILABLE", e.message ?: e.javaClass.simpleName))
            channel.close()
        }
        awaitClose { pending.remove(req.id); file.delete(); scheduleIdle() }
    }.buffer(Channel.UNLIMITED)

    /** Reads a model file's header in the model process (the launcher never loads LiteRT-LM itself). */
    suspend fun inspect(path: String): JSONObject {
        val id = "i${seq.incrementAndGet()}"
        val d = CompletableDeferred<JSONObject>()
        infos[id] = d
        main.removeCallbacks(idle)
        try {
            connect().send(Message.obtain(null, LocalModelService.MSG_INSPECT).apply {
                data = Bundle().apply { putString(K_ID, id); putString(K_PATH, path) }
                replyTo = incoming
            })
            return withTimeout(60_000) { d.await() }
        } finally {
            infos.remove(id)
            scheduleIdle()
        }
    }

    /** Frees the model now (low memory, the user switched models or turned it off). */
    fun unload() {
        if (pending.isEmpty() && infos.isEmpty()) unbind() else send(LocalModelService.MSG_UNLOAD, null)
    }

    /**
     * Called by the app when Android asks for memory back. Leaving the home screen (UI hidden) is not pressure:
     * the model stays for its keep-loaded window so the next question doesn't wait for a reload.
     */
    fun onTrimMemory(level: Int) {
        val pressure = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (pressure && pending.isEmpty() && infos.isEmpty()) unbind()
    }

    /** Writes an image the model process can read (named by content, so a conversation's images are written once). */
    fun saveImage(image: ContentPart.Image): String {
        val bytes = Base64.getDecoder().decode(image.base64)
        val ext = if (image.mediaType.contains("png")) "png" else if (image.mediaType.contains("webp")) "webp" else "jpg"
        val f = File(File(dir, "img").apply { mkdirs() }, "${sha256(bytes).take(24)}.$ext")
        if (!f.exists()) f.writeBytes(bytes)
        return f.absolutePath
    }

    // ------------------------------------------------------------------ binding

    private suspend fun connect(): Messenger {
        service?.let { return it }
        val d = synchronized(this) {
            connecting ?: CompletableDeferred<Messenger>().also { def ->
                connecting = def
                main.post {
                    val ok = runCatching { app.bindService(Intent(app, LocalModelService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                    bound = ok
                    if (!ok) { connecting = null; def.completeExceptionally(IllegalStateException("the model service didn't start")) }
                }
            }
        }
        return withTimeout(30_000) { d.await() }
    }

    private fun unbind() {
        main.removeCallbacks(idle)
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service = null
        connecting = null
        _status.value = LocalModelStatus()
    }

    private fun scheduleIdle() {
        if (pending.isNotEmpty() || infos.isNotEmpty()) return
        main.removeCallbacks(idle)
        main.postDelayed(idle, keepLoadedMs().coerceAtLeast(5_000))
    }

    /** The model process is gone. Calls in flight fail (the router moves on); the next call starts it again. */
    private fun died() {
        if (service == null && connecting == null && !bound) return
        val s = _status.value
        if (s.state == "loading" && s.backend == "gpu") onGpuFailed("the model process stopped while loading on the GPU")
        val crashed = pending.keys.toList()
        crashed.forEach { id -> pending.remove(id)?.let { it.trySend(LocalEvent.Failed("CRASHED", "The on-device model stopped unexpectedly")); it.close() } }
        infos.values.forEach { it.completeExceptionally(IllegalStateException("The on-device model stopped unexpectedly")) }
        unbind()
        _status.value = LocalModelStatus(state = "crashed", detail = if (crashed.isNotEmpty()) "stopped during a call" else "stopped")
    }

    private fun send(what: Int, id: String?) {
        val svc = service ?: return
        try {
            svc.send(Message.obtain(null, what).apply { data = Bundle().apply { id?.let { putString(K_ID, it) } }; replyTo = incoming })
        } catch (_: RemoteException) {
        }
    }

    private fun onEvent(m: Message) {
        val d = m.data
        if (m.what == EV_STATE) {
            val st = d.getString(K_STATE).orEmpty()
            val detail = d.getString(K_DETAIL).orEmpty()
            when (st) {
                "gpu_failed" -> onGpuFailed(detail)
                "loading" -> _status.update { it.copy(state = "loading", backend = detail, detail = "") }
                "ready" -> _status.update { it.copy(state = "ready", backend = detail, detail = "") }
                "idle" -> _status.update { it.copy(state = "off", backend = "") }
            }
            return
        }
        val id = d.getString(K_ID) ?: return
        if (m.what == EV_INFO) { infos[id]?.complete(JSONObject(d.getString(K_JSON) ?: "{}")); return }
        if (m.what == EV_FAILED) infos[id]?.let { it.completeExceptionally(IllegalArgumentException(d.getString(K_TEXT))); return }
        val ch = pending[id] ?: return
        when (m.what) {
            EV_TEXT -> { _status.update { it.copy(state = "busy") }; ch.trySend(LocalEvent.Text(d.getString(K_TEXT).orEmpty())) }
            EV_THOUGHT -> ch.trySend(LocalEvent.Thought(d.getString(K_TEXT).orEmpty()))
            EV_CALL -> ch.trySend(LocalEvent.Call(LocalToolCall(d.getString(K_NAME).orEmpty(), d.getString(K_ARGS) ?: "{}")))
            EV_DONE -> {
                pending.remove(id)
                val stats = runCatching { LocalStats.fromJson(JSONObject(d.getString(K_JSON) ?: "{}")) }.getOrDefault(LocalStats())
                _status.update { it.copy(state = "ready", backend = stats.backend.ifBlank { it.backend }) }
                ch.trySend(LocalEvent.Done(stats))
                ch.close()
            }
            EV_FAILED -> {
                pending.remove(id)
                ch.trySend(LocalEvent.Failed(d.getString(K_KIND) ?: "UNKNOWN", d.getString(K_TEXT).orEmpty()))
                ch.close()
                if (_status.value.state == "busy") _status.update { it.copy(state = "ready") }
            }
        }
    }

    /** Waits until no call is running (for tests and the speed check). */
    suspend fun awaitIdle() { status.first { it.running == 0 } }

    companion object {
        private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
