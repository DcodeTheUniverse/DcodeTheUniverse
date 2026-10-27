package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.CatalogParsers
import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.AnthropicProvider
import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.OpenAiCompatProvider
import com.axiom.launcher.solis.llm.ToolCall
import com.axiom.launcher.solis.llm.ToolSpec
import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt caching: breakpoints on the stable prefix for Claude models, cache-aware cost and usage. */
class PromptCacheTest {
    private val servers = ArrayList<MockServer>()
    @After fun tearDown() { servers.forEach { it.close() }; Rig.killAll() }

    private val tools = listOf(ToolSpec("set_timer", "Start a timer", """{"type":"object","properties":{"seconds":{"type":"integer"}},"required":["seconds"]}"""))
    private fun request(model: String, cache: Boolean) = ChatRequest(
        model = model, system = "You are Solis. ".repeat(40),
        messages = listOf(
            ChatMessage.user("Set a timer for a minute"),
            ChatMessage(ChatRole.ASSISTANT, emptyList(), listOf(ToolCall("c1", "set_timer", """{"seconds":60}"""))),
            ChatMessage.toolResult(ToolCall("c1", "set_timer", "{}"), "Timer set", false),
        ),
        tools = tools, cachePrefix = cache,
    )

    private fun openRouter() = OpenAiCompatProvider("openrouter", "OpenRouter", { "http://unused" }, { "k" }, OpenAiCompatProvider.Flavor.OPENROUTER, OkHttpClient())

    @Test
    fun openRouterMarksTheStablePrefixForClaudeModels() {
        val body = openRouter().buildBody(request("anthropic/claude-sonnet-5.5", cache = true))
        val msgs = body.getJSONArray("messages")
        val system = msgs.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        val sysPart = system.getJSONArray("content").getJSONObject(0)
        assertEquals("ephemeral", sysPart.getJSONObject("cache_control").getString("type"))
        val user = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("the last user turn carries the second breakpoint", "ephemeral",
            user.getJSONObject(user.length() - 1).getJSONObject("cache_control").getString("type"))
        assertEquals("only two breakpoints (max is four)", 2, Regex("cache_control").findAll(body.toString()).count())
        assertEquals("tool results stay plain", "tool", msgs.getJSONObject(3).getString("role"))
    }

    @Test
    fun noBreakpointsWhereTheyArentNeeded() {
        val other = openRouter().buildBody(request("openai/gpt-x", cache = true)).toString()
        assertFalse("other vendors cache automatically", other.contains("cache_control"))
        val off = openRouter().buildBody(request("anthropic/claude-haiku-5.5", cache = false))
        assertFalse(off.toString().contains("cache_control"))
        assertTrue("system stays a plain string", off.getJSONArray("messages").getJSONObject(0).get("content") is String)
        val local = OpenAiCompatProvider("local", "Local", { "http://x" }, { null }, OpenAiCompatProvider.Flavor.LOCAL, OkHttpClient())
        assertFalse(local.buildBody(request("anthropic/claude-sonnet-5.5", cache = true)).toString().contains("cache_control"))
    }

    @Test
    fun imagesKeepTheirPartsWhenTheTurnIsCached() {
        val req = request("anthropic/claude-sonnet-5.5", cache = true).copy(
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ContentPart.Image("image/jpeg", "AAAA"), ContentPart.Text("What is this?")))),
        )
        val content = openRouter().buildBody(req).getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(1).has("cache_control"))
    }

    @Test
    fun openRouterReportsCacheReadsAndWrites() {
        val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]," +
            "\"usage\":{\"prompt_tokens\":3200,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":2900,\"cache_write_tokens\":0},\"cost\":0.0012}}\n\ndata: [DONE]\n\n"
        val srv = MockServer { MockServer.sse(it, sse) }.also { servers += it }
        val p = OpenAiCompatProvider("openrouter", "OpenRouter", { srv.url }, { "k" }, OpenAiCompatProvider.Flavor.OPENROUTER, OkHttpClient())
        val usage = runBlocking { p.stream(request("anthropic/claude-sonnet-5.5", true)).toList() }.filterIsInstance<LlmEvent.Usage>().single()
        assertEquals(2900, usage.cachedInputTokens)
        assertEquals(3200, usage.inputTokens)
    }

    @Test
    fun anthropicDirectSendsSystemBreakpointAndAutomaticCaching() {
        val sse = buildString {
            fun ev(name: String, data: String) = append("event: $name\ndata: $data\n\n")
            ev("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":2900}}}""")
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done"}}""")
            ev("content_block_stop", """{"type":"content_block_stop","index":0}""")
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4,"input_tokens":12,"cache_creation_input_tokens":0,"cache_read_input_tokens":2900}}""")
            ev("message_stop", """{"type":"message_stop"}""")
        }
        val srv = MockServer { MockServer.sse(it, sse) }.also { servers += it }
        val p = AnthropicProvider({ "sk-ant-test" }, baseUrl = srv.url)
        val events = runBlocking { p.stream(request("claude-sonnet-5-5", cache = true)).toList() }
        val body = JSONObject(srv.body.get())
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        assertEquals("top-level automatic caching follows the conversation", "ephemeral", body.getJSONObject("cache_control").getString("type"))
        val usage = events.filterIsInstance<LlmEvent.Usage>().single()
        assertEquals(2900, usage.cachedInputTokens)
        assertEquals(12 + 2900, usage.inputTokens)
        assertEquals(FinishReason.STOP, events.filterIsInstance<LlmEvent.Finish>().single().reason)

        val plain = MockServer { MockServer.sse(it, sse) }.also { servers += it }
        runBlocking { AnthropicProvider({ "k" }, baseUrl = plain.url).stream(request("claude-sonnet-5-5", cache = false)).toList() }
        val pb = JSONObject(plain.body.get())
        assertFalse(pb.has("cache_control"))
        assertTrue("uncached system is a plain string", pb.get("system") is String)
    }

    @Test
    fun cacheAwareCostAndCatalogPrices() {
        val m = Models.m("anthropic/x", 1e-6, 5e-6, 50.0).copy(cacheReadPrice = 1e-7, cacheWritePrice = 1.25e-6)
        assertEquals(200 * 1e-6 + 800 * 1e-7 + 100 * 5e-6, m.estimateCost(1000, 100, cacheRead = 800, cacheWrite = 0)!!, 1e-12)
        assertEquals(800 * 1e-7 + 200 * 1.25e-6 + 100 * 5e-6, m.estimateCost(1000, 100, cacheRead = 800, cacheWrite = 200)!!, 1e-12)
        assertEquals(m.estimateCost(1000, 100), m.estimateCost(1000, 100, 0, 0))
        val noCachePrices = Models.m("acme/y", 1e-6, 5e-6, 50.0)
        assertEquals("without published cache prices, cached tokens cost normal input", noCachePrices.estimateCost(1000, 100), noCachePrices.estimateCost(1000, 100, 800, 0))

        val json = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "anthropic/claude-sonnet-5.5").put("name", "Claude Sonnet 5.5")
            .put("context_length", 1_000_000).put("pricing", JSONObject().put("prompt", "0.000002").put("completion", "0.00001")
                .put("input_cache_read", "0.0000002").put("input_cache_write", "0.0000025"))
            .put("supported_parameters", JSONArray().put("tools")))).toString()
        val parsed = CatalogParsers.openRouter(json).single()
        assertEquals(2e-7, parsed.cacheReadPrice!!, 1e-15)
        assertEquals(2.5e-6, parsed.cacheWritePrice!!, 1e-15)
        val back = CatalogParsers.fromCache(CatalogParsers.toCache(CatalogSnapshot("openrouter", 1L, listOf(parsed)))).models.single()
        assertEquals(parsed.cacheReadPrice, back.cacheReadPrice)
        assertEquals(parsed.cacheWritePrice, back.cacheWritePrice)
    }

    @Test
    fun engineCachesWorkerLoopsButNotOneOffCalls() {
        val p = FakeProvider(script = { r, _ ->
            when {
                r.system.startsWith("You are the coordinator") -> Script.text("not json")
                r.system == SolisEngine.NARRATOR_SYSTEM -> Script.text("Working on it")
                r.messages.last().role == ChatRole.TOOL -> Script.Reply(listOf(LlmEvent.TextDelta("Saved."), LlmEvent.Usage(3100, 10, cachedInputTokens = 2900, costUsd = 0.0004), LlmEvent.Finish(FinishReason.STOP)))
                else -> Script.Reply(listOf(LlmEvent.ToolCallReady(ToolCall("c", "note_add", """{"text":"pack"}""")), LlmEvent.Usage(3100, 10, cacheWriteTokens = 2900, costUsd = 0.001), LlmEvent.Finish(FinishReason.TOOL_CALLS)))
            }
        })
        val rig = Rig(providers = listOf(p)).start()
        val id = (rig.engine.submit("Plan and compare packing lists, research what to bring, then outline it and add a note to pack", Space.PERSONAL, "personal") as SubmitResult.Accepted).threadId
        awaitStatus(rig, id, ThreadStatus.DONE, ThreadStatus.FAILED, ThreadStatus.BLOCKED)
        val workers = p.requests.filter { it.tools.isNotEmpty() }
        assertTrue("worker calls happened", workers.isNotEmpty())
        assertTrue("worker tool loops are cached", workers.all { it.cachePrefix })
        assertTrue("the coordinator call is one-off", p.requests.filter { it.system.startsWith("You are the coordinator") }.none { it.cachePrefix })
        assertTrue("cache reads are counted on the thread", rig.thread(id).usage.cachedTokens >= 2900)
    }
}
