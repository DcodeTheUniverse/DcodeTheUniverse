package com.axiom.launcher.platform

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.axiom.launcher.data.Prefs
import com.axiom.launcher.solis.ondevice.DeviceSpec
import com.axiom.launcher.solis.ondevice.LocalMessage
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.LocalStats
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import com.axiom.launcher.solis.ondevice.OnDeviceConfig
import com.axiom.launcher.solis.ondevice.OnDeviceModel
import com.axiom.launcher.solis.ondevice.LocalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A model file on the phone that Solis can run. */
data class InstalledModel(
    val id: String,
    val name: String,
    val file: File,
    val sizeBytes: Long,
    /** The catalog entry when this is one of the models Axiom knows (downloaded, or imported with a matching checksum). */
    val catalog: OnDeviceModel?,
    val vision: Boolean,
    val maxContext: Int,
    val installedAt: Long,
    /** "download" or "import". */
    val source: String,
)

/** Where a download stands, from WorkManager. */
data class ModelDownload(
    val modelId: String,
    /** "waiting" (for Wi-Fi or storage), "running", "verifying", "done", "failed", "cancelled". */
    val state: String,
    val done: Long,
    val total: Long,
    val error: String? = null,
)

/**
 * Owns everything about models on the phone: which are installed, which one Solis uses and with what settings,
 * downloads and imports, and the [LocalModelClient] that runs them in their own process.
 */
class OnDeviceManager(private val app: Context, private val prefs: Prefs) {
    val dir: File = File(app.filesDir, "models").apply { mkdirs() }

    val client = LocalModelClient(app, keepLoadedMs = { prefs.onDeviceKeepMin.coerceIn(1, 60) * 60_000L }, onGpuFailed = ::blockGpu)

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _importing = MutableStateFlow<Pair<Long, Long>?>(null)
    /** Bytes copied / total while a model file is being imported. */
    val importing: StateFlow<Pair<Long, Long>?> = _importing.asStateFlow()

    private val _stats = MutableStateFlow(readStats())
    /** Latest measured call per model id: speed, backend, load time. */
    val stats: StateFlow<Map<String, LocalStats>> = _stats.asStateFlow()

    /** Called when the installed models or their settings change, so routing sees it. */
    var onChanged: () -> Unit = {}

    init {
        rescan()
    }

    // ------------------------------------------------------------------ the phone

    /** Screenshots and tests pretend to be a particular phone, or a download in progress. */
    @Volatile internal var debugSpec: DeviceSpec? = null
    @Volatile internal var debugDownload: ModelDownload? = null

    fun deviceSpec(): DeviceSpec {
        debugSpec?.let { return it }
        val mi = ActivityManager.MemoryInfo().also { runCatching { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else Build.HARDWARE.orEmpty()
        return DeviceSpec(mi.totalMem, free, Build.SUPPORTED_ABIS.contains("arm64-v8a"), soc, Build.MODEL.orEmpty())
    }

    /** "Galaxy S24+ · Snapdragon 8 Gen 3 · 12 GB RAM" */
    fun deviceLine(d: DeviceSpec = deviceSpec()): String = listOfNotNull(
        phoneName(Build.MANUFACTURER.orEmpty(), d.model),
        OnDeviceCatalog.chipName(d.soc) ?: d.soc.takeIf { it.isNotBlank() && it != "unknown" },
        "${marketingRam(d.totalRamBytes)} GB RAM",
    ).joinToString(" · ")

    // ------------------------------------------------------------------ what Solis runs

    fun active(): InstalledModel? {
        val list = _installed.value
        return list.firstOrNull { it.id == prefs.onDeviceModel } ?: list.firstOrNull()
    }

    /** The model and settings the provider uses, or null when on-device AI is off or nothing is installed. */
    fun config(): OnDeviceConfig? {
        if (!prefs.onDeviceOn) return null
        val m = active() ?: return null
        val blocked = gpuBlocked(m.id)
        val backend = when (prefs.onDeviceBackend) {
            "cpu" -> "cpu"
            "gpu" -> if (blocked) "cpu" else "gpu"
            else -> if (blocked) "cpu" else "auto"
        }
        val c = m.catalog
        return OnDeviceConfig(
            modelId = m.id, name = m.name, path = m.file.absolutePath, backend = backend,
            contextTokens = prefs.onDeviceContext.coerceIn(2048, m.maxContext.coerceAtLeast(2048)),
            template = c?.template, vision = m.vision, tools = c?.tools ?: false,
            thinking = (c?.thinking ?: false) && prefs.onDeviceThink, speculative = true, quality = c?.quality ?: 0.3,
        )
    }

    fun setActive(id: String) {
        prefs.onDeviceModel = id
        client.unload()
        onChanged()
    }

    fun fileFor(m: OnDeviceModel): File = File(dir, m.file)

    // ------------------------------------------------------------------ downloads

    fun download(m: OnDeviceModel, wifiOnly: Boolean = prefs.onDeviceWifiOnly) = ModelDownloadWorker.start(app, m.id, wifiOnly)

    fun cancelDownload() = ModelDownloadWorker.cancel(app)

    /** The current (or last) download, live. */
    fun downloads(): Flow<ModelDownload?> {
        debugDownload?.let { return kotlinx.coroutines.flow.flowOf(it) }
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return kotlinx.coroutines.flow.flowOf(null)
        return downloads(wm)
    }

    private fun downloads(wm: WorkManager): Flow<ModelDownload?> = wm.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.NAME).map { infos ->
        val w = infos.lastOrNull() ?: return@map null
        val model = w.progress.getString(ModelDownloadWorker.KEY_MODEL) ?: w.outputData.getString(ModelDownloadWorker.KEY_MODEL) ?: ""
        val total = w.progress.getLong(ModelDownloadWorker.KEY_TOTAL, OnDeviceCatalog.byId(model)?.sizeBytes ?: 0)
        when (w.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownload(model, "waiting", partialBytes(model), total)
            WorkInfo.State.RUNNING -> ModelDownload(model, if (w.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false)) "verifying" else "running",
                w.progress.getLong(ModelDownloadWorker.KEY_DONE, partialBytes(model)), total)
            WorkInfo.State.SUCCEEDED -> ModelDownload(model, "done", total, total)
            WorkInfo.State.FAILED -> ModelDownload(model, "failed", partialBytes(model), total, w.outputData.getString(ModelDownloadWorker.KEY_ERROR))
            WorkInfo.State.CANCELLED -> ModelDownload(model, "cancelled", partialBytes(model), total)
        }
    }

    private fun partialBytes(modelId: String): Long = OnDeviceCatalog.byId(modelId)?.let { File(dir, it.file + ".part").length() } ?: 0L

    /** Called by the download worker once the file is complete and verified. */
    fun onDownloaded(m: OnDeviceModel) {
        writeMeta(fileFor(m), m.id, m.name, "download", m.vision, m.maxContext)
        if (prefs.onDeviceModel.isBlank() || active() == null) prefs.onDeviceModel = m.id
        rescan()
        onChanged()
    }

    // ------------------------------------------------------------------ import & delete

    /**
     * Copies a `.litertlm` file the user picked into Axiom's storage, checks in the model process that it is a
     * language model, and recognises Axiom's own models by checksum.
     */
    suspend fun import(uri: Uri): InstalledModel = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        // The picker's display name is the reliable one; the URI's last segment covers providers that don't report it.
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "model"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (!name.endsWith(".litertlm", ignoreCase = true)) throw IOException("Pick a .litertlm model file (LiteRT-LM format)")
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (size > 0 && free < size + OnDeviceCatalog.STORAGE_MARGIN) throw NotEnoughSpace(size + OnDeviceCatalog.STORAGE_MARGIN, free)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val part = File(dir, "import-$safe.part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            _importing.value = 0L to size
            (cr.openInputStream(uri) ?: throw IOException("Couldn't open the file")).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        _importing.value = done to size
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val known = OnDeviceCatalog.bySha(sha)
            val target = if (known != null) fileFor(known) else File(dir, safe)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't store the file")
            val info = runCatching { client.inspect(target.absolutePath) }.getOrElse {
                target.delete()
                throw IOException(it.message ?: "Not a model Axiom can run")
            }
            if (info.optString("type") != "LLM") {
                target.delete()
                throw IOException("That file is a ${info.optString("type").lowercase(Locale.ROOT)} model, not a language model")
            }
            val id = known?.id ?: "local/" + safe.removeSuffix(".litertlm").lowercase(Locale.ROOT)
            val display = known?.name ?: safe.removeSuffix(".litertlm").replace('_', ' ').replace('-', ' ')
            writeMeta(target, id, display, "import", info.optBoolean("vision"), info.optInt("maxContext").takeIf { it > 0 } ?: known?.maxContext ?: 8192)
            rescan()
            if (active() == null || prefs.onDeviceModel.isBlank()) prefs.onDeviceModel = id
            onChanged()
            _installed.value.first { it.id == id }
        } finally {
            part.delete()
            _importing.value = null
        }
    }

    fun delete(id: String) {
        val m = _installed.value.firstOrNull { it.id == id } ?: return
        if (active()?.id == id) client.unload()
        m.file.delete()
        meta(m.file).delete()
        // LiteRT-LM's speed-up caches for this file (rebuilt if the model comes back).
        File(app.cacheDir, LocalModelService.CACHE_DIR).listFiles()?.filter { it.name.startsWith(m.file.name) }?.forEach { it.delete() }
        if (prefs.onDeviceModel == id) prefs.onDeviceModel = ""
        rescan()
        onChanged()
    }

    /** Space the model's speed-up cache takes (LiteRT-LM keeps prepared weights to load in a second next time). */
    fun cacheBytes(m: InstalledModel): Long =
        File(app.cacheDir, LocalModelService.CACHE_DIR).listFiles()?.filter { it.name.startsWith(m.file.name) }?.sumOf { it.length() } ?: 0L

    /** Loads the model before the first question (Solis just opened), so that answer doesn't wait for it. */
    suspend fun warm() {
        val c = config() ?: return
        if (client.status.value.state in setOf("loading", "ready", "busy")) return
        runCatching {
            client.prepare(LocalRequest(
                id = "warm${System.currentTimeMillis()}", modelId = c.modelId, modelPath = c.path, backend = c.backend,
                contextTokens = c.contextTokens, system = "", messages = emptyList(), template = c.template, vision = c.vision,
            ))
        }
    }

    // ------------------------------------------------------------------ speed test & stats

    /** Loads the active model and answers a short question, to show the user real numbers for this phone. */
    suspend fun speedTest(): LocalStats {
        val c = config() ?: throw IllegalStateException("No on-device model is set up")
        val req = LocalRequest(
            id = "speed${System.currentTimeMillis()}", modelId = c.modelId, modelPath = c.path, backend = c.backend,
            contextTokens = c.contextTokens, system = "You are Solis, the assistant in a phone launcher. Be brief.",
            messages = listOf(LocalMessage("user", "In two short sentences: what can you help with on this phone, even offline?")),
            maxOutputTokens = 96, template = c.template, vision = c.vision,
        )
        var result: LocalStats? = null
        var error: String? = null
        client.generate(req).collect { ev ->
            when (ev) {
                is LocalEvent.Done -> result = ev.stats
                is LocalEvent.Failed -> error = ev.message
                else -> Unit
            }
        }
        val s = result ?: throw IllegalStateException(error ?: "No answer")
        recordStats(c.modelId, s)
        return s
    }

    fun recordStats(modelId: String, s: LocalStats) {
        // Calls that reused the conversation say little about speed from cold; keep the most informative ones.
        if (s.decodeTokens < 8) return
        val next = _stats.value + (modelId to s)
        _stats.value = next
        prefs.onDeviceStats = JSONObject().apply { next.forEach { (k, v) -> put(k, v.toJson()) } }.toString()
    }

    private fun readStats(): Map<String, LocalStats> = runCatching {
        val o = JSONObject(prefs.onDeviceStats)
        o.keys().asSequence().associateWith { LocalStats.fromJson(o.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------ GPU fallback

    fun gpuBlocked(modelId: String): Boolean = prefs.onDeviceGpuBlocked[modelId]?.substringBefore('|') == Build.FINGERPRINT

    fun gpuBlockReason(modelId: String): String? = prefs.onDeviceGpuBlocked[modelId]?.takeIf { it.substringBefore('|') == Build.FINGERPRINT }?.substringAfter('|')

    private fun blockGpu(reason: String) {
        val id = active()?.id ?: return
        if (prefs.onDeviceBackend == "cpu") return
        prefs.onDeviceGpuBlocked = prefs.onDeviceGpuBlocked + (id to "${Build.FINGERPRINT}|${reason.take(200)}")
        onChanged()
    }

    /** Lets the GPU be tried again (after an OS or driver update, or from settings). */
    fun clearGpuBlock(modelId: String) {
        prefs.onDeviceGpuBlocked = prefs.onDeviceGpuBlocked - modelId
        onChanged()
    }

    // ------------------------------------------------------------------ files

    fun rescan() {
        val list = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm") }.orEmpty().mapNotNull { f ->
            val meta = runCatching { JSONObject(meta(f).readText()) }.getOrNull()
            val known = OnDeviceCatalog.all.firstOrNull { it.file == f.name }
            when {
                meta != null -> InstalledModel(
                    id = meta.getString("id"), name = meta.optString("name", f.name), file = f, sizeBytes = f.length(),
                    catalog = OnDeviceCatalog.byId(meta.getString("id")), vision = meta.optBoolean("vision"),
                    maxContext = meta.optInt("maxContext", 8192), installedAt = meta.optLong("at", f.lastModified()), source = meta.optString("source", "download"),
                )
                // A catalog file without its note (e.g. copied in by hand): trust it only at the right size.
                known != null && f.length() == known.sizeBytes -> InstalledModel(known.id, known.name, f, f.length(), known, known.vision, known.maxContext, f.lastModified(), "download")
                else -> null
            }
        }.sortedByDescending { it.catalog?.quality ?: 0.0 }
        _installed.value = list
    }

    private fun meta(f: File) = File(f.path + ".json")

    private fun writeMeta(f: File, id: String, name: String, source: String, vision: Boolean, maxContext: Int) {
        meta(f).writeText(JSONObject().put("id", id).put("name", name).put("source", source).put("vision", vision)
            .put("maxContext", maxContext).put("at", System.currentTimeMillis()).toString())
    }

    companion object {
        /** A "12 GB" phone reports about 11 GB to apps; round up to the size on the box. */
        fun marketingRam(bytes: Long): Int {
            val gb = bytes / 1e9
            return listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32).firstOrNull { it >= gb } ?: kotlin.math.ceil(gb).toInt()
        }

        /** Samsung model numbers to the names on the box, for the Galaxy phones people ask about. */
        fun phoneName(manufacturer: String, model: String): String? {
            val m = model.uppercase(Locale.ROOT)
            val samsung = mapOf(
                "SM-S921" to "Galaxy S24", "SM-S926" to "Galaxy S24+", "SM-S928" to "Galaxy S24 Ultra", "SM-S721" to "Galaxy S24 FE",
                "SM-S931" to "Galaxy S25", "SM-S936" to "Galaxy S25+", "SM-S938" to "Galaxy S25 Ultra", "SM-S937" to "Galaxy S25 Edge",
                "SM-S941" to "Galaxy S26", "SM-S946" to "Galaxy S26+", "SM-S948" to "Galaxy S26 Ultra",
            )
            samsung.entries.firstOrNull { m.startsWith(it.key) }?.let { return it.value }
            if (model.isBlank()) return null
            return if (manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true)) "${manufacturer.replaceFirstChar { it.uppercase() }} $model" else model
        }
    }
}
