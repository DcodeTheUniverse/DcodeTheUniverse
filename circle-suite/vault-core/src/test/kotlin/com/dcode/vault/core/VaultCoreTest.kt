package com.dcode.vault.core

import com.dcode.crypto.Argon2Params
import com.dcode.crypto.Bytes
import com.dcode.crypto.CryptoException
import com.dcode.vault.core.markdown.Block
import com.dcode.vault.core.markdown.Inline
import com.dcode.vault.core.markdown.Markdown
import com.dcode.vault.core.search.SearchIndex
import com.dcode.vault.core.search.SearchScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultCoreTest {
    private val root: File = Files.createTempDirectory("vault-test").toFile()
    private val fast = Argon2Params(memoryKiB = 1024, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun createUnlockAndPersistAcrossSessions() {
        val store = VaultStore(File(root, "v"))
        val dek = store.create("pass phrase 1".toCharArray(), fast)
        val noteId: String
        val fileId: String
        val payload = Bytes.random(200_000)
        store.open(dek).use { s ->
            val ideas = s.createFolder(null, "Ideas")
            noteId = s.createNote(ideas.id, "Fusion reactor.md", "# Fusion\nA tokamak in a *teacup*.").id
            fileId = s.importFile(ideas.id, "blob.bin", null, ByteArrayInputStream(payload)).id
            s.savePassword(null, null, "GitHub", PasswordFields(username = "dcode", password = "hunter2", url = "github.com"))
        }
        // Nothing readable on disk.
        val allBytes = store.dir.walkTopDown().filter { it.isFile }.map { it.readBytes().decodeToString() }.joinToString()
        assertFalse(allBytes.contains("tokamak") || allBytes.contains("Fusion") || allBytes.contains("hunter2") || allBytes.contains("Ideas"))

        val dek2 = store.unlockWithPassphrase("pass phrase 1".toCharArray())
        assertContentEquals(dek, dek2)
        store.open(dek2).use { s ->
            assertEquals(4, s.snapshot.value.nodes.size)
            assertEquals("# Fusion\nA tokamak in a *teacup*.", s.readText(noteId))
            assertContentEquals(payload, s.readBytes(fileId))
            assertEquals(noteId, s.search("tokam").first().id)
            assertEquals(noteId, s.search("fusion react", SearchScope.NAMES).first().id)
            assertTrue(s.search("hunter2").isEmpty(), "passwords must never be indexed")
            assertEquals("GitHub", s.snapshot.value.nodes[s.search("github").first().id]?.name)
        }
    }

    @Test
    fun wrongPassphraseIsRejectedAndThrottled() {
        val store = VaultStore(File(root, "v"))
        store.create("right".toCharArray(), fast)
        repeat(3) { assertFailsWith<CryptoException> { store.unlockWithPassphrase("wrong".toCharArray()) } }
        assertTrue(store.passphraseLockoutRemainingMs() > 0)
    }

    @Test
    fun headerTamperingIsDetected() {
        val store = VaultStore(File(root, "v"))
        val dek = store.create("right".toCharArray(), fast)
        val body = store.readHeaderBody()
        // An attacker rewrites the header (e.g. to swap the pinned Circle ID key) without knowing the DEK.
        val forged = HeaderCodec.seal(body.copy(circle = CirclePairing("X", "Y", "Z", 0)), Bytes.random(32))
        VaultStore.atomicWrite(File(store.dir, "header.json"), HeaderCodec.encode(forged))
        assertFailsWith<VaultTamperedException> { store.verifyHeader(dek) }
    }

    @Test
    fun circleSlotNeedsReleaseSecret() {
        val dek = Bytes.random(32)
        val secret = Bytes.random(32)
        val inner = KeySlots.circleInnerWrap(dek, secret, "vault1")
        assertContentEquals(dek, KeySlots.circleInnerUnwrap(inner, secret, "vault1"))
        assertFailsWith<CryptoException> { KeySlots.circleInnerUnwrap(inner, Bytes.random(32), "vault1") }
        assertFailsWith<CryptoException> { KeySlots.circleInnerUnwrap(inner, secret, "vault2") }
    }

    @Test
    fun folderOperations() {
        val store = VaultStore(File(root, "v"))
        val dek = store.create("p".toCharArray(), fast)
        store.open(dek).use { s ->
            val a = s.createFolder(null, "A")
            val b = s.createFolder(a.id, "B")
            val n = s.createNote(b.id, "note.txt", "hello")
            assertEquals("note (2).txt", s.createNote(b.id, "note.txt", "again").name)
            assertFailsWith<IllegalArgumentException> { s.move(a.id, b.id) }
            s.move(n.id, null)
            assertEquals(null, s.node(n.id)?.parentId)
            assertEquals(listOf("A", "B"), s.snapshot.value.path(b.id).map { it.name })
            s.delete(a.id)
            assertEquals(listOf(n.id), s.snapshot.value.nodes.keys.toList())
            assertTrue(s.search("again").isEmpty())
            assertEquals(1, store.blobDir.listFiles()!!.size)
        }
    }

    @Test
    fun indexSelfHealsAfterCrash() {
        val store = VaultStore(File(root, "v"))
        val dek = store.create("p".toCharArray(), fast)
        val s = store.open(dek)
        val n = s.createNote(null, "draft.md", "first words")
        s.flush()
        s.saveText(n.id, "quantum entanglement notes")
        // Simulate a crash: the session is never closed, so the index on disk is stale.
        store.open(dek).use { s2 ->
            assertTrue(s2.search("quantum").isEmpty())
            assertEquals(1, s2.repairIndex())
            assertEquals(n.id, s2.search("entangle").single().id)
        }
    }

    @Test
    fun backupRoundTrip() {
        val store = VaultStore(File(root, "v"))
        val dek = store.create("backup pass".toCharArray(), fast)
        store.open(dek).use { it.createNote(null, "secret.md", "launch codes") }
        val zip = ByteArrayOutputStream().also { store.exportBackup(it) }.toByteArray()
        val restored = VaultStore(File(root, "restored"))
        restored.importBackup(ByteArrayInputStream(zip))
        restored.open(restored.unlockWithPassphrase("backup pass".toCharArray())).use {
            assertEquals("launch codes", it.readText(it.search("secret").single().id))
        }
    }

    @Test
    fun searchRankingSnippetsAndPhrases() {
        val idx = SearchIndex()
        idx.put("1", "Rocket ideas.md", "Reusable rockets land on barges. Café culture on Mars.")
        idx.put("2", "Groceries", "eggs, milk, rocket salad")
        idx.put("3", "rocket", null)
        val hits = idx.search("rocket")
        assertEquals("3", hits.first().id) // exact name beats everything
        assertEquals(setOf("1", "2", "3"), hits.map { it.id }.toSet())
        assertEquals(listOf("1"), idx.search("cafe mars").map { it.id }) // accent-insensitive, AND semantics
        assertEquals(listOf("2"), idx.search("\"rocket salad\"").map { it.id })
        val snip = idx.search("barges").single()
        assertTrue(snip.snippet!!.contains("barges"))
        val r = snip.snippetRanges.single()
        assertEquals("barges", snip.snippet.substring(r.first, r.last + 1))
        idx.remove("1")
        assertTrue(idx.search("barges").isEmpty())
    }

    @Test
    fun searchIsFastAtScale() {
        val idx = SearchIndex()
        val words = List(5000) { "word$it" }
        val rnd = java.util.Random(1)
        repeat(5000) { d -> idx.put("d$d", "Document $d", (1..200).joinToString(" ") { words[rnd.nextInt(words.size)] }) }
        repeat(20) { idx.search("word77 word3") } // JIT warm-up
        val t0 = System.nanoTime()
        repeat(50) { idx.search("word12 word4") }
        val perQueryMs = (System.nanoTime() - t0) / 50 / 1e6
        assertTrue(perQueryMs < 50, "query took $perQueryMs ms")
    }

    @Test
    fun markdownParsesCommonReadmeConstructs() {
        val md = """
            # Title
            Some **bold**, *italic*, `code` and [a link](https://x.y).

            - [x] done
            - [ ] todo
              continued

            1. one
            2. two

            > quoted

            ```kotlin
            val x = 1
            ```

            | a | b |
            |---|:-:|
            | 1 | 2 |

            ---
        """.trimIndent()
        val blocks = Markdown.parse(md)
        assertEquals(Block.Heading(1, listOf(Inline.Text("Title"))), blocks[0])
        val p = assertIs<Block.Paragraph>(blocks[1])
        assertTrue(p.inlines.any { it is Inline.Bold } && p.inlines.any { it is Inline.Italic } && p.inlines.any { it is Inline.Code })
        assertEquals("https://x.y", p.inlines.filterIsInstance<Inline.Link>().single().url)
        val tasks = assertIs<Block.ListBlock>(blocks[2])
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
        assertEquals("todo continued", Markdown.plainText((tasks.items[1].blocks.single() as Block.Paragraph).inlines))
        assertEquals(true, assertIs<Block.ListBlock>(blocks[3]).ordered)
        assertIs<Block.Quote>(blocks[4])
        assertEquals(Block.CodeBlock("kotlin", "val x = 1"), blocks[5])
        val table = assertIs<Block.Table>(blocks[6])
        assertEquals(1, table.rows.size)
        assertEquals(Block.Rule, blocks[7])
    }
}
