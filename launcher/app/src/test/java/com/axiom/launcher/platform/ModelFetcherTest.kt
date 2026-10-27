package com.axiom.launcher.platform

import com.axiom.launcher.solis.TestHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

/** Model downloads: multi-gigabyte files on phone networks must resume, verify and never fill the phone. */
class ModelFetcherTest {
    private val body = buildString { var x = 7L; repeat(20_000) { x = (x * 1103515245 + 12345) and 0x7fffffff; append('a' + (x % 26).toInt()) } }
    private val sha = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val target = File(dir, "gemma.litertlm")
    private val ranges: MutableList<String?> = Collections.synchronizedList(ArrayList())
    private val servers = ArrayList<TestHttp>()

    @After
    fun cleanup() { servers.forEach { it.close() }; dir.deleteRecursively() }

    /** Serves [body]; honours Range unless told not to; sends at most [chunk] bytes per response. */
    private fun server(honourRange: Boolean = true, chunk: Int = Int.MAX_VALUE): String {
        val s = TestHttp { req ->
            val range = req.headers["range"]
            ranges += range
            val from = if (honourRange) range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0 else 0
            val part = body.substring(from, minOf(body.length.toLong(), from.toLong() + chunk).toInt())
            if (from > 0 || part.length < body.length) TestHttp.Res(206, part, "application/octet-stream", mapOf("Content-Range" to "bytes $from-${from + part.length - 1}/${body.length}"))
            else TestHttp.Res(200, part, "application/octet-stream")
        }
        servers += s
        return "http://127.0.0.1:${s.port}/model"
    }

    private fun fetcher(free: Long = Long.MAX_VALUE) = ModelFetcher(OkHttpClient(), freeBytes = { free })

    @Test
    fun downloadsVerifiesAndMovesIntoPlace() = runBlocking {
        val url = server()
        var last = 0L
        var verified = false
        fetcher().fetch(url, target, body.length.toLong(), sha, margin = 0) { done, total, verifying -> last = done; if (verifying) verified = true; assertEquals(body.length.toLong(), total) }
        assertEquals(body, target.readText())
        assertFalse(File(target.path + ".part").exists())
        assertTrue(verified && last == body.length.toLong())
        assertEquals(listOf<String?>(null), ranges)
    }

    @Test
    fun resumesWhereItStopped() = runBlocking {
        File(target.path + ".part").writeText(body.substring(0, 8_000))
        fetcher().fetch(server(), target, body.length.toLong(), sha, margin = 0) { _, _, _ -> }
        assertEquals(body, target.readText())
        assertEquals("only the missing part was asked for", listOf<String?>("bytes=8000-"), ranges)
    }

    @Test
    fun keepsGoingWhenTheConnectionKeepsDropping() = runBlocking {
        fetcher().fetch(server(chunk = 3_000), target, body.length.toLong(), sha, margin = 0) { _, _, _ -> }
        assertEquals(body, target.readText())
        assertEquals(7, ranges.size)
        assertEquals("bytes=18000-", ranges.last())
    }

    @Test
    fun startsOverWhenTheServerIgnoresRanges() = runBlocking {
        File(target.path + ".part").writeText(body.substring(0, 5_000))
        fetcher().fetch(server(honourRange = false), target, body.length.toLong(), sha, margin = 0) { _, _, _ -> }
        assertEquals(body, target.readText())
    }

    @Test
    fun aFileThatDoesntMatchItsChecksumIsThrownAway() = runBlocking {
        try {
            fetcher().fetch(server(), target, body.length.toLong(), "0".repeat(64), margin = 0) { _, _, _ -> }
            fail("expected a checksum mismatch")
        } catch (e: ChecksumMismatch) {
        }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun neverStartsWithoutRoomToSpare() = runBlocking {
        val url = server()
        try {
            fetcher(free = body.length.toLong() + 10).fetch(url, target, body.length.toLong(), sha, margin = 1_000) { _, _, _ -> }
            fail("expected NotEnoughSpace")
        } catch (e: NotEnoughSpace) {
            assertEquals(body.length + 1_000L, e.needed)
        }
        assertTrue("nothing was downloaded", ranges.isEmpty())
    }
}
