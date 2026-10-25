package com.dcode.launcher.ai

import android.content.Context
import android.content.ContextWrapper
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.dcode.launcher.core.Actions
import com.dcode.launcher.data.AppInfo
import com.dcode.launcher.data.FileRepository
import com.dcode.launcher.data.Note
import com.dcode.launcher.ui.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.Collections

/** Drives the real agent loop (SDK streaming, accumulator, tool round trip) against a local fake API. */
class ClaudeAgentTest {

    private class FakeBridge : PhoneBridge {
        override val context: Context = ContextWrapper(null)
        override val actions = Actions(context)
        override val files = FileRepository(context)
        val notes = mutableListOf(Note(1, "buy oat milk", 0))
        var theme: ThemeMode? = null
        override fun visibleApps() = emptyList<AppInfo>()
        override fun findApps(query: String, limit: Int) = emptyList<AppInfo>()
        override fun launchApp(app: AppInfo) = true
        override fun notesSnapshot() = notes.toList()
        override fun addNote(text: String) { notes += Note(notes.size + 1L, text, 0) }
        override fun deleteNote(id: Long) = notes.removeIf { it.id == id }
        override fun memoriesSnapshot() = emptyList<String>()
        override fun remember(fact: String) {}
        override fun forget(index: Int) = false
        override fun applyTheme(mode: ThemeMode) { theme = mode }
        override fun startFocus(minutes: Int) {}
        override fun lockScreen() = false
        override fun calendarText(days: Int) = "No events."
        override fun notificationsText() = "No notifications."
    }

    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { (t, d) -> "event: $t\ndata: $d\n\n" }

    private val turn1 = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking your notes"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"notes_list","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_2","name":"set_theme","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"theme\": \"netr"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"unner\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private val turn2 = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":30,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"You have 1 note: buy oat milk."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":9}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun toolRoundTrip() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val headers = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { so ->
                    val input = so.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val len = Regex("(?i)content-length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(len)
                    var off = 0
                    while (off < len) off += input.read(body, off, len - off)
                    headers += head.toString()
                    bodies += body.decodeToString()
                    val payload = (if (bodies.size == 1) turn1 else turn2).toByteArray()
                    val out = so.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(payload)
                    out.flush()
                }
            }
        }
        try {
            val bridge = FakeBridge()
            val agent = ClaudeAgent(AgentTools(bridge), "http://127.0.0.1:${server.localPort}")
            val text = StringBuilder()
            val tools = mutableListOf<String>()
            agent.send(
                apiKey = "sk-test", model = "claude-opus-5-5", effort = "low", webSearch = true,
                system = ClaudeAgent.systemPrompt("Dee", listOf("likes tea"), false),
                userBlocks = listOf(BetaContentBlockParam.ofText(ClaudeAgent.userText("now: test", "what are my notes? also go netrunner"))),
                listener = object : ClaudeAgent.Listener {
                    override fun onText(delta: String) { text.append(delta) }
                    override fun onTool(summary: String, isError: Boolean) { tools += "$summary|$isError" }
                    override fun onNewTurn() {}
                },
            )

            assertEquals(2, bodies.size)
            assertEquals("Checking your notesYou have 1 note: buy oat milk.", text.toString())
            assertEquals(listOf("notes (1)|false", "theme Netrunner|false"), tools)
            assertEquals(ThemeMode.NETRUNNER, bridge.theme)

            val first = JSONObject(bodies[0])
            println("REQUEST 1:\n" + first.toString(2).take(3000))
            println("HEADERS 1:\n" + headers[0])
            assertEquals("claude-opus-5-5", first.getString("model"))
            assertEquals("default", first.getString("fallbacks"))
            assertEquals("low", first.getJSONObject("output_config").getString("effort"))
            assertTrue(first.getBoolean("stream"))
            val toolNames = (0 until first.getJSONArray("tools").length()).map { first.getJSONArray("tools").getJSONObject(it).getString("name") }
            assertTrue("notes_list" in toolNames && "web_search" in toolNames)
            assertTrue(headers[0].contains("server-side-fallback-2026-07-01"))

            val second = JSONObject(bodies[1])
            val msgs = second.getJSONArray("messages")
            println("REQUEST 2 messages:\n" + msgs.toString(2))
            assertEquals(3, msgs.length())
            assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
            val results = msgs.getJSONObject(2).getJSONArray("content")
            assertEquals("tool_result", results.getJSONObject(0).getString("type"))
            assertEquals("toolu_1", results.getJSONObject(0).getString("tool_use_id"))
            assertTrue(results.getJSONObject(0).toString().contains("buy oat milk"))
            assertEquals("toolu_2", results.getJSONObject(1).getString("tool_use_id"))
        } finally {
            server.close()
        }
    }
}
