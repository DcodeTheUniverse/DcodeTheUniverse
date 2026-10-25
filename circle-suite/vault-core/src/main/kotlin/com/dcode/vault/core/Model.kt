package com.dcode.vault.core

import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind { FOLDER, NOTE, FILE, PASSWORD }

@Serializable
data class CustomField(val label: String, val value: String, val secret: Boolean = false)

@Serializable
data class PasswordFields(
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp: String = "",
    val extra: List<CustomField> = emptyList(),
    val passwordChangedAt: Long = 0,
)

@Serializable
data class Node(
    val id: String,
    val parentId: String?,
    val kind: NodeKind,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val size: Long = 0,
    val mime: String? = null,
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val password: PasswordFields? = null,
    /** Incremented every time the encrypted blob is rewritten; lets the search index self-heal. */
    val blobVersion: Int = 0,
) {
    val isFolder: Boolean get() = kind == NodeKind.FOLDER
    val hasBlob: Boolean get() = kind == NodeKind.NOTE || kind == NodeKind.FILE
}

@Serializable
data class Catalog(val version: Int = 1, val nodes: List<Node> = emptyList())

/** Immutable view of the catalog published to the UI. */
class VaultSnapshot(val nodes: Map<String, Node>, val revision: Long) {
    val children: Map<String?, List<Node>> by lazy { nodes.values.groupBy { it.parentId } }

    fun childrenOf(parentId: String?): List<Node> = children[parentId].orEmpty()

    fun path(id: String?): List<Node> {
        val out = ArrayList<Node>()
        var cur = id?.let { nodes[it] }
        var guard = 0
        while (cur != null && guard++ < 256) {
            out.add(cur)
            cur = cur.parentId?.let { nodes[it] }
        }
        return out.asReversed()
    }

    fun count(kind: NodeKind): Int = nodes.values.count { it.kind == kind }

    companion object {
        val EMPTY = VaultSnapshot(emptyMap(), 0)
    }
}

object TextTypes {
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "mdown", "rst", "org", "adoc", "tex", "csv", "tsv", "json", "jsonl", "xml", "yaml",
        "yml", "toml", "ini", "cfg", "conf", "log", "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "m", "sh", "bash", "zsh", "sql", "gradle",
        "properties", "env", "gitignore", "dart", "lua", "r", "scala", "php", "pl", "vue", "svelte", "srt", "vtt",
    )
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isMarkdown(name: String): Boolean = extension(name) in MARKDOWN_EXTENSIONS ||
        name.equals("readme", ignoreCase = true)

    fun isTextLike(name: String, mime: String?): Boolean =
        extension(name) in TEXT_EXTENSIONS || mime?.startsWith("text/") == true ||
            mime == "application/json" || mime == "application/xml" || name.equals("readme", ignoreCase = true)

    fun isImage(name: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true || extension(name) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")

    /** Heuristic for content sniffing: valid UTF-8 without NUL bytes. */
    fun looksLikeText(sample: ByteArray): Boolean {
        if (sample.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(sample))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            // The sample may end mid-character; accept if only the last 3 bytes are at fault.
            sample.size > 4 && try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sample, 0, sample.size - 3)); true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun guessMime(name: String): String = when (extension(name)) {
        "md", "markdown", "mdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> if (isTextLike(name, null)) "text/plain" else "application/octet-stream"
    }
}
