package com.dcode.vault.core

import com.dcode.crypto.Bytes
import com.dcode.crypto.SecretStream
import com.dcode.vault.core.search.SearchHit
import com.dcode.vault.core.search.SearchIndex
import com.dcode.vault.core.search.SearchScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

@Serializable
private data class IndexEntry(val id: String, val version: Int, val text: String)

/**
 * An unlocked vault. Holds the derived keys and the decrypted catalog/index in memory only;
 * [close] wipes them. All methods are thread-safe and blocking (call them off the main thread).
 */
class VaultSession internal constructor(
    private val store: VaultStore,
    dek: ByteArray,
    private val clock: () -> Long,
) : Closeable {
    private val keys = VaultKeys(dek)
    private val lock = Any()
    /** Writers share the read side; [close] takes the write side so it waits for in-flight encryption to finish. */
    private val lifecycle = java.util.concurrent.locks.ReentrantReadWriteLock()

    private inline fun <T> writing(block: () -> T): T {
        lifecycle.readLock().lock()
        try {
            check(!closed) { "Vault is locked" }
            return block()
        } finally {
            lifecycle.readLock().unlock()
        }
    }
    private val nodes = LinkedHashMap<String, Node>()
    private val index = SearchIndex()
    private val indexedVersions = HashMap<String, Int>()
    private var indexDirty = false
    private var closed = false
    private val _snapshot = MutableStateFlow(VaultSnapshot.EMPTY)
    val snapshot: StateFlow<VaultSnapshot> = _snapshot.asStateFlow()

    init {
        loadCatalog()
        loadIndex()
        publish()
    }

    val isClosed: Boolean get() = closed

    /** Copy of the DEK for header operations (re-wrapping key slots). Caller must wipe it. */
    fun dekCopy(): ByteArray = synchronized(lock) { check(!closed); keys.dek.copyOf() }

    // ------------------------------------------------------------------ queries

    fun node(id: String): Node? = synchronized(lock) { nodes[id] }

    fun search(query: String, scope: SearchScope = SearchScope.ALL, limit: Int = 200): List<SearchHit> = index.search(query, scope, limit)

    fun indexedDocuments(): Int = index.size()

    fun readText(id: String): String = openBlob(id).use { it.readBytes().decodeToString() }

    fun readBytes(id: String): ByteArray = openBlob(id).use { it.readBytes() }

    fun openBlob(id: String): InputStream {
        val node = node(id) ?: throw NoSuchElementException(id)
        require(node.hasBlob)
        val f = blobFile(id)
        if (!f.exists()) return java.io.ByteArrayInputStream(ByteArray(0))
        return SecretStream.decryptingStream(keys.blobs, Contexts.blob(id), FileInputStream(f).buffered(64 * 1024))
    }

    fun exportBlob(id: String, out: OutputStream) {
        openBlob(id).use { it.copyTo(out, 64 * 1024) }
    }

    // ------------------------------------------------------------------ mutations

    fun createFolder(parentId: String?, name: String): Node = insert(newNode(parentId, NodeKind.FOLDER, uniqueName(parentId, name)))

    fun createNote(parentId: String?, name: String, text: String = ""): Node = writing {
        val node = newNode(parentId, NodeKind.NOTE, uniqueName(parentId, name), mime = TextTypes.guessMime(name))
        val written = writeBlob(node, text.toByteArray())
        insert(written)
        indexText(written, text)
        written
    }

    fun saveText(id: String, text: String): Node = writing {
        val node = node(id) ?: throw NoSuchElementException(id)
        val updated = writeBlob(node, text.toByteArray())
        replace(updated)
        indexText(updated, text)
        updated
    }

    fun importFile(parentId: String?, name: String, mime: String?, input: InputStream): Node = writing {
        val clean = sanitize(name).ifEmpty { "Imported file" }
        var node = newNode(parentId, NodeKind.FILE, uniqueName(parentId, clean), mime = mime ?: TextTypes.guessMime(clean))
        val f = blobFile(node.id)
        var size = 0L
        val head = ByteArrayOutputStream()
        VaultStore.atomicWrite(f) { raw ->
            SecretStream.encryptingStream(keys.blobs, Contexts.blob(node.id), raw).use { enc ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    enc.write(buf, 0, n)
                    if (head.size() < MAX_INDEXED_BYTES) head.write(buf, 0, minOf(n, MAX_INDEXED_BYTES - head.size()))
                    size += n
                }
                buf.fill(0)
            }
        }
        node = node.copy(size = size, blobVersion = 1)
        insert(node)
        val sample = head.toByteArray()
        if (TextTypes.isTextLike(node.name, node.mime) || TextTypes.looksLikeText(sample.copyOf(minOf(sample.size, 4096)))) {
            indexText(node, sample.decodeToString())
        } else {
            indexText(node, null)
        }
        Bytes.wipe(sample)
        node
    }

    fun savePassword(id: String?, parentId: String?, title: String, fields: PasswordFields): Node {
        val existing = id?.let { node(it) }
        val now = clock()
        val node = if (existing == null) {
            newNode(parentId, NodeKind.PASSWORD, title.ifBlank { "Untitled login" }).copy(password = fields.copy(passwordChangedAt = now))
        } else {
            val changed = existing.password?.password != fields.password
            existing.copy(
                name = title.ifBlank { existing.name },
                password = fields.copy(passwordChangedAt = if (changed) now else existing.password?.passwordChangedAt ?: now),
                modifiedAt = now,
            )
        }
        if (existing == null) insert(node) else replace(node)
        indexText(node, passwordSearchText(fields))
        return node
    }

    fun rename(id: String, newName: String): Node {
        val node = node(id) ?: throw NoSuchElementException(id)
        val clean = sanitize(newName).ifEmpty { return node }
        val updated = node.copy(name = uniqueName(node.parentId, clean, exceptId = id), modifiedAt = clock())
        replace(updated)
        index.rename(id, updated.name)
        synchronized(lock) { indexDirty = true }
        return updated
    }

    fun move(id: String, newParentId: String?): Node {
        val node = node(id) ?: throw NoSuchElementException(id)
        synchronized(lock) {
            var p = newParentId
            while (p != null) {
                require(p != id) { "Cannot move a folder into itself" }
                p = nodes[p]?.parentId
            }
            require(newParentId == null || nodes[newParentId]?.isFolder == true) { "Target is not a folder" }
        }
        val updated = node.copy(parentId = newParentId, name = uniqueName(newParentId, node.name, exceptId = id), modifiedAt = clock())
        replace(updated)
        return updated
    }

    fun setFavorite(id: String, favorite: Boolean): Node {
        val node = node(id) ?: throw NoSuchElementException(id)
        return node.copy(favorite = favorite).also { replace(it) }
    }

    /** Deletes the node and, for folders, everything inside it. Blob files are overwritten before unlinking. */
    fun delete(id: String) {
        val doomed = synchronized(lock) {
            val out = ArrayList<String>()
            val stack = ArrayDeque(listOf(id))
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (nodes.containsKey(cur)) out.add(cur)
                nodes.values.filter { it.parentId == cur }.forEach { stack.addLast(it.id) }
            }
            out.forEach { nodes.remove(it) }
            out
        }
        persistCatalog()
        for (d in doomed) {
            index.remove(d)
            indexedVersions.remove(d)
            shred(blobFile(d))
        }
        synchronized(lock) { indexDirty = true }
        publish()
    }

    fun flush() {
        persistCatalog()
        flushIndex(force = true)
    }

    fun flushIndex(force: Boolean = false) {
        writing { flushIndexLocked(force) }
    }

    private fun flushIndexLocked(force: Boolean) {
        synchronized(lock) {
            if (!indexDirty && !force) return
            val entries = index.entries().mapNotNull { (id, _, text) ->
                if (text.isEmpty()) null else IndexEntry(id, indexedVersions[id] ?: 0, text)
            }
            val bytes = HeaderCodec.json.encodeToString(ListSerializer(IndexEntry.serializer()), entries).toByteArray()
            VaultStore.atomicWrite(store.file("index.bin")) { raw ->
                SecretStream.encryptingStream(keys.index, Contexts.INDEX, raw, SecretStream.SUITE_AES).use { it.write(bytes) }
            }
            Bytes.wipe(bytes)
            indexDirty = false
        }
    }

    /** Re-indexes any text document whose stored index entry is missing or stale. Returns the number repaired. */
    fun repairIndex(): Int {
        val stale = synchronized(lock) {
            nodes.values.filter { n ->
                n.hasBlob && (indexedVersions[n.id] ?: -1) != n.blobVersion && TextTypes.isTextLike(n.name, n.mime)
            }
        }
        var repaired = 0
        for (n in stale) {
            runCatching {
                val text = openBlob(n.id).use { s ->
                    val buf = ByteArray(MAX_INDEXED_BYTES)
                    var total = 0
                    while (total < buf.size) {
                        val r = s.read(buf, total, buf.size - total)
                        if (r < 0) break
                        total += r
                    }
                    String(buf, 0, total, Charsets.UTF_8).also { buf.fill(0) }
                }
                indexText(n, text, flush = false)
                repaired++
            }
        }
        if (repaired > 0) flushIndex()
        return repaired
    }

    override fun close() {
        lifecycle.writeLock().lock()
        try {
            if (closed) return
            runCatching { flushIndex() }
            synchronized(lock) { closed = true }
            nodes.clear()
            index.clear()
            keys.wipe()
            _snapshot.value = VaultSnapshot.EMPTY
        } finally {
            lifecycle.writeLock().unlock()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun newNode(parentId: String?, kind: NodeKind, name: String, mime: String? = null): Node {
        val now = clock()
        return Node(id = Bytes.b64(Bytes.random(16)), parentId = parentId, kind = kind, name = name, createdAt = now, modifiedAt = now, mime = mime)
    }

    private fun writeBlob(node: Node, bytes: ByteArray): Node {
        VaultStore.atomicWrite(blobFile(node.id)) { raw ->
            SecretStream.encryptingStream(keys.blobs, Contexts.blob(node.id), raw).use { it.write(bytes) }
        }
        val size = bytes.size.toLong()
        Bytes.wipe(bytes)
        return node.copy(size = size, modifiedAt = clock(), blobVersion = node.blobVersion + 1)
    }

    private fun insert(node: Node): Node {
        synchronized(lock) {
            check(!closed)
            require(node.parentId == null || nodes[node.parentId]?.isFolder == true) { "Parent folder missing" }
            nodes[node.id] = node
        }
        if (node.kind == NodeKind.FOLDER) indexText(node, null)
        persistCatalog()
        publish()
        return node
    }

    private fun replace(node: Node) {
        synchronized(lock) {
            check(!closed)
            nodes[node.id] = node
        }
        persistCatalog()
        publish()
    }

    private fun indexText(node: Node, text: String?, flush: Boolean = false) {
        index.put(node.id, node.name, text?.take(MAX_INDEXED_CHARS))
        synchronized(lock) {
            indexedVersions[node.id] = node.blobVersion
            indexDirty = true
        }
        if (flush) flushIndex()
    }

    private fun passwordSearchText(f: PasswordFields): String =
        listOf(f.username, f.url, f.notes).plus(f.extra.filterNot { it.secret }.map { "${it.label} ${it.value}" })
            .filter { it.isNotBlank() }.joinToString("\n")

    private fun uniqueName(parentId: String?, name: String, exceptId: String? = null): String {
        val clean = sanitize(name).ifEmpty { "Untitled" }
        val siblings = synchronized(lock) {
            nodes.values.filter { it.parentId == parentId && it.id != exceptId }.map { it.name.lowercase() }.toHashSet()
        }
        if (clean.lowercase() !in siblings) return clean
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var i = 2
        while ("$base ($i)$ext".lowercase() in siblings) i++
        return "$base ($i)$ext"
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\u0000-\\u001f/\\\\]"), " ").trim().take(200)

    private fun blobFile(id: String) = File(store.blobDir, id)

    private fun shred(f: File) {
        if (!f.exists()) return
        runCatching {
            val len = f.length()
            FileOutputStream(f).use { out ->
                val buf = Bytes.random(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    out.write(buf, 0, n)
                    left -= n
                }
                out.fd.sync()
            }
        }
        f.delete()
    }

    private fun persistCatalog() = writing {
        synchronized(lock) {
            val bytes = HeaderCodec.json.encodeToString(Catalog.serializer(), Catalog(nodes = nodes.values.toList())).toByteArray()
            VaultStore.atomicWrite(store.file("catalog.bin")) { raw ->
                SecretStream.encryptingStream(keys.catalog, Contexts.CATALOG, raw).use { it.write(bytes) }
            }
            Bytes.wipe(bytes)
        }
    }

    private fun loadCatalog() {
        val f = store.file("catalog.bin")
        if (!f.exists()) return
        val bytes = SecretStream.decryptingStream(keys.catalog, Contexts.CATALOG, FileInputStream(f).buffered()).use { it.readBytes() }
        val catalog = HeaderCodec.json.decodeFromString(Catalog.serializer(), bytes.decodeToString())
        Bytes.wipe(bytes)
        for (n in catalog.nodes) nodes[n.id] = n
    }

    private fun loadIndex() {
        val f = store.file("index.bin")
        val stored = HashMap<String, IndexEntry>()
        if (f.exists()) {
            runCatching {
                val bytes = SecretStream.decryptingStream(keys.index, Contexts.INDEX, FileInputStream(f).buffered()).use { it.readBytes() }
                HeaderCodec.json.decodeFromString(ListSerializer(IndexEntry.serializer()), bytes.decodeToString()).forEach { stored[it.id] = it }
                Bytes.wipe(bytes)
            }
        }
        for (n in nodes.values) {
            val e = stored[n.id]
            index.put(n.id, n.name, e?.text)
            if (e != null) indexedVersions[n.id] = e.version
            else if (!n.hasBlob) indexedVersions[n.id] = n.blobVersion
        }
        for (n in nodes.values) if (n.kind == NodeKind.PASSWORD && stored[n.id] == null) {
            index.put(n.id, n.name, n.password?.let(::passwordSearchText))
        }
    }

    private fun publish() {
        synchronized(lock) {
            if (closed) return
            _snapshot.value = VaultSnapshot(HashMap(nodes), _snapshot.value.revision + 1)
        }
    }

    companion object {
        const val MAX_INDEXED_BYTES = 2 * 1024 * 1024
        const val MAX_INDEXED_CHARS = 2 * 1024 * 1024
    }
}
