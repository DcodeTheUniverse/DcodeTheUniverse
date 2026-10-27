import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ToolCall
import com.axiom.launcher.solis.llm.ToolSpec
import com.axiom.launcher.solis.ondevice.GemmaTemplate
import com.axiom.launcher.solis.ondevice.LiteRtRuntime
import com.axiom.launcher.solis.ondevice.LocalEvent
import com.axiom.launcher.solis.ondevice.LocalRequest
import com.axiom.launcher.solis.ondevice.LocalRequests
import com.axiom.launcher.solis.ondevice.LocalStats
import org.json.JSONObject
import java.io.File
import java.util.Base64
import kotlin.concurrent.thread

/*
 * Runs the app's own LiteRtRuntime (compiled from the app sources) against a real Gemma 4 file, the way the
 * Solis engine drives it: stateless requests carrying the whole conversation, tools run by the caller.
 */

val SYSTEM = """
You are Solis, the AI built into Axiom, the user's Android home-screen launcher. You can act on the phone through tools: open apps, set timers and alarms, read the calendar, and use your own memory.

How to behave:
- Do, don't describe. When a tool can do what was asked, call it, then confirm in one short line.
- Replies are read on a phone: default to 1–4 sentences or a tight list.
- Use remember for lasting preferences or facts the user shares; use recall when past context would help.
""".trimIndent()

fun spec(name: String, desc: String, props: String, req: String) =
    ToolSpec(name, desc, """{"type":"object","properties":$props,"required":$req}""")

val TOOLS = listOf(
    spec("set_timer", "Start a countdown timer.", """{"seconds":{"type":"integer","description":"Length in seconds"},"label":{"type":"string","description":"What it's for"}}""", """["seconds"]"""),
    spec("set_alarm", "Set an alarm.", """{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}}""", """["hour","minute"]"""),
    spec("recall", "Search Solis's memory of the user.", """{"query":{"type":"string","description":"What to look for"}}""", """["query"]"""),
    spec("open_app", "Open an app by name.", """{"name":{"type":"string"}}""", """["name"]"""),
)

class Run(val text: String, val thought: String, val calls: List<ToolCall>, val stats: LocalStats?, val failed: LocalEvent.Failed?, val ms: Long)

var passed = 0
var failed = 0
fun check(ok: Boolean, what: String) { if (ok) passed++ else failed++; println((if (ok) "  PASS " else "  FAIL ") + what) }

fun main(args: Array<String>) {
    val modelPath = args[0]
    val template = File(args[1]).readText()
    val only = args.getOrElse(2) { "all" }
    val backend = "cpu"
    val ctx = 4096
    val cache = File(args.getOrElse(3) { "/tmp/litertlm-cache" })
    // "spec" as the fifth argument turns on speculative decoding on the CPU too (the app uses it on the GPU only).
    val spec = args.getOrElse(4) { "" } == "spec"
    val rt = LiteRtRuntime(cache, templates = { if (it == GemmaTemplate.ID) template else null }, speculativeOnCpu = spec, log = { println("    [rt] $it") })
    val images = File(cache, "img").apply { mkdirs() }
    var n = 0

    fun request(messages: List<ChatMessage>, tools: List<ToolSpec> = TOOLS, effort: Effort? = null, json: Boolean = false, maxOut: Int = 400, vision: Boolean = false, system: String = SYSTEM): LocalRequest =
        LocalRequests.from(
            ChatRequest(model = "google/gemma-4", system = system, messages = messages, tools = tools, maxOutputTokens = maxOut, effort = effort, jsonMode = json),
            id = "r${++n}", modelPath = modelPath, backend = backend, contextTokens = ctx, template = GemmaTemplate.ID,
            vision = vision, speculative = true, thinking = true, extraSystem = GemmaTemplate.TOOL_REPLY_RULE,
            saveImage = { img -> File(images, "i${img.base64.hashCode()}.png").also { it.writeBytes(Base64.getDecoder().decode(img.base64)) }.absolutePath },
        )

    fun run(label: String, r: LocalRequest, cancelAfterMs: Long? = null): Run {
        val t0 = System.currentTimeMillis()
        val text = StringBuilder(); val thought = StringBuilder(); val calls = ArrayList<ToolCall>()
        var stats: LocalStats? = null; var fail: LocalEvent.Failed? = null
        val canceller = cancelAfterMs?.let { ms -> thread { Thread.sleep(ms); rt.cancel() } }
        rt.generate(r, { ev ->
            when (ev) {
                is LocalEvent.Text -> text.append(ev.text)
                is LocalEvent.Thought -> thought.append(ev.text)
                is LocalEvent.Call -> calls += ToolCall("c${calls.size + 1}", ev.call.name, ev.call.argsJson)
                is LocalEvent.Done -> stats = ev.stats
                is LocalEvent.Failed -> fail = ev
            }
        }, { s, d -> println("    [state] $s $d") })
        canceller?.join()
        val ms = System.currentTimeMillis() - t0
        println("== $label (${ms} ms): text=<<${text.toString().take(300).replace("\n", " ")}>> calls=${calls.map { it.name + it.argumentsJson }} thought=${thought.length} chars stats=$stats failed=$fail")
        return Run(text.toString(), thought.toString(), calls, stats, fail, ms)
    }

    if (only == "all" || only == "loop") {
        println("--- tool loop, the way SolisEngine.agentLoop drives it")
        val msgs = arrayListOf(ChatMessage.user("Set a timer for 12 minutes for the pasta."))
        val a = run("round 1", request(msgs))
        check(a.failed == null && a.calls.size == 1 && a.calls[0].name == "set_timer", "calls set_timer")
        check(a.calls.firstOrNull()?.let { JSONObject(it.argumentsJson).optInt("seconds") == 720 } == true, "720 seconds")
        check(a.stats?.finish == "tool_calls", "finish = tool_calls")
        msgs += ChatMessage(ChatRole.ASSISTANT, if (a.text.isEmpty()) emptyList() else listOf(ContentPart.Text(a.text)), a.calls)
        a.calls.forEach { msgs += ChatMessage.toolResult(it, "Timer set · 12 min (pasta).", false) }
        val b = run("round 2 (tool result)", request(msgs))
        check(b.failed == null && b.text.isNotBlank() && b.calls.isEmpty(), "replies after the tool result")
        check((b.stats?.reusedTokens ?: 0) > 0, "kept the conversation in memory (reused ${b.stats?.reusedTokens} tokens)")
        msgs += ChatMessage.assistant(b.text)
        msgs += ChatMessage.user("What's my favourite pasta? Check your memory.")
        val c = run("next user turn", request(msgs))
        check(c.failed == null && c.calls.any { it.name == "recall" }, "calls recall")
        check((c.stats?.reusedTokens ?: 0) > (b.stats?.reusedTokens ?: 0), "reused the longer conversation (${c.stats?.reusedTokens} tokens)")
        msgs += ChatMessage(ChatRole.ASSISTANT, if (c.text.isEmpty()) emptyList() else listOf(ContentPart.Text(c.text)), c.calls)
        c.calls.forEach { msgs += ChatMessage.toolResult(it, "Memory (3 weeks ago): favourite pasta is cacio e pepe.", false) }
        val d = run("memory result", request(msgs))
        check(d.text.contains("cacio", ignoreCase = true), "answers from the tool result")

        println("--- a different request starts a fresh conversation, and the old one can be replayed")
        val e = run("unrelated", request(listOf(ChatMessage.user("In one sentence: what is a haiku?")), tools = emptyList()))
        check(e.failed == null && e.text.isNotBlank() && (e.stats?.reusedTokens ?: -1) == 0, "fresh conversation")
        val replay = ArrayList(msgs).apply { add(ChatMessage.assistant(d.text)); add(ChatMessage.user("And how long was the timer?")) }
        val f = run("replayed history", request(replay))
        // Gemma may look this up in memory instead of reading the history (it does the same in a live conversation),
        // so this is reported, not judged; the plain question below checks that the replayed history is there.
        println("  NOTE replayed history, open question: " + if (f.calls.isNotEmpty()) "the model chose ${f.calls.map { it.name }}" else "answered: ${f.text.take(80)}")
        val plain = ArrayList(msgs).apply { add(ChatMessage.assistant(d.text)); add(ChatMessage.user("Without using any tool: how many minutes was the pasta timer you set earlier in this chat?")) }
        val h = run("explicit question, replayed", request(plain))
        check(h.failed == null && (h.text.contains("12") || h.text.contains("twelve", true)), "reads the replayed history when asked plainly")
    }
    if (only == "all" || only == "think") {
        val r = run("thinking (effort HIGH)", request(listOf(ChatMessage.user("A bat and a ball cost 1.10 in total. The bat costs 1.00 more than the ball. How much is the ball? Answer briefly.")), tools = emptyList(), effort = Effort.HIGH, maxOut = 1500))
        check(r.thought.isNotEmpty(), "thought on its own channel (${r.thought.length} chars)")
        check(r.text.contains("0.05") || r.text.contains("5 cents") || r.text.contains("five cents", true), "right answer")
        val q = run("no thinking (effort LOW)", request(listOf(ChatMessage.user("Say hi in three words.")), tools = emptyList(), effort = Effort.LOW))
        check(q.thought.isEmpty() && q.text.isNotBlank(), "LOW effort doesn't think")
    }
    if (only == "all" || only == "json") {
        val r = run("json mode", request(listOf(ChatMessage.user("""Give two steps to make tea as {"steps":[...]}.""")), tools = emptyList(), json = true))
        check(runCatching { JSONObject(r.text.trim()) }.isSuccess, "valid JSON object")
    }
    if (only == "all" || only == "cancel") {
        val r = run("cancel after 3 s", request(listOf(ChatMessage.user("Write a detailed history of lighthouses, at least 800 words.")), tools = emptyList(), maxOut = 1200, system = "You are a writer. Always write long, complete, detailed texts."), cancelAfterMs = 3000)
        check(r.failed?.kind == "CANCELLED" && r.ms < 8000, "stops promptly with CANCELLED")
        val after = run("next request after cancel", request(listOf(ChatMessage.user("Say OK.")), tools = emptyList(), maxOut = 16))
        check(after.failed == null && after.text.isNotBlank(), "the runtime keeps working")
    }
    if (only == "all" || only == "vision") {
        val png = java.io.ByteArrayOutputStream().also { out ->
            val bi = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = bi.createGraphics(); g.color = java.awt.Color(20, 90, 220); g.fillRect(0, 0, 96, 96); g.dispose()
            javax.imageio.ImageIO.write(bi, "png", out)
        }.toByteArray()
        val img = ContentPart.Image("image/png", Base64.getEncoder().encodeToString(png))
        val r = run("image", request(listOf(ChatMessage(ChatRole.USER, listOf(img, ContentPart.Text("What colour fills this image? One word.")))), tools = emptyList(), vision = true))
        check(r.text.contains("blue", ignoreCase = true), "sees the image")
    }
    if (only == "all" || only == "limits") {
        val big = "lorem ipsum dolor sit amet ".repeat(800)
        val r = run("too long for the context", request(listOf(ChatMessage.user(big))))
        check(r.failed?.kind == "CONTEXT_LENGTH", "refuses with CONTEXT_LENGTH before loading anything")
    }
    rt.unload()
    println("RESULT: $passed passed, $failed failed")
}
