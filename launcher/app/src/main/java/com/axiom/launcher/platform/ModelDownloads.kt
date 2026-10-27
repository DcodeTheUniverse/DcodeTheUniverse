package com.axiom.launcher.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.R
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class NotEnoughSpace(val needed: Long, val free: Long) : IOException("Needs ${OnDeviceCatalog.size(needed)} free, ${OnDeviceCatalog.size(free)} available")

class ChecksumMismatch : IOException("The downloaded file doesn't match the published checksum, so it was deleted")

/**
 * Downloads a model file: resumes where it stopped (multi-gigabyte files on phone networks), refuses to start
 * without room to spare, and only keeps the file once its size and SHA-256 match the pinned values.
 */
class ModelFetcher(private val http: OkHttpClient, private val freeBytes: (File) -> Long = { runCatching { StatFs(it.path).availableBytes }.getOrDefault(Long.MAX_VALUE) }) {

    suspend fun fetch(url: String, target: File, size: Long, sha256: String, margin: Long = OnDeviceCatalog.STORAGE_MARGIN, onProgress: suspend (done: Long, total: Long, verifying: Boolean) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > size) part.delete()
        val free = freeBytes(target.parentFile ?: target)
        val needed = size - part.length() + margin
        if (free < needed) throw NotEnoughSpace(needed, free)

        var tries = 0
        while (part.length() < size) {
            coroutineContext.ensureActive()
            val have = part.length()
            val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { resp ->
                val append = when {
                    resp.code == 206 -> true
                    resp.code == 200 -> false
                    resp.code == 416 && have == size -> return@use
                    else -> throw IOException("Download failed: HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("Empty response")
                body.byteStream().use { input -> copy(input, part, append, size, onProgress) }
            }
            if (part.length() == have && ++tries >= 3) throw IOException("The download stopped making progress")
        }
        onProgress(size, size, true)
        if (part.length() != size || !sha256(part).equals(sha256, ignoreCase = true)) {
            part.delete()
            throw ChecksumMismatch()
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't move the finished file into place")
        target
    }

    private suspend fun copy(input: InputStream, part: File, append: Boolean, size: Long, onProgress: suspend (Long, Long, Boolean) -> Unit) {
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(256 * 1024)
            var done = if (append) part.length() else 0L
            var lastReport = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - lastReport > 500) { lastReport = now; onProgress(done, size, false) }
            }
            out.fd.sync()
        }
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Downloads a model in the background, as a visible download (progress notification with Cancel) that keeps
 * going if the user leaves Axiom. Wi-Fi only unless the user allowed mobile data; retried with back-off when
 * the connection drops, resuming the partial file.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val model = OnDeviceCatalog.byId(inputData.getString(KEY_MODEL)) ?: return Result.failure(workDataOf(KEY_ERROR to "Unknown model"))
        val host = (applicationContext as AxiomApp).solis
        runCatching { setForeground(foreground(model.name, 0, model.sizeBytes, false)) }
        return try {
            // A short read timeout so Cancel and lost connections are noticed quickly (the shared client waits minutes for slow models).
            val http = host.http.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            ModelFetcher(http).fetch(model.url, host.onDevice.fileFor(model), model.sizeBytes, model.sha256) { done, total, verifying ->
                setProgress(workDataOf(KEY_MODEL to model.id, KEY_DONE to done, KEY_TOTAL to total, KEY_VERIFYING to verifying))
                runCatching { setForeground(foreground(model.name, done, total, verifying)) }
            }
            host.onDevice.onDownloaded(model)
            Result.success(workDataOf(KEY_MODEL to model.id))
        } catch (e: NotEnoughSpace) {
            Result.failure(workDataOf(KEY_MODEL to model.id, KEY_ERROR to (e.message ?: "Not enough space")))
        } catch (e: ChecksumMismatch) {
            Result.failure(workDataOf(KEY_MODEL to model.id, KEY_ERROR to (e.message ?: "Checksum mismatch")))
        } catch (e: IOException) {
            if (runAttemptCount < 6) Result.retry() else Result.failure(workDataOf(KEY_MODEL to model.id, KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = OnDeviceCatalog.byId(inputData.getString(KEY_MODEL))
        return foreground(model?.name ?: "model", 0, model?.sizeBytes ?: 0, false)
    }

    private fun foreground(name: String, done: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "On-device AI downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a model for Solis downloads to the phone"
            })
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(if (verifying) "Checking $name" else "Downloading $name")
            .setContentText(if (verifying) "Making sure the file is intact" else "${OnDeviceCatalog.size(done)} of ${OnDeviceCatalog.size(total)}")
            .setProgress(100, pct, verifying || total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", WorkManager.getInstance(ctx).createCancelPendingIntent(id)).build())
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val NAME = "model-download"
        const val CHANNEL = "models"
        private const val NOTIFICATION_ID = 0x0A13
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"

        fun start(context: Context, modelId: String, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL to modelId))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
