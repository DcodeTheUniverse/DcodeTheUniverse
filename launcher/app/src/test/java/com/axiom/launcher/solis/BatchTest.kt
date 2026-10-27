package com.axiom.launcher.solis

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** The Message Batches calls against a local stand-in for api.anthropic.com. */
class BatchTest {
    private val seen: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
    private val keys: MutableList<String?> = Collections.synchronizedList(ArrayList())
    @Volatile private var status = "in_progress"
    @Volatile private var result = """{"custom_id":"axiom-1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"Here is the plan."}],"usage":{"input_tokens":1200,"output_tokens":300}}}}"""
    @Volatile private var resultsHost = "127.0.0.1"
    private val server: TestHttp = TestHttp { r ->
        seen += "${r.method} ${r.path}" to r.body
        keys += r.headers["x-api-key"]
        val out = when {
            r.method == "POST" && r.path == "/v1/messages/batches" -> """{"id":"msgbatch_01","processing_status":"in_progress"}"""
            r.path.endsWith("/results") -> "\n$result\n"
            r.path.endsWith("/cancel") -> """{"id":"msgbatch_01","processing_status":"canceling"}"""
            else -> JSONObject().put("id", "msgbatch_01").put("processing_status", status)
                .put("results_url", if (status == "ended") "http://$resultsHost:${server.port}/v1/messages/batches/msgbatch_01/results" else JSONObject.NULL).toString()
        }
        TestHttp.Res(200, out)
    }
    private val api = AnthropicBatches(OkHttpClient(), { "sk-ant-test" }, baseUrl = "http://127.0.0.1:${server.port}")

    @After fun stop() = server.close()

    @Test
    fun createsChecksAndReadsAResult() = runBlocking {
        val id = api.create("axiom-1", "claude-sonnet-x", "You are Solis.", "Plan my week", 4000)
        assertEquals("msgbatch_01", id)
        val req = JSONObject(seen.first().second).getJSONArray("requests").getJSONObject(0)
        assertEquals("axiom-1", req.getString("custom_id"))
        val params = req.getJSONObject("params")
        assertEquals("claude-sonnet-x", params.getString("model"))
        assertEquals(4000, params.getInt("max_tokens"))
        assertEquals("Plan my week", params.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(keys.all { it == "sk-ant-test" })

        assertEquals(BatchOutcome.Pending("in_progress"), api.outcome(id, "axiom-1"))
        status = "ended"
        assertEquals(BatchOutcome.Succeeded("Here is the plan.", 1200, 300), api.outcome(id, "axiom-1"))
        result = """{"custom_id":"axiom-1","result":{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens too large"}}}}"""
        assertEquals(BatchOutcome.Failed("max_tokens too large"), api.outcome(id, "axiom-1"))
        result = """{"custom_id":"axiom-1","result":{"type":"expired"}}"""
        assertTrue((api.outcome(id, "axiom-1") as BatchOutcome.Failed).reason.contains("24 hours"))
        api.cancel(id)
        assertTrue(seen.any { it.first == "POST /v1/messages/batches/msgbatch_01/cancel" })
    }

    @Test
    fun theKeyNeverGoesToAnotherHost() = runBlocking {
        status = "ended"
        resultsHost = "localhost" // same machine, different host name: refused before any request
        val before = seen.size
        assertEquals(BatchOutcome.Failed("Unexpected results address"), api.outcome("msgbatch_01", "axiom-1"))
        assertEquals("only the status call was made", before + 1, seen.size)
        try { api.outcome("../../etc", "x"); fail("bad ids are refused") } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun jobsSurviveARestart() {
        val j = BatchJob("1", "Plan", "Plan my week", "sys", "user", "claude-x", "Claude X", "msgbatch_01", 5L,
            com.axiom.launcher.solis.memory.Space.WORK, "work", 0.004, BatchState.DONE, threadId = "t1", costUsd = 0.003)
        assertEquals(j, BatchJob.fromJson(JSONObject(j.toJson().toString())))
    }
}
