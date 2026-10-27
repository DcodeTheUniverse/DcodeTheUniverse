package com.axiom.launcher.solis.coding

import com.axiom.launcher.solis.engine.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Where a coding hand-off stands, derived only from what GitHub shows. */
enum class JobState(val label: String, val terminal: Boolean = false) {
    SENT("Waiting for Claude"),
    WORKING("Claude is working"),
    READY("Changes ready"),
    REPLIED("Claude replied"),
    PR_OPEN("Pull request open"),
    CHECKS_FAILED("Checks failed"),
    ERROR("Claude hit a problem"),
    STALLED("No response yet"),
    MERGED("Merged", terminal = true),
    CLOSED("Closed", terminal = true),
}

/**
 * A coding task handed to Claude Code through a GitHub issue. Claude Code runs in the repository's GitHub
 * Actions on the user's Claude subscription; Axiom only files the request, follows the issue, branch, pull
 * request and checks, and reports what it sees.
 */
data class CodingJob(
    val repo: String,
    val issue: Int,
    val title: String,
    val url: String,
    /** The Solis thread that handed it off, if any. */
    val threadId: String?,
    val createdAt: Long,
    /** When state or detail last changed (polling stops a day after that). */
    val changedAt: Long,
    val state: JobState = JobState.SENT,
    val detail: String = "",
    val stepsDone: Int = 0,
    val stepsTotal: Int = 0,
    val branch: String? = null,
    val baseBranch: String? = null,
    val pr: Int? = null,
    val prUrl: String? = null,
    val headSha: String? = null,
    val checks: String? = null,
    val checksDone: Boolean = true,
    val changes: String? = null,
    val summary: String? = null,
    val mergeRequested: Boolean = false,
    val lastPolledAt: Long = 0,
    val nextPollAt: Long = 0,
    val pollError: String? = null,
    val watching: Boolean = true,
    /** When Claude was last asked (filing or a follow-up); silence is measured from here. */
    val askedAt: Long = createdAt,
    /** Our latest "@claude" follow-up comment: only Claude's comments after it describe the new run. */
    val triggerCommentId: Long = 0,
    /** Where that comment was posted when not on the issue (the pull request, for fixing checks). */
    val trackOn: Int? = null,
) {
    val id: String get() = "$repo#$issue"
    val active: Boolean get() = watching && !state.terminal

    fun toJson(): JSONObject = JSONObject().apply {
        put("repo", repo); put("issue", issue); put("title", title); put("url", url); put("threadId", threadId ?: JSONObject.NULL)
        put("createdAt", createdAt); put("changedAt", changedAt); put("state", state.name); put("detail", detail)
        put("stepsDone", stepsDone); put("stepsTotal", stepsTotal); put("branch", branch ?: JSONObject.NULL)
        put("baseBranch", baseBranch ?: JSONObject.NULL); put("pr", pr ?: JSONObject.NULL); put("prUrl", prUrl ?: JSONObject.NULL)
        put("headSha", headSha ?: JSONObject.NULL); put("checks", checks ?: JSONObject.NULL); put("checksDone", checksDone)
        put("changes", changes ?: JSONObject.NULL); put("summary", summary ?: JSONObject.NULL); put("mergeRequested", mergeRequested)
        put("lastPolledAt", lastPolledAt); put("watching", watching); put("askedAt", askedAt); put("triggerCommentId", triggerCommentId)
        put("trackOn", trackOn ?: JSONObject.NULL)
    }

    companion object {
        private fun JSONObject.s(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

        fun fromJson(o: JSONObject) = CodingJob(
            repo = o.getString("repo"), issue = o.getInt("issue"), title = o.optString("title"), url = o.optString("url"),
            threadId = o.s("threadId"), createdAt = o.optLong("createdAt"), changedAt = o.optLong("changedAt"),
            state = runCatching { JobState.valueOf(o.optString("state")) }.getOrDefault(JobState.SENT), detail = o.optString("detail"),
            stepsDone = o.optInt("stepsDone"), stepsTotal = o.optInt("stepsTotal"), branch = o.s("branch"), baseBranch = o.s("baseBranch"),
            pr = if (o.isNull("pr")) null else o.optInt("pr"), prUrl = o.s("prUrl"), headSha = o.s("headSha"), checks = o.s("checks"),
            checksDone = o.optBoolean("checksDone", true), changes = o.s("changes"), summary = o.s("summary"),
            mergeRequested = o.optBoolean("mergeRequested"), lastPolledAt = o.optLong("lastPolledAt"), watching = o.optBoolean("watching", true),
            askedAt = o.optLong("askedAt", o.optLong("createdAt")), triggerCommentId = o.optLong("triggerCommentId"),
            trackOn = if (o.isNull("trackOn") || !o.has("trackOn")) null else o.optInt("trackOn"),
        )
    }
}

/** Reads the progress comment that claude-code-action keeps updated on the issue. */
object ClaudeComments {
    enum class Status { WORKING, FINISHED, ERROR, OTHER }

    data class Tracking(
        val status: Status,
        val done: Int,
        val total: Int,
        val current: String?,
        val branch: String?,
        val pr: Int?,
        val summary: String?,
        val updatedAt: Long,
    )

    private val ERROR = Regex("(?i)claude (code )?(encountered an error|failed|errored)")
    private val FINISHED = Regex("(?i)claude (code )?(finished|completed|is done)")
    private val WORKING = Regex("(?i)claude (code )?is working|<img[^>]*spinner")
    private val CHECK = Regex("(?m)^\\s*[-*] \\[( |x|X)] (.+)$")
    private val BRANCH = Regex("claude/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")
    private val PULL = Regex("/pull/(\\d+)")

    fun isTracking(c: GhComment): Boolean =
        (c.isBot && (c.author.contains("claude", true) || c.body.contains("claude", true))) ||
            ERROR.containsMatchIn(c.body) || FINISHED.containsMatchIn(c.body) || WORKING.containsMatchIn(c.body)

    /** The most recently updated progress comment (a follow-up "@claude …" request starts a new one). */
    fun latest(comments: List<GhComment>): GhComment? = comments.filter { isTracking(it) }.maxByOrNull { maxOf(it.updatedAt, it.createdAt) }

    fun parse(c: GhComment): Tracking {
        val body = c.body
        val head = body.lineSequence().take(3).joinToString("\n")
        val status = when {
            ERROR.containsMatchIn(head) -> Status.ERROR
            FINISHED.containsMatchIn(head) -> Status.FINISHED
            WORKING.containsMatchIn(head) -> Status.WORKING
            ERROR.containsMatchIn(body) -> Status.ERROR
            FINISHED.containsMatchIn(body) -> Status.FINISHED
            WORKING.containsMatchIn(body) -> Status.WORKING
            else -> Status.OTHER
        }
        val items = CHECK.findAll(body).map { (it.groupValues[1] != " ") to plainText(it.groupValues[2]).trim() }.toList()
        val branch = BRANCH.findAll(body).map { it.value.trimEnd('.', ')', '`') }.firstOrNull()
        val summary = body.lineSequence().drop(1)
            .filterNot { CHECK.matches(it) || it.trim().startsWith("---") || it.contains("<img") || it.trim().startsWith("[") }
            .joinToString(" ")
            .let { plainText(it).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }
            .ifBlank { null }?.take(320)
        return Tracking(
            status = status,
            done = items.count { it.first },
            total = items.size,
            current = items.firstOrNull { !it.first }?.second?.take(80),
            branch = branch,
            pr = PULL.find(body)?.groupValues?.get(1)?.toIntOrNull(),
            summary = summary,
            updatedAt = maxOf(c.updatedAt, c.createdAt),
        )
    }
}

/**
 * Hands coding work to Claude Code via GitHub issues and follows it to a merged pull request. Polling
 * runs only while a job is active, backs off as work settles, and stops a day after the last change.
 * Merging is never automatic: Solis may only ask, and the user confirms in the UI.
 */
class CodingBridge(
    private val scope: CoroutineScope,
    val github: GitHubClient,
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val defaultRepo: () -> String?,
    private val online: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _jobs = MutableStateFlow<List<CodingJob>>(emptyList())
    val jobs: StateFlow<List<CodingJob>> = _jobs.asStateFlow()

    /** Called after a job's state or detail changed (old is null for a new job). */
    var onChange: ((old: CodingJob?, new: CodingJob) -> Unit)? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pollLock = Mutex()
    @Volatile private var started = false
    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Waits until saved jobs are back in memory (a background worker may start the process cold). */
    suspend fun awaitLoaded() = loaded.await()

    val configured: Boolean get() = github.configured

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            runCatching { load()?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { CodingJob.fromJson(a.getJSONObject(it)) } } } }
                .getOrNull()?.let { restored -> _jobs.update { cur -> cur + restored.filter { l -> cur.none { it.id == l.id } } } }
            loaded.complete(Unit)
            while (isActive) {
                pollDue()
                val next = _jobs.value.filter { it.active }.minOfOrNull { it.nextPollAt }
                if (next == null) wake.receive()
                else withTimeoutOrNull((next - clock()).coerceIn(2_000L, 10 * 60_000L)) { wake.receive() }
            }
        }
    }

    /** Polls every job that is due now. */
    suspend fun pollDue() {
        if (!online() || !configured) return
        val now = clock()
        for (j in _jobs.value.filter { it.active && it.nextPollAt <= now }) {
            runCatching { refresh(j.id) }
        }
    }

    fun job(id: String): CodingJob? = _jobs.value.firstOrNull { it.id == id }

    fun jobFor(repo: String, number: Int): CodingJob? = _jobs.value.firstOrNull { it.repo == repo && (it.issue == number || it.pr == number) }

    fun repoOrDefault(repo: String?): String =
        repo?.trim()?.removePrefix("https://github.com/")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: defaultRepo()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No repository given and no default repository is set (Settings › Coding)")

    /**
     * Files the task as an issue that mentions @claude (or, with [existingIssue], asks Claude again in a
     * comment) and starts tracking it.
     */
    suspend fun delegate(repo: String?, title: String, spec: String, threadId: String?, existingIssue: Int? = null): CodingJob {
        val r = repoOrDefault(repo)
        val now = clock()
        // A circuit breaker for loops (or instructions planted in content Solis read): a person rarely files this many.
        val recent = _jobs.value.count { now - it.askedAt < 3_600_000L }
        check(recent < MAX_PER_HOUR) { "Already handed $recent coding tasks to Claude in the last hour. Ask the user before sending more." }
        val job = if (existingIssue != null) {
            val issue = github.issue(r, existingIssue)
            val c = github.comment(r, existingIssue, "$TRIGGER $spec")
            (jobFor(r, existingIssue) ?: CodingJob(r, issue.number, issue.title, issue.url, threadId, now, now))
                .copy(state = JobState.SENT, detail = "Asked Claude again on #${issue.number}", watching = true, nextPollAt = now + FIRST_POLL_MS,
                    changedAt = now, mergeRequested = false, askedAt = now, triggerCommentId = c.id, trackOn = null)
        } else {
            val issue = github.createIssue(r, title.trim().take(120).ifBlank { spec.take(80) }, issueBody(spec, threadId))
            CodingJob(r, issue.number, issue.title, issue.url, threadId, now, now, detail = SENT_DETAIL, nextPollAt = now + FIRST_POLL_MS)
        }
        put(job)
        wake.trySend(Unit)
        return job
    }

    /** Re-reads the job from GitHub now and returns its new state. */
    suspend fun refresh(id: String): CodingJob = pollLock.withLock {
        val j = job(id) ?: throw IllegalArgumentException("No coding job $id")
        val now = clock()
        try {
            val next = observe(j, now)
            put(next)
            next
        } catch (e: GitHubException) {
            val failed = j.copy(lastPolledAt = now, nextPollAt = now + if (e.status == 429) 15 * 60_000L else 3 * 60_000L, pollError = e.message,
                watching = j.watching && !e.auth)
            put(failed)
            throw e
        }
    }

    private suspend fun observe(j: CodingJob, now: Long): CodingJob {
        val issue = github.issue(j.repo, j.issue)
        val tracking = ClaudeComments.latest(github.comments(j.repo, j.trackOn ?: j.issue).filter { it.id > j.triggerCommentId })?.let { ClaudeComments.parse(it) }
        var branch = tracking?.branch ?: j.branch
        if (branch == null && tracking != null && tracking.status != ClaudeComments.Status.WORKING) {
            branch = github.branches(j.repo, "claude/issue-${j.issue}-").maxOrNull()
        }
        val pull = when {
            j.pr != null -> github.pull(j.repo, j.pr)
            tracking?.pr != null -> runCatching { github.pull(j.repo, tracking.pr) }.getOrNull()
            branch != null -> github.pullsForBranch(j.repo, branch).firstOrNull()?.let { github.pull(j.repo, it.number) }
            else -> null
        }
        val checks = pull?.takeIf { it.state == "open" && it.headSha.isNotBlank() }?.let { github.checks(j.repo, it.headSha) }
        var base = j.baseBranch
        var changes = j.changes
        if (pull != null) {
            changes = changeLine(pull.additions, pull.deletions, pull.changedFiles)
        } else if (branch != null && tracking?.status == ClaudeComments.Status.FINISHED && (changes == null || branch != j.branch)) {
            if (base == null) base = github.repo(j.repo).defaultBranch
            val files = github.compare(j.repo, base, branch)
            changes = changeLine(files.sumOf { it.additions }, files.sumOf { it.deletions }, files.size)
        }

        val state = when {
            pull?.merged == true -> JobState.MERGED
            pull != null && pull.state == "closed" -> JobState.CLOSED
            pull == null && issue.state == "closed" && tracking?.status != ClaudeComments.Status.WORKING -> JobState.CLOSED
            tracking?.status == ClaudeComments.Status.ERROR -> JobState.ERROR
            tracking?.status == ClaudeComments.Status.WORKING || tracking?.status == ClaudeComments.Status.OTHER -> JobState.WORKING
            // Asked again and Claude hasn't picked it up yet: the old checks don't describe the new run.
            tracking == null && j.triggerCommentId > 0 && now - j.askedAt < STALL_MS -> JobState.SENT
            pull != null -> if ((checks?.failed ?: 0) > 0) JobState.CHECKS_FAILED else JobState.PR_OPEN
            tracking?.status == ClaudeComments.Status.FINISHED -> if (branch != null) JobState.READY else JobState.REPLIED
            now - j.askedAt > STALL_MS -> JobState.STALLED
            else -> JobState.SENT
        }
        val detail = when (state) {
            JobState.SENT -> if (j.trackOn != null) "Asked Claude to fix the failing checks; waiting for it to start"
                else if (j.triggerCommentId > 0) "Asked Claude again; waiting for it to start" else SENT_DETAIL
            JobState.STALLED -> "No reply from Claude after ${(now - j.askedAt) / 60_000} min. Check that the Claude workflow and its token are set up (Settings › Coding)."
            JobState.WORKING -> if (tracking != null && tracking.total > 0) "${tracking.done}/${tracking.total}" + (tracking.current?.let { " · $it" } ?: "")
                else "Claude is working on it"
            JobState.READY -> "Done on $branch" + (changes?.let { " · $it" } ?: "") + " · open a pull request to review"
            JobState.REPLIED -> "Claude replied without code changes" + (tracking?.summary?.let { ": ${it.take(160)}" } ?: "")
            JobState.PR_OPEN -> "PR #${pull!!.number}" + (changes?.let { " · $it" } ?: "") + " · " + (checks?.summary() ?: "no checks") +
                (if (pull.draft) " · draft" else "") + (if (pull.mergeable == false) " · has conflicts" else "")
            JobState.CHECKS_FAILED -> "PR #${pull!!.number} · ${checks!!.summary()}"
            JobState.ERROR -> "Claude stopped with an error" + (tracking?.summary?.let { ": ${it.take(160)}" } ?: "")
            JobState.MERGED -> "PR #${pull!!.number} merged"
            JobState.CLOSED -> if (pull != null) "PR #${pull.number} closed without merging" else "Issue closed"
        }
        val changed = state != j.state || detail != j.detail
        val changedAt = if (changed) now else j.changedAt
        val checksDone = checks?.done ?: true
        val interval = when (state) {
            JobState.SENT -> 30_000L
            JobState.WORKING -> 45_000L
            JobState.PR_OPEN -> if (checksDone) 5 * 60_000L else 60_000L
            JobState.CHECKS_FAILED -> 5 * 60_000L
            JobState.STALLED -> 15 * 60_000L
            else -> 10 * 60_000L
        }
        return j.copy(
            title = issue.title.ifBlank { j.title },
            state = state, detail = detail,
            stepsDone = tracking?.done ?: j.stepsDone, stepsTotal = tracking?.total ?: j.stepsTotal,
            branch = branch, baseBranch = base ?: pull?.baseRef?.ifBlank { null } ?: j.baseBranch,
            pr = pull?.number ?: j.pr, prUrl = pull?.url ?: j.prUrl, headSha = pull?.headSha ?: j.headSha,
            checks = checks?.summary() ?: j.checks.takeIf { pull != null }, checksDone = checksDone,
            changes = changes, summary = tracking?.summary ?: j.summary,
            mergeRequested = j.mergeRequested && state == JobState.PR_OPEN,
            lastPolledAt = now, nextPollAt = now + interval, pollError = null, changedAt = changedAt,
            watching = !state.terminal && now - changedAt < WATCH_MS,
        )
    }

    /** Opens a pull request from Claude's branch (claude-code-action leaves that to a person by default). */
    suspend fun openPullRequest(id: String, draft: Boolean = false): CodingJob {
        val j = refresh(id)
        j.pr?.let { return j }
        val branch = j.branch ?: throw IllegalStateException("Claude hasn't pushed a branch for #${j.issue} yet")
        val base = j.baseBranch ?: github.repo(j.repo).defaultBranch
        val body = buildString {
            append("Closes #${j.issue}\n\n")
            j.summary?.let { append(it).append("\n\n") }
            append("Opened from Axiom.")
        }
        github.openPull(j.repo, branch, base, j.title, body, draft)
        return refresh(id)
    }

    /**
     * Posts the failing checks (names, GitHub's summaries, first annotations) on the pull request for
     * Claude Code to fix on the same branch, and follows that run.
     */
    suspend fun askFix(id: String): CodingJob {
        val j = refresh(id)
        val pr = j.pr ?: throw IllegalStateException("There's no pull request with checks yet")
        val sha = j.headSha ?: github.pull(j.repo, pr).headSha
        val failures = github.failedChecks(j.repo, sha)
        check(failures.isNotEmpty()) { "No failed checks on PR #$pr right now" }
        val now = clock()
        val recent = _jobs.value.count { now - it.askedAt < 3_600_000L }
        check(recent < MAX_PER_HOUR) { "Already asked Claude $recent times in the last hour. Try again later." }
        val c = github.comment(j.repo, pr, fixRequest(j.branch, failures))
        val next = j.copy(state = JobState.SENT, detail = "Asked Claude to fix the failing checks; waiting for it to start", watching = true,
            nextPollAt = now + FIRST_POLL_MS, changedAt = now, mergeRequested = false, askedAt = now, triggerCommentId = c.id, trackOn = pr)
        put(next)
        wake.trySend(Unit)
        return next
    }

    /** The files a job changed, with patches (for the diff viewer). */
    suspend fun files(id: String): List<GhFile> {
        val j = job(id) ?: throw IllegalArgumentException("No coding job $id")
        return when {
            j.pr != null -> github.pullFiles(j.repo, j.pr)
            j.branch != null -> github.compare(j.repo, j.baseBranch ?: github.repo(j.repo).defaultBranch, j.branch)
            else -> emptyList()
        }
    }

    /** Solis can only ask; the user sees "Merge?" on the job and confirms it there. */
    fun requestMerge(id: String): CodingJob? {
        val j = job(id) ?: return null
        if (j.pr == null || j.state != JobState.PR_OPEN) return null
        return j.copy(mergeRequested = true).also { put(it) }
    }

    fun dismissMerge(id: String) { job(id)?.let { put(it.copy(mergeRequested = false)) } }

    /** Squash-merges after the user confirmed, only if the PR is still open, clean and unchanged since it was checked. */
    suspend fun merge(id: String): String {
        val j = refresh(id)
        val pr = j.pr ?: throw IllegalStateException("There is no pull request to merge yet")
        val p = github.pull(j.repo, pr)
        when {
            p.merged -> return "PR #$pr is already merged"
            p.state != "open" -> throw IllegalStateException("PR #$pr is closed")
            p.draft -> throw IllegalStateException("PR #$pr is a draft; mark it ready on GitHub first")
            p.mergeable == false -> throw IllegalStateException("PR #$pr has merge conflicts")
            j.state == JobState.CHECKS_FAILED -> throw IllegalStateException("Checks failed on PR #$pr; fix them or merge on GitHub")
        }
        val msg = github.merge(j.repo, pr, p.headSha)
        refresh(id)
        return msg
    }

    /** Changed files with line counts, and patches up to [maxChars] (for reviewing or explaining a change). */
    suspend fun diff(id: String, maxChars: Int = 12_000): String {
        val j = job(id) ?: throw IllegalArgumentException("No coding job $id")
        val files = when {
            j.pr != null -> github.pullFiles(j.repo, j.pr)
            j.branch != null -> github.compare(j.repo, j.baseBranch ?: github.repo(j.repo).defaultBranch, j.branch)
            else -> return "Claude hasn't pushed any changes for #${j.issue} yet."
        }
        if (files.isEmpty()) return "No file changes."
        val sb = StringBuilder("${files.size} files · +${files.sumOf { it.additions }} −${files.sumOf { it.deletions }}\n")
        files.forEach { sb.append("${it.status} ${it.path} (+${it.additions} −${it.deletions})\n") }
        for (f in files) {
            val patch = f.patch ?: continue
            if (sb.length + patch.length > maxChars) { sb.append("\n…(remaining patches omitted)"); break }
            sb.append("\n--- ${f.path}\n").append(patch).append('\n')
        }
        return sb.toString()
    }

    /** Starts following an existing issue or pull request someone else filed. */
    suspend fun track(repo: String?, number: Int, threadId: String?): CodingJob {
        val r = repoOrDefault(repo)
        jobFor(r, number)?.let { return refresh(it.id) }
        val issue = github.issue(r, number)
        val now = clock()
        put(CodingJob(r, issue.number, issue.title, issue.url, threadId, now, now, detail = "Following #${issue.number}", nextPollAt = now,
            pr = if (issue.isPull) issue.number else null))
        return refresh("$r#${issue.number}")
    }

    /** Checks the token, the repository, the Claude workflow and its secret; one line per finding. */
    suspend fun checkSetup(repo: String?): List<String> {
        val out = ArrayList<String>()
        val login = try { github.me() } catch (e: GitHubException) { return listOf("✗ ${e.message}") }
        out += "✓ Connected as @$login"
        val r = repo?.takeIf { it.isNotBlank() } ?: defaultRepo()?.takeIf { it.isNotBlank() }
            ?: return out + "• Pick a default repository so Solis knows where coding tasks go"
        val info = try { github.repo(r) } catch (e: GitHubException) { return out + "✗ $r: ${e.message}" }
        out += "✓ ${info.fullName} (default branch ${info.defaultBranch})"
        val flows = try { github.workflows(r) } catch (e: GitHubException) { null }
        val claude = flows?.firstOrNull { it.path.contains("claude", true) || it.name.contains("claude", true) }
        out += when {
            flows == null -> "• Couldn't list workflows (give the token Actions: read)"
            claude == null -> "✗ No Claude Code workflow yet: tap “Install workflow”, or run /install-github-app in Claude Code"
            claude.state != "active" -> "✗ ${claude.path} is ${claude.state.replace('_', ' ')}"
            else -> "✓ Workflow ${claude.path}"
        }
        val secrets = github.secretNames(r)
        out += when {
            secrets == null -> "• Couldn't check secrets (fine-grained tokens need Secrets: read). Make sure $SECRET is set."
            SECRET in secrets || "ANTHROPIC_API_KEY" in secrets -> "✓ Secret ${if (SECRET in secrets) SECRET else "ANTHROPIC_API_KEY"} is set"
            else -> "✗ Add the $SECRET secret (from `claude setup-token`) to the repository"
        }
        return out
    }

    /** Commits the Claude Code workflow to the default branch unless one is already there. */
    suspend fun installWorkflow(repo: String?): String {
        val r = repoOrDefault(repo)
        if (github.fileSha(r, WORKFLOW_PATH) != null) return "$WORKFLOW_PATH already exists in $r"
        github.createFile(r, WORKFLOW_PATH, WORKFLOW, "Add Claude Code workflow (from Axiom)")
        return "Added $WORKFLOW_PATH to $r. Now add the $SECRET secret and install the Claude GitHub app."
    }

    fun stopWatching(id: String) { job(id)?.let { put(it.copy(watching = false)) } }

    fun forget(id: String) {
        _jobs.update { l -> l.filterNot { it.id == id } }
        persist()
    }

    fun recheck(id: String) {
        job(id)?.let { put(it.copy(watching = true, nextPollAt = 0)) }
        wake.trySend(Unit)
    }

    /** Adds or replaces a job as-is (screenshots and tests). */
    internal fun debugPut(j: CodingJob) = put(j)

    private fun put(j: CodingJob) {
        var old: CodingJob? = null
        _jobs.update { list ->
            old = list.firstOrNull { it.id == j.id }
            if (old == null) listOf(j) + list else list.map { if (it.id == j.id) j else it }
        }
        persist()
        val o = old
        if (o == null || o.state != j.state || o.detail != j.detail || o.mergeRequested != j.mergeRequested) onChange?.invoke(o, j)
    }

    private fun persist() {
        val arr = JSONArray()
        _jobs.value.sortedByDescending { it.changedAt }.take(MAX_JOBS).forEach { arr.put(it.toJson()) }
        runCatching { save(arr.toString()) }
    }

    companion object {
        const val TRIGGER = "@claude"
        const val SENT_DETAIL = "Waiting for Claude Code to start on GitHub Actions"
        const val FIRST_POLL_MS = 20_000L
        const val STALL_MS = 12 * 60_000L
        const val WATCH_MS = 24 * 3600_000L
        const val MAX_JOBS = 60
        const val MAX_PER_HOUR = 6

        fun changeLine(add: Int, del: Int, files: Int) = "+$add −$del in $files file${if (files == 1) "" else "s"}"

        /** The "@claude, fix these checks" comment. */
        fun fixRequest(branch: String?, failures: List<GhFailure>): String = buildString {
            append(TRIGGER).append(" The checks on this pull request failed. Please find the cause, fix it on this branch")
            branch?.let { append(" (`").append(it).append("`)") }
            append(" and push. Read the failing jobs' logs; don't skip, disable or loosen tests to make them pass.\n\n")
            failures.forEach { f ->
                append("- **").append(f.name).append("**")
                if (f.title.isNotBlank()) append(": ").append(f.title)
                if (f.url.isNotBlank()) append(" ([details](").append(f.url).append("))")
                append('\n')
                f.summary.lines().filter { it.isNotBlank() }.take(6).forEach { append("  > ").append(it.take(200)).append('\n') }
                f.annotations.take(8).forEach { append("  - `").append(it.replace("`", "'")).append("`\n") }
            }
            append("\n<sub>Requested from Axiom.</sub>")
        }

        fun issueBody(spec: String, threadId: String?): String = buildString {
            append(TRIGGER).append(' ').append(spec.trim()).append("\n\n")
            append("<sub>Sent from Axiom by Solis").append(threadId?.let { " (task $it)" } ?: "")
            append(". Claude Code runs this in GitHub Actions on the repository owner's Claude subscription.</sub>")
        }

        /** The workflow file for claude-code-action (mirrors its documented setup). */
        val WORKFLOW = """
            name: Claude Code

            on:
              issue_comment:
                types: [created]
              pull_request_review_comment:
                types: [created]
              issues:
                types: [opened, assigned]
              pull_request_review:
                types: [submitted]

            jobs:
              claude:
                if: |
                  (github.event_name == 'issue_comment' && contains(github.event.comment.body, '@claude')) ||
                  (github.event_name == 'pull_request_review_comment' && contains(github.event.comment.body, '@claude')) ||
                  (github.event_name == 'pull_request_review' && contains(github.event.review.body, '@claude')) ||
                  (github.event_name == 'issues' && (contains(github.event.issue.body, '@claude') || contains(github.event.issue.title, '@claude')))
                runs-on: ubuntu-latest
                permissions:
                  contents: read
                  pull-requests: read
                  issues: read
                  id-token: write
                  actions: read
                steps:
                  - name: Checkout repository
                    uses: actions/checkout@v4
                    with:
                      fetch-depth: 1

                  - name: Run Claude Code
                    uses: anthropics/claude-code-action@v1
                    with:
                      claude_code_oauth_token: ${'$'}{{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
        """.trimIndent() + "\n"

        const val WORKFLOW_PATH = ".github/workflows/claude.yml"
        const val SECRET = "CLAUDE_CODE_OAUTH_TOKEN"
    }
}
