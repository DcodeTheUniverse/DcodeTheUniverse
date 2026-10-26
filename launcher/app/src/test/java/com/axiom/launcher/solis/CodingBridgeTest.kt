package com.axiom.launcher.solis

import com.axiom.launcher.solis.coding.ClaudeComments
import com.axiom.launcher.solis.coding.CodingBridge
import com.axiom.launcher.solis.coding.CodingJob
import com.axiom.launcher.solis.coding.CodingTools
import com.axiom.launcher.solis.coding.GhComment
import com.axiom.launcher.solis.coding.GitHubClient
import com.axiom.launcher.solis.coding.GitHubException
import com.axiom.launcher.solis.coding.JobState
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A small in-memory GitHub: issues, comments, Claude's branch, pull requests, check runs, workflows,
 * secrets and contents, served over real HTTP so the client's requests are exercised end to end.
 */
class FakeGitHub {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}"
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    val bodies: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    private val next = AtomicInteger(12)
    private val commentIds = java.util.concurrent.atomic.AtomicLong(1000)
    val issues = HashMap<Int, JSONObject>()
    val comments = HashMap<Int, MutableList<JSONObject>>()
    val branches = ArrayList<String>()
    val pulls = HashMap<Int, JSONObject>()
    var checkRuns = JSONArray()
    var compareFiles = JSONArray()
    var workflows = JSONArray()
    var secrets: JSONArray? = JSONArray()
    val files = HashMap<String, String>()
    @Volatile var forceStatus: Pair<Int, String>? = null

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val input = sock.getInputStream()
                    val h = StringBuilder()
                    while (!h.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; h.append(c.toChar()) }
                    val len = Regex("(?i)content-length: (\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                    val b = ByteArray(len); var off = 0
                    while (off < len) { val n = input.read(b, off, len - off); if (n < 0) break; off += n }
                    val line = h.lineSequence().first().split(' ')
                    val (code, body, extra) = synchronized(this) { route(line[0], line[1], b.decodeToString()) }
                    val out = body.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n${extra}Content-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(out); flush()
                    }
                }
                runCatching { sock.close() }
            }
        }
    }

    fun close() = server.close()

    fun issueJson(n: Int, title: String) = JSONObject().put("number", n).put("title", title).put("state", "open")
        .put("html_url", "https://github.com/octo/app/issues/$n").put("state_reason", JSONObject.NULL)

    fun comment(n: Int, author: String, body: String, at: String = "2026-10-26T10:00:00Z") {
        synchronized(this) {
            val list = comments.getOrPut(n) { ArrayList() }
            list.removeAll { it.getJSONObject("user").getString("login") == author && author.endsWith("[bot]") }
            list += JSONObject().put("id", commentIds.incrementAndGet()).put("user", JSONObject().put("login", author)).put("body", body)
                .put("created_at", "2026-10-26T09:59:00Z").put("updated_at", at).put("html_url", "https://github.com/octo/app/issues/$n#c")
        }
    }

    fun pull(n: Int, branch: String, sha: String, draft: Boolean = false, state: String = "open", merged: Boolean = false) = JSONObject()
        .put("number", n).put("title", "Add dark mode").put("state", state).put("merged", merged).put("draft", draft)
        .put("html_url", "https://github.com/octo/app/pull/$n").put("head", JSONObject().put("ref", branch).put("sha", sha))
        .put("base", JSONObject().put("ref", "main")).put("mergeable", true).put("mergeable_state", "clean")
        .put("additions", 30).put("deletions", 2).put("changed_files", 2)

    private fun route(method: String, target: String, body: String): Triple<Int, String, String> {
        log += "$method $target"
        if (body.isNotBlank()) bodies += JSONObject(body)
        forceStatus?.let { (c, extra) -> return Triple(c, """{"message":"forced"}""", extra) }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val repo = "/repos/octo/app"
        fun ok(json: Any) = Triple(200, json.toString(), "")
        Regex("^$repo/issues/(\\d+)/comments$").find(path)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (method == "POST") {
                comment(n, "octo", JSONObject(body).getString("body"))
                return Triple(201, comments[n]!!.last().toString(), "")
            }
            return ok(JSONArray(comments[n].orEmpty()))
        }
        Regex("^$repo/issues/(\\d+)$").find(path)?.let { m -> return issues[m.groupValues[1].toInt()]?.let { ok(it) } ?: Triple(404, """{"message":"Not Found"}""", "") }
        Regex("^$repo/git/matching-refs/heads/(.+)$").find(path)?.let { m ->
            return ok(JSONArray(branches.filter { it.startsWith(m.groupValues[1]) }.map { JSONObject().put("ref", "refs/heads/$it") }))
        }
        Regex("^$repo/pulls/(\\d+)/merge$").find(path)?.let { m ->
            val pr = pulls[m.groupValues[1].toInt()]!!
            if (JSONObject(body).optString("sha") != pr.getJSONObject("head").getString("sha")) return Triple(409, """{"message":"Head branch was modified"}""", "")
            pr.put("merged", true).put("state", "closed")
            return ok(JSONObject().put("merged", true).put("message", "Pull Request successfully merged"))
        }
        Regex("^$repo/pulls/(\\d+)/files$").find(path)?.let { return ok(compareFiles) }
        Regex("^$repo/pulls/(\\d+)$").find(path)?.let { m -> return ok(pulls[m.groupValues[1].toInt()]!!) }
        if (path == "$repo/pulls" && method == "POST") {
            val o = JSONObject(body)
            val n = next.getAndIncrement()
            pulls[n] = pull(n, o.getString("head"), "sha-1", draft = o.optBoolean("draft"))
            return Triple(201, pulls[n].toString(), "")
        }
        if (path == "$repo/pulls") {
            val head = java.net.URLDecoder.decode(query.split('&').first { it.startsWith("head=") }.substringAfter('='), "UTF-8").substringAfter(':')
            return ok(JSONArray(pulls.values.filter { it.getJSONObject("head").getString("ref") == head }))
        }
        if (path == "$repo/issues" && method == "POST") {
            val o = JSONObject(body)
            val n = next.getAndIncrement()
            issues[n] = issueJson(n, o.getString("title")).put("body", o.getString("body"))
            return Triple(201, issues[n].toString(), "")
        }
        Regex("^$repo/compare/(.+)$").find(path)?.let { return ok(JSONObject().put("files", compareFiles)) }
        Regex("^$repo/commits/([^/]+)/check-runs$").find(path)?.let { return ok(JSONObject().put("total_count", checkRuns.length()).put("check_runs", checkRuns)) }
        if (path == "$repo/actions/workflows") return ok(JSONObject().put("workflows", workflows))
        if (path == "$repo/actions/secrets") return secrets?.let { ok(JSONObject().put("secrets", it)) } ?: Triple(403, """{"message":"Resource not accessible by personal access token"}""", "")
        Regex("^$repo/contents/(.+)$").find(path)?.let { m ->
            val f = m.groupValues[1]
            if (method == "PUT") { files[f] = JSONObject(body).getString("content"); return Triple(201, "{}", "") }
            return if (f in files) ok(JSONObject().put("sha", "blob1")) else Triple(404, """{"message":"Not Found"}""", "")
        }
        if (path == repo) return ok(JSONObject().put("full_name", "octo/app").put("private", false).put("default_branch", "main").put("description", "demo").put("pushed_at", "2026-10-25T10:00:00Z"))
        if (path == "/user") return ok(JSONObject().put("login", "octo"))
        if (path == "/user/repos") return ok(JSONArray().put(JSONObject().put("full_name", "octo/app").put("private", false).put("default_branch", "main").put("description", "demo").put("pushed_at", "2026-10-25T10:00:00Z")))
        return Triple(404, """{"message":"Not Found: $path"}""", "")
    }

    companion object {
        const val BRANCH = "claude/issue-12-20261026-1015"
        val WORKING = "**Claude Code is working…** <img src=\"https://github.com/user-attachments/assets/spinner.gif\" width=\"14px\" height=\"14px\" />\n\n" +
            "### Todo List\n- [x] Read the settings screen\n- [ ] Add the dark mode toggle\n- [ ] Add tests\n"
        val FINISHED = "**Claude finished @octo's task in 2m 10s** —— [View job](https://github.com/octo/app/actions/runs/1) —— " +
            "[`$BRANCH`](https://github.com/octo/app/tree/$BRANCH) —— [Create PR ➔](https://github.com/octo/app/compare/main...$BRANCH?quick_pull=1)\n\n" +
            "- [x] Read the settings screen\n- [x] Add the dark mode toggle\n- [x] Add tests\n\nAdded a dark mode toggle to Settings with a test."
    }
}

class CodingBridgeTest {
    private val gh = FakeGitHub()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saved: String? = null
    private var now = 1_000_000L
    private val changes: MutableList<Pair<CodingJob?, CodingJob>> = Collections.synchronizedList(ArrayList())
    private var token: String? = "ghp_test"
    private val client = GitHubClient(OkHttpClient(), { token }, baseUrl = gh.url)
    private val bridge = CodingBridge(scope, client, { saved }, { saved = it }, { "octo/app" }, clock = { now }).also {
        it.onChange = { o, n -> changes += o to n }
    }

    @After fun tearDown() { gh.close(); scope.cancel(); Rig.killAll() }

    private fun checks(vararg runs: Pair<String, String?>) {
        gh.checkRuns = JSONArray(runs.map { (name, conclusion) ->
            JSONObject().put("name", name).put("status", if (conclusion == null) "in_progress" else "completed").put("conclusion", conclusion ?: JSONObject.NULL)
        })
    }

    @Test
    fun delegateFilesAnIssueForClaudeAndFollowsItToMerged() = runBlocking {
        val job = bridge.delegate(null, "Add dark mode", "Add a dark mode toggle to Settings. Acceptance: it persists.", threadId = "t1")
        assertEquals("octo/app#12", job.id)
        assertEquals(JobState.SENT, job.state)
        val issueBody = gh.issues[12]!!.getString("body")
        assertTrue("the issue mentions @claude first", issueBody.startsWith("@claude Add a dark mode toggle"))
        assertTrue(issueBody.contains("task t1"))
        assertTrue("persisted", saved!!.contains("octo/app"))

        assertEquals(JobState.SENT, bridge.refresh(job.id).state)

        gh.comment(12, "claude[bot]", FakeGitHub.WORKING)
        val working = bridge.refresh(job.id)
        assertEquals(JobState.WORKING, working.state)
        assertEquals(1, working.stepsDone)
        assertEquals(3, working.stepsTotal)
        assertTrue(working.detail, working.detail.contains("Add the dark mode toggle"))

        gh.comment(12, "claude[bot]", FakeGitHub.FINISHED, at = "2026-10-26T10:03:00Z")
        gh.branches += FakeGitHub.BRANCH
        gh.compareFiles = JSONArray().put(JSONObject().put("filename", "Settings.kt").put("status", "modified").put("additions", 24).put("deletions", 2).put("patch", "@@ +toggle"))
            .put(JSONObject().put("filename", "SettingsTest.kt").put("status", "added").put("additions", 6).put("deletions", 0).put("patch", "@@ +test"))
        val ready = bridge.refresh(job.id)
        assertEquals(JobState.READY, ready.state)
        assertEquals(FakeGitHub.BRANCH, ready.branch)
        assertEquals("+30 −2 in 2 files", ready.changes)
        assertTrue(ready.summary!!.contains("Added a dark mode toggle"))

        checks("build" to null)
        val opened = bridge.openPullRequest(job.id)
        assertEquals(JobState.PR_OPEN, opened.state)
        assertFalse("checks still running", opened.checksDone)
        val prBody = gh.bodies.last { it.has("head") }
        assertEquals(FakeGitHub.BRANCH, prBody.getString("head"))
        assertEquals("main", prBody.getString("base"))
        assertTrue(prBody.getString("body").startsWith("Closes #12"))

        checks("build" to "success", "lint" to "success")
        val green = bridge.refresh(job.id)
        assertTrue(green.checksDone)
        assertTrue(green.detail, green.detail.contains("all 2 checks passed"))

        assertNotNull(bridge.requestMerge(job.id))
        assertTrue(bridge.job(job.id)!!.mergeRequested)
        val msg = bridge.merge(job.id)
        assertEquals("Pull Request successfully merged", msg)
        val merged = bridge.job(job.id)!!
        assertEquals(JobState.MERGED, merged.state)
        assertFalse("merged jobs stop polling", merged.active)
        assertTrue("merged with the head sha it checked", gh.bodies.any { it.optString("sha") == "sha-1" && it.optString("merge_method") == "squash" })

        val states = changes.map { it.second.state }.distinct()
        assertEquals(listOf(JobState.SENT, JobState.WORKING, JobState.READY, JobState.PR_OPEN, JobState.MERGED), states)

        // A restarted launcher picks the jobs up again.
        val again = CodingBridge(scope, client, { saved }, { }, { "octo/app" }, clock = { now })
        again.start()
        awaitUntil(3000, "reload") { again.jobs.value.any { it.id == job.id && it.state == JobState.MERGED } }
    }

    @Test
    fun failedChecksErrorsAndSilenceAreReportedNotHidden() = runBlocking {
        val job = bridge.delegate("octo/app", "Fix crash", "Fix the crash on launch.", threadId = null)
        now += 13 * 60_000L
        val stalled = bridge.refresh(job.id)
        assertEquals(JobState.STALLED, stalled.state)
        assertTrue(stalled.detail.contains("Settings › Coding"))

        gh.comment(12, "claude[bot]", "**Claude encountered an error after 1m 2s** —— [View job](https://github.com/octo/app/actions/runs/2)\n\nThe CLAUDE_CODE_OAUTH_TOKEN secret is invalid.")
        val err = bridge.refresh(job.id)
        assertEquals(JobState.ERROR, err.state)
        assertTrue(err.detail.contains("secret is invalid"))

        // Asking Claude again (a new @claude comment) starts a fresh run that we follow; until Claude answers,
        // the old error comment no longer describes the job.
        bridge.delegate(null, "", "Please try again.", threadId = null, existingIssue = 12)
        assertTrue(gh.comments[12]!!.any { it.getString("body") == "@claude Please try again." })
        val waiting = bridge.refresh(job.id)
        assertEquals(JobState.SENT, waiting.state)
        assertTrue(waiting.detail, waiting.detail.contains("Asked Claude again"))
        gh.comment(12, "claude[bot]", FakeGitHub.FINISHED, at = "2026-10-26T11:00:00Z")
        gh.branches += FakeGitHub.BRANCH
        gh.pulls[40] = gh.pull(40, FakeGitHub.BRANCH, "sha-9")
        checks("build" to "failure", "lint" to "success")
        val failed = bridge.refresh(job.id)
        assertEquals(JobState.CHECKS_FAILED, failed.state)
        assertTrue(failed.detail, failed.detail.contains("1 of 2 checks failed (build)"))
        assertNull("can't ask to merge a failing PR", bridge.requestMerge(job.id))
        try {
            bridge.merge(job.id)
            fail("merge must refuse failing checks")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Checks failed"))
        }
        assertFalse(gh.log.any { it.endsWith("/merge") })
    }

    @Test
    fun mergeRefusesDraftsAndPinsTheHeadItChecked() = runBlocking {
        val job = bridge.delegate(null, "Docs", "Update the README.", threadId = null)
        gh.comment(12, "claude[bot]", FakeGitHub.FINISHED)
        gh.branches += FakeGitHub.BRANCH
        gh.pulls[50] = gh.pull(50, FakeGitHub.BRANCH, "sha-a", draft = true)
        checks("build" to "success")
        bridge.refresh(job.id)
        try { bridge.merge(job.id); fail("draft") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("draft")) }
        assertFalse(gh.log.any { it.endsWith("/merge") })
        // A new push lands before the user taps Merge: the merge carries the head it just re-checked,
        // so GitHub refuses it if anything changes again in between.
        gh.pulls[50] = gh.pull(50, FakeGitHub.BRANCH, "sha-b")
        assertEquals("Pull Request successfully merged", bridge.merge(job.id))
        assertTrue(gh.bodies.any { it.optString("sha") == "sha-b" })
        assertTrue(gh.bodies.none { it.optString("sha") == "sha-a" })
    }

    @Test
    fun setupCheckAndWorkflowInstall() = runBlocking {
        var lines = bridge.checkSetup(null)
        assertTrue(lines.toString(), lines[0].contains("@octo"))
        assertTrue(lines.toString(), lines.any { it.startsWith("✗ No Claude Code workflow") })
        assertTrue(lines.toString(), lines.any { it.startsWith("✗ Add the CLAUDE_CODE_OAUTH_TOKEN") })

        assertTrue(bridge.installWorkflow(null).startsWith("Added .github/workflows/claude.yml"))
        val yaml = String(java.util.Base64.getDecoder().decode(gh.files[".github/workflows/claude.yml"]))
        assertTrue(yaml.contains("anthropics/claude-code-action@v1"))
        assertTrue(yaml.contains("claude_code_oauth_token: \${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}"))
        assertTrue("never overwrites", bridge.installWorkflow(null).contains("already exists"))

        gh.workflows = JSONArray().put(JSONObject().put("name", "Claude Code").put("path", ".github/workflows/claude.yml").put("state", "active"))
        gh.secrets = JSONArray().put(JSONObject().put("name", "CLAUDE_CODE_OAUTH_TOKEN"))
        lines = bridge.checkSetup(null)
        assertTrue(lines.toString(), lines.none { it.startsWith("✗") })
        gh.secrets = null
        assertTrue(bridge.checkSetup(null).any { it.contains("Couldn't check secrets") })
    }

    @Test
    fun githubErrorsAreReadable() = runBlocking {
        gh.forceStatus = 401 to ""
        try { client.me(); fail() } catch (e: GitHubException) { assertTrue(e.auth); assertTrue(e.message!!.contains("rejected the token")) }
        gh.forceStatus = 403 to "x-ratelimit-remaining: 0\r\nx-ratelimit-reset: 1761480000\r\n"
        try { client.me(); fail() } catch (e: GitHubException) { assertEquals(429, e.status); assertTrue(e.message!!.contains("rate limit")) }
        gh.forceStatus = null
        token = null
        try { client.me(); fail() } catch (e: GitHubException) { assertTrue(e.message!!.contains("Settings › Coding")) }
        try { client.issue("not a repo", 1); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("owner/name")) }
    }

    @Test
    fun trackingCommentParsing() {
        fun c(body: String, author: String = "claude[bot]") = GhComment(1, author, body, 0, 5, "")
        val w = ClaudeComments.parse(c(FakeGitHub.WORKING))
        assertEquals(ClaudeComments.Status.WORKING, w.status)
        assertEquals(1 to 3, w.done to w.total)
        assertEquals("Add the dark mode toggle", w.current)
        val f = ClaudeComments.parse(c(FakeGitHub.FINISHED))
        assertEquals(ClaudeComments.Status.FINISHED, f.status)
        assertEquals(FakeGitHub.BRANCH, f.branch)
        // The user's own "@claude …" request is not Claude's progress comment.
        assertFalse(ClaudeComments.isTracking(c("@claude please also add tests", author = "octo")))
        assertNull(ClaudeComments.latest(listOf(c("@claude do it", author = "octo"))))
        val newest = ClaudeComments.latest(listOf(c(FakeGitHub.FINISHED).copy(id = 1, updatedAt = 10), c(FakeGitHub.WORKING).copy(id = 2, updatedAt = 20)))
        assertEquals(2L, newest!!.id)
    }

    @Test
    fun solisHandsCodingToClaudeThroughTheTool() {
        val tools = CodingTools(bridge)
        token = null
        assertTrue("no coding tools until GitHub is connected", tools.tools().isEmpty())
        token = "ghp_test"
        val workerCalls = AtomicInteger()
        val p = FakeProvider(script = { r, _ ->
            when {
                r.system.startsWith("You are the coordinator") -> Script.text(
                    """{"tier":"T1","confidence":0.9,"ambiguity":0,"risk":"LOW","domain":"code","needs":["TOOLS"],""" +
                        """"steps":[{"id":"s1","goal":"Hand the dark mode change to Claude Code","dependsOn":[],"tier":"T1","needs":["TOOLS"]}],"clarify":null,"reasons":["hand-off"]}""")
                r.tools.isEmpty() -> Script.text("Handing it to Claude Code")
                workerCalls.getAndIncrement() == 0 -> Script.tool("code_delegate",
                    JSONObject().put("title", "Add dark mode").put("spec", "Add a dark mode toggle to Settings; keep the user's choice.").toString())
                else -> Script.text("Sent to Claude Code as octo/app#12. I'll let you know when it's ready.")
            }
        })
        val rig = Rig(providers = listOf(p), extra = tools).start()
        val id = (rig.engine.submit("Add dark mode to my app repo", Space.PERSONAL, "personal") as SubmitResult.Accepted).threadId
        awaitStatus(rig, id, ThreadStatus.DONE)
        assertTrue("system prompt explains the hand-off", rig.thread(id).system.contains("code_delegate"))
        assertTrue("the worker was offered the coding tools", p.requests.first { it.tools.isNotEmpty() }.tools.any { it.name == "code_delegate" })
        assertTrue(rig.thread(id).toolNames.containsAll(listOf("code_delegate", "code_status", "code_request_merge")))
        assertEquals(ToolGroup.CODE, tools.tools().first { it.name == "code_delegate" }.group)
        val job = bridge.jobs.value.single()
        assertEquals(id, job.threadId)
        assertTrue(gh.issues[12]!!.getString("body").startsWith("@claude Add a dark mode toggle"))
        assertTrue(rig.engine.transcript(id).value.any { it.kind == EntryKind.TOOL && it.text.contains("sent to Claude Code") })
        // Updates about the job land in the thread's history.
        rig.engine.note(id, "Claude Code · Changes ready")
        assertTrue(rig.engine.transcript(id).value.any { it.kind == EntryKind.STATUS && it.text.contains("Changes ready") })
        // No merge tool exists for the model; only the user-confirmed path can merge.
        assertTrue(tools.tools().none { it.name.contains("merge") && it.name != "code_request_merge" })
    }

    @Test
    fun handOffsAreCappedPerHour() = runBlocking {
        repeat(CodingBridge.MAX_PER_HOUR) { i -> bridge.delegate(null, "Task $i", "Do thing $i", threadId = "t") }
        try {
            bridge.delegate(null, "One more", "Do one more", threadId = "t")
            fail("cap")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("last hour"))
        }
        now += 3_600_001L
        assertEquals(JobState.SENT, bridge.delegate(null, "Later", "Do it later", threadId = "t").state)
    }
}
