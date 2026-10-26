package com.axiom.launcher.solis

import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.OpenAiCompatProvider
import com.axiom.launcher.solis.llm.PrivacyPrefs
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.llm.ToolCall
import com.axiom.launcher.solis.llm.ToolSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One-shot HTTP/1.1 server: captures the request, then runs [respond] with the socket. */
class MockServer(private val respond: (Socket) -> Unit) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/api/v1"
    val head = AtomicReference<String>()
    val body = AtomicReference<String>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val input = sock.getInputStream()
                    val h = StringBuilder()
                    while (!h.endsWith("\r\n\r\n")) {
                        val c = input.read(); if (c < 0) break; h.append(c.toChar())
                    }
                    val len = Regex("(?i)content-length: (\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                    val b = ByteArray(len)
                    var off = 0
                    while (off < len) { val n = input.read(b, off, len - off); if (n < 0) break; off += n }
                    head.set(h.toString()); body.set(b.decodeToString())
                    respond(sock)
                }
                runCatching { sock.close() }
            }
        }
    }

    fun close() = server.close()

    companion object {
        fun sse(sock: Socket, payload: String) {
            val bytes = payload.toByteArray()
            sock.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }

        fun status(sock: Socket, code: Int, json: String, extra: String = "") {
            val bytes = json.toByteArray()
            sock.getOutputStream().apply {
                write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
    }
}

class OpenRouterAdapterTest {
    private val servers = ArrayList<MockServer>()
    @After fun tearDown() = servers.forEach { it.close() }

    private fun provider(url: String) = OpenAiCompatProvider(
        id = "openrouter", displayName = "OpenRouter", baseUrl = { url }, apiKey = { "sk-or-test" },
        flavor = OpenAiCompatProvider.Flavor.OPENROUTER, http = OkHttpClient(),
    )

    private val request = ChatRequest(
        model = "acme/mid",
        system = "You are Solis.",
        messages = listOf(
            ChatMessage.user("Set a timer and say hi"),
            ChatMessage(com.axiom.launcher.solis.llm.ChatRole.ASSISTANT, emptyList(), listOf(ToolCall("call_1", "set_timer", """{"seconds":60}"""))),
            ChatMessage.toolResult(ToolCall("call_1", "set_timer", "{}"), "Timer set", false),
        ),
        tools = listOf(ToolSpec("set_timer", "Start a timer", """{"type":"object","properties":{"seconds":{"type":"integer"}},"required":["seconds"]}""")),
        maxOutputTokens = 2048,
        effort = Effort.LOW,
        privacy = PrivacyPrefs(denyDataCollection = true, zeroDataRetention = true),
        inferenceFallbacks = listOf("zeta/strong"),
    )

    @Test
    fun streamsTextToolCallsAndUsage() = runBlocking {
        val sse = buildString {
            append(": OPENROUTER PROCESSING\n\n")
            append("data: {\"id\":\"g1\",\"model\":\"acme/mid\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi \"}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"there\"}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"set_timer\",\"arguments\":\"{\\\"sec\"}}]}}]}\n\n")
            append(": OPENROUTER PROCESSING\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"onds\\\": 90}\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_10\",\"type\":\"function\",\"function\":{\"name\":\"note_add\",\"arguments\":\"{}\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\",\"native_finish_reason\":\"tool_use\"}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"prompt_tokens_details\":{\"cached_tokens\":64},\"completion_tokens_details\":{\"reasoning_tokens\":12},\"cost\":0.00042}}\n\n")
            append("data: [DONE]\n\n")
        }
        val srv = MockServer { MockServer.sse(it, sse) }.also { servers += it }
        val events = provider(srv.url).stream(request).toList()

        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hi there", text)
        val calls = events.filterIsInstance<LlmEvent.ToolCallReady>().map { it.call }
        assertEquals(listOf("set_timer", "note_add"), calls.map { it.name })
        assertEquals(90, JSONObject(calls[0].argumentsJson).getInt("seconds"))
        val usage = events.filterIsInstance<LlmEvent.Usage>().single()
        assertEquals(0.00042, usage.costUsd!!, 1e-12)
        assertEquals(64, usage.cachedInputTokens)
        val finish = events.last() as LlmEvent.Finish
        assertEquals(FinishReason.TOOL_CALLS, finish.reason)
        assertEquals("acme/mid", finish.servedModel)

        // Request: OpenAI-compatible body plus OpenRouter routing/privacy/reasoning fields.
        val body = JSONObject(srv.body.get())
        assertEquals("acme/mid", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        val assistant = body.getJSONArray("messages").getJSONObject(2)
        assertTrue(assistant.isNull("content"))
        assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", body.getJSONArray("messages").getJSONObject(3).getString("role"))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals(listOf("acme/mid", "zeta/strong"), body.getJSONArray("models").let { a -> (0 until a.length()).map { a.getString(it) } })
        val prefs = body.getJSONObject("provider")
        assertEquals("deny", prefs.getString("data_collection"))
        assertTrue(prefs.getBoolean("zdr"))
        assertTrue(prefs.getBoolean("require_parameters"))
        val h = srv.head.get()
        assertTrue(h.contains("Authorization: Bearer sk-or-test"))
        assertTrue(h.contains("X-OpenRouter-Title: Axiom Launcher"))
        assertTrue(h.contains("HTTP-Referer:"))
    }

    @Test
    fun midStreamErrorIsAFailureNotAnAnswer() = runBlocking {
        val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n" +
            "data: {\"error\":{\"code\":\"server_error\",\"message\":\"Provider disconnected unexpectedly\"},\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"error\"}]}\n\n"
        val srv = MockServer { MockServer.sse(it, sse) }.also { servers += it }
        try {
            provider(srv.url).stream(request).toList()
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ErrorKind.UNAVAILABLE, e.kind)
            assertTrue(e.message!!.contains("disconnected"))
        }
    }

    @Test
    fun httpErrorsMapToKinds() = runBlocking {
        val cases = listOf(
            Triple(429, """{"error":{"code":429,"message":"Rate limited"}}""", ErrorKind.RATE_LIMIT),
            Triple(402, """{"error":{"code":402,"message":"Insufficient credits"}}""", ErrorKind.BUDGET),
            Triple(401, """{"error":{"code":401,"message":"No auth"}}""", ErrorKind.AUTH),
            Triple(403, """{"error":{"code":403,"message":"Budget exceeded","metadata":{"limit_source":"openrouter_key_limit"}}}""", ErrorKind.BUDGET),
            Triple(503, """{"error":{"code":503,"message":"No available provider"}}""", ErrorKind.UNAVAILABLE),
            Triple(400, """{"error":{"code":400,"message":"This model's maximum context length is 8192 tokens"}}""", ErrorKind.CONTEXT_LENGTH),
        )
        for ((code, json, kind) in cases) {
            val srv = MockServer { MockServer.status(it, code, json, if (code == 429) "Retry-After: 2\r\n" else "") }.also { servers += it }
            try {
                provider(srv.url).stream(request).toList()
                fail("expected error for $code")
            } catch (e: ProviderException) {
                assertEquals("HTTP $code", kind, e.kind)
                assertEquals(code, e.httpStatus)
                if (code == 429) assertEquals(2000L, e.retryAfterMs)
                assertEquals("credentials/spending problems are never fixed by switching models",
                    kind != ErrorKind.AUTH && kind != ErrorKind.BUDGET, e.modelFallbackHelps)
            }
        }
    }

    @Test
    fun cancellingTheCollectorClosesTheConnection() = runBlocking {
        val closed = CountDownLatch(1)
        val firstSent = CountDownLatch(1)
        val srv = MockServer { sock ->
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            fun chunk(s: String) { val b = s.toByteArray(); out.write("${Integer.toHexString(b.size)}\r\n".toByteArray()); out.write(b); out.write("\r\n".toByteArray()); out.flush() }
            chunk("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tick\"}}]}\n\n")
            firstSent.countDown()
            try {
                repeat(200) { Thread.sleep(25); chunk(": OPENROUTER PROCESSING\n\n") }
            } catch (e: Exception) {
                closed.countDown()   // the client went away
            }
        }.also { servers += it }
        val first = withTimeout(5000) { provider(srv.url).stream(request).first() }
        assertEquals("tick", (first as LlmEvent.TextDelta).text)
        assertTrue("server saw the connection close after cancellation", closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun localRuntimeFlavorSendsNoOpenRouterFields() = runBlocking {
        val srv = MockServer { MockServer.sse(it, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"local\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") }.also { servers += it }
        val local = OpenAiCompatProvider("local", "Local", { srv.url }, { null }, OpenAiCompatProvider.Flavor.LOCAL, OkHttpClient())
        val events = local.stream(request).toList()
        assertEquals("local", events.filterIsInstance<LlmEvent.TextDelta>().single().text)
        val body = JSONObject(srv.body.get())
        assertTrue(!body.has("provider") && !body.has("reasoning") && !body.has("models"))
        assertNull(Regex("(?i)authorization").find(srv.head.get()))
        assertTrue(local.isLocal)
    }
}
