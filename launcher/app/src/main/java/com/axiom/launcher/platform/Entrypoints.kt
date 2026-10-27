package com.axiom.launcher.platform

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.axiom.launcher.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share → Solis" from any app: text, links, images and PDFs become the start of a Solis task. Shared files
 * are copied into Axiom's cache (the read grant only lasts as long as this activity), then the launcher
 * opens on the Solis page with the content attached. Nothing is sent anywhere until the user sends it.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        lifecycleScope.launch {
            val path = uri?.let { withContext(Dispatchers.IO) { runCatching { copy(it) }.getOrNull() } }
            startActivity(
                Intent(this@ShareActivity, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SOLIS)
                    .putExtra(MainActivity.EXTRA_SHARED_TEXT, text)
                    .putExtra(MainActivity.EXTRA_SHARED_FILE, path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    private fun copy(uri: Uri): String? {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val size = c.getLong(1)
            if (size > MAX_BYTES) return null
            c.getString(0)
        } ?: "shared"
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "shared" }
        val out = File(dir, "${System.currentTimeMillis()}-$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { out.delete(); return null }
                    o.write(buf, 0, n)
                }
            }
        } ?: return null
        return out.path
    }

    companion object {
        private const val MAX_BYTES = 25L * 1024 * 1024
    }
}

/** Quick Settings tile: ask Solis by voice from anywhere. */
class SolisTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SOLIS)
            .putExtra(MainActivity.EXTRA_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
