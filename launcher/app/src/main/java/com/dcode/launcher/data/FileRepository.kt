package com.dcode.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import java.io.File

/** Files and contacts lookup. File search uses a lazily built on-device name index. */
class FileRepository(private val context: Context) {
    @Volatile private var index: List<FileHit> = emptyList()
    @Volatile private var indexedAt = 0L

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun list(dir: File, showHidden: Boolean = false): List<FileHit> {
        val kids = dir.listFiles() ?: return emptyList()
        return kids.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toHit() }
            .sortedWith(compareBy<FileHit>({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    /** Rebuilds the index at most every 10 minutes. Walks shared storage, skipping app-private dirs. */
    fun ensureIndex(force: Boolean = false) {
        if (!hasAllFilesAccess()) return
        if (!force && index.isNotEmpty() && System.currentTimeMillis() - indexedAt < 10 * 60_000) return
        val out = ArrayList<FileHit>(4096)
        val rootFile = root
        rootFile.walkTopDown()
            .onEnter { d -> d == rootFile || (!d.name.startsWith(".") && !(d.name == "Android" && d.parentFile == rootFile)) }
            .maxDepth(8)
            .filter { it != rootFile && !it.name.startsWith(".") }
            .take(60_000)
            .forEach { out += it.toHit() }
        index = out
        indexedAt = System.currentTimeMillis()
    }

    fun search(query: String, limit: Int = 30): List<FileHit> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        if (hasAllFilesAccess()) {
            ensureIndex()
            return index.asSequence()
                .filter { it.name.lowercase().contains(q) }
                .sortedWith(compareBy<FileHit>({ !it.name.lowercase().startsWith(q) }, { -it.modified }))
                .take(limit)
                .toList()
        }
        return searchMediaStore(q, limit)
    }

    fun recent(limit: Int = 25): List<FileHit> {
        if (hasAllFilesAccess()) {
            ensureIndex()
            return index.asSequence().filter { !it.isDir }.sortedByDescending { it.modified }.take(limit).toList()
        }
        return searchMediaStore("", limit)
    }

    private fun searchMediaStore(q: String, limit: Int): List<FileHit> {
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val sel = if (q.isEmpty()) null else "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = if (q.isEmpty()) null else arrayOf("%$q%")
        val out = ArrayList<FileHit>()
        runCatching {
            context.contentResolver.query(uri, proj, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val path = c.getString(0) ?: continue
                    out += FileHit(path, c.getString(1) ?: File(path).name, false, c.getLong(2), c.getLong(3) * 1000)
                }
            }
        }
        return out
    }

    fun hasContacts(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun searchContacts(query: String, limit: Int = 6): List<ContactHit> {
        if (!hasContacts() || query.isBlank()) return emptyList()
        val out = LinkedHashMap<String, ContactHit>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${query.trim()}%"),
                "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue
                    if (!out.containsKey(name)) out[name] = ContactHit(name, c.getString(1))
                }
            }
        }
        return out.values.toList()
    }

    private fun File.toHit() = FileHit(absolutePath, name, isDirectory, if (isDirectory) 0 else length(), lastModified())
}
