package com.axiom.launcher.solis.coding

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GhRepo(val fullName: String, val private: Boolean, val defaultBranch: String, val description: String, val pushedAt: Long)
data class GhIssue(val number: Int, val title: String, val state: String, val url: String, val stateReason: String?, val isPull: Boolean = false)
data class GhComment(val id: Long, val author: String, val body: String, val createdAt: Long, val updatedAt: Long, val url: String) {
    val isBot: Boolean get() = author.endsWith("[bot]")
}
data class GhPull(
    val number: Int,
    val title: String,
    val state: String,
    val merged: Boolean,
    val draft: Boolean,
    val url: String,
    val headRef: String,
    val headSha: String,
    val baseRef: String,
    /** null while GitHub is still computing it. */
    val mergeable: Boolean?,
    val mergeableState: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
)
data class GhFile(val path: String, val status: String, val additions: Int, val deletions: Int, val patch: String?)
data class GhChecks(val total: Int, val passed: Int, val failed: Int, val pending: Int, val failedNames: List<String>) {
    val done: Boolean get() = pending == 0
    fun summary(): String = when {
        total == 0 -> "no checks"
        pending > 0 -> "$pending of $total checks running" + if (failed > 0) ", $failed failed" else ""
        failed > 0 -> "$failed of $total checks failed (${failedNames.take(3).joinToString()})"
        else -> "all $total checks passed"
    }
}
data class GhWorkflow(val name: String, val path: String, val state: String)

/** A GitHub API failure, with a message a person can act on. */
class GitHubException(val status: Int, message: String) : Exception(message) {
    val auth: Boolean get() = status == 401
    val notFound: Boolean get() = status == 404
}

/**
 * The few GitHub REST calls the coding bridge needs: repositories, issues and comments, Claude's branches,
 * pull requests, check runs, workflows and one-file commits. Uses the user's token; nothing else.
 */
class GitHubClient(
    private val http: OkHttpClient,
    private val token: () -> String?,
    /** Cheap presence check (no decryption), used on hot paths such as building a task's tool list. */
    private val hasToken: () -> Boolean = { !token().isNullOrBlank() },
    private val baseUrl: String = API,
) {
    val configured: Boolean get() = hasToken()

    suspend fun me(): String = JSONObject(get(url("user"))).getString("login")

    /** The user's repositories, most recently pushed first. */
    suspend fun repos(limit: Int = 60): List<GhRepo> {
        val u = url("user/repos").newBuilder().addQueryParameter("sort", "pushed").addQueryParameter("per_page", "${limit.coerceIn(1, 100)}")
            .addQueryParameter("affiliation", "owner,collaborator,organization_member").build()
        val arr = JSONArray(get(u))
        return (0 until arr.length()).map { repo(arr.getJSONObject(it)) }
    }

    suspend fun repo(fullName: String): GhRepo = repo(JSONObject(get(url("repos/${check(fullName)}"))))

    suspend fun createIssue(repo: String, title: String, body: String): GhIssue =
        issue(JSONObject(send("POST", url("repos/${check(repo)}/issues"), JSONObject().put("title", title).put("body", body))))

    suspend fun issue(repo: String, number: Int): GhIssue = issue(JSONObject(get(url("repos/${check(repo)}/issues/$number"))))

    suspend fun comment(repo: String, number: Int, body: String): GhComment =
        comment(JSONObject(send("POST", url("repos/${check(repo)}/issues/$number/comments"), JSONObject().put("body", body))))

    suspend fun comments(repo: String, number: Int): List<GhComment> {
        val u = url("repos/${check(repo)}/issues/$number/comments").newBuilder().addQueryParameter("per_page", "100").build()
        val arr = JSONArray(get(u))
        return (0 until arr.length()).map { comment(arr.getJSONObject(it)) }
    }

    /** Branch names starting with [prefix] (for example Claude's `claude/issue-12-`). */
    suspend fun branches(repo: String, prefix: String): List<String> {
        val arr = JSONArray(get(url("repos/${check(repo)}/git/matching-refs/heads/$prefix")))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("ref").removePrefix("refs/heads/") }
    }

    suspend fun pullsForBranch(repo: String, branch: String): List<GhPull> {
        val owner = check(repo).substringBefore('/')
        val u = url("repos/$repo/pulls").newBuilder().addQueryParameter("state", "all").addQueryParameter("head", "$owner:$branch").build()
        val arr = JSONArray(get(u))
        return (0 until arr.length()).map { pull(arr.getJSONObject(it)) }
    }

    suspend fun pull(repo: String, number: Int): GhPull = pull(JSONObject(get(url("repos/${check(repo)}/pulls/$number"))))

    suspend fun pullFiles(repo: String, number: Int): List<GhFile> {
        val u = url("repos/${check(repo)}/pulls/$number/files").newBuilder().addQueryParameter("per_page", "100").build()
        val arr = JSONArray(get(u))
        return (0 until arr.length()).map { file(arr.getJSONObject(it)) }
    }

    /** Files changed on [head] relative to [base] (for a branch that has no pull request yet). */
    suspend fun compare(repo: String, base: String, head: String): List<GhFile> {
        val o = JSONObject(get(url("repos/${check(repo)}/compare/$base...$head")))
        val arr = o.optJSONArray("files") ?: JSONArray()
        return (0 until arr.length()).map { file(arr.getJSONObject(it)) }
    }

    suspend fun checks(repo: String, sha: String): GhChecks {
        val u = url("repos/${check(repo)}/commits/$sha/check-runs").newBuilder().addQueryParameter("per_page", "100").build()
        val runs = JSONObject(get(u)).optJSONArray("check_runs") ?: JSONArray()
        var passed = 0; var failed = 0; var pending = 0
        val failedNames = ArrayList<String>()
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("status") != "completed") { pending++; continue }
            when (r.optString("conclusion")) {
                "success", "neutral", "skipped" -> passed++
                else -> { failed++; failedNames += r.optString("name") }
            }
        }
        return GhChecks(runs.length(), passed, failed, pending, failedNames)
    }

    suspend fun openPull(repo: String, head: String, base: String, title: String, body: String, draft: Boolean): GhPull =
        pull(JSONObject(send("POST", url("repos/${check(repo)}/pulls"),
            JSONObject().put("title", title).put("head", head).put("base", base).put("body", body).put("draft", draft))))

    /** Squash-merges a pull request, only if its head is still [sha] (so nothing unseen gets merged). */
    suspend fun merge(repo: String, number: Int, sha: String, method: String = "squash"): String {
        val o = JSONObject(send("PUT", url("repos/${check(repo)}/pulls/$number/merge"), JSONObject().put("merge_method", method).put("sha", sha)))
        return o.optString("message", "Merged")
    }

    suspend fun workflows(repo: String): List<GhWorkflow> {
        val arr = JSONObject(get(url("repos/${check(repo)}/actions/workflows"))).optJSONArray("workflows") ?: JSONArray()
        return (0 until arr.length()).map { val w = arr.getJSONObject(it); GhWorkflow(w.optString("name"), w.optString("path"), w.optString("state")) }
    }

    /** Actions secret names, or null when the token may not list them. Values are never readable. */
    suspend fun secretNames(repo: String): List<String>? = try {
        val arr = JSONObject(get(url("repos/${check(repo)}/actions/secrets"))).optJSONArray("secrets") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    } catch (e: GitHubException) {
        if (e.status == 403 || e.status == 404) null else throw e
    }

    /** The blob sha of a file on the default branch, or null when it doesn't exist. */
    suspend fun fileSha(repo: String, path: String): String? = try {
        JSONObject(get(url("repos/${check(repo)}/contents/$path"))).optString("sha").ifBlank { null }
    } catch (e: GitHubException) {
        if (e.notFound) null else throw e
    }

    /** Creates a file in one commit on the default branch. */
    suspend fun createFile(repo: String, path: String, content: String, message: String) {
        val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
        send("PUT", url("repos/${check(repo)}/contents/$path"), JSONObject().put("message", message).put("content", b64))
    }

    // ------------------------------------------------------------------ transport

    private fun url(path: String): HttpUrl = (baseUrl.trimEnd('/') + "/").toHttpUrl().newBuilder().addPathSegments(path).build()

    private fun check(repo: String): String {
        require(REPO.matches(repo)) { "“$repo” isn't a repository name like owner/name" }
        return repo
    }

    private fun request(u: HttpUrl): Request.Builder {
        val t = token()?.takeIf { it.isNotBlank() } ?: throw GitHubException(401, "Connect GitHub in Settings › Coding first")
        return Request.Builder().url(u)
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Axiom-Launcher")
    }

    private suspend fun get(u: HttpUrl): String = execute(request(u).get().build())

    private suspend fun send(method: String, u: HttpUrl, body: JSONObject): String =
        execute(request(u).method(method, body.toString().toRequestBody(JSON)).build())

    private suspend fun execute(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(GitHubException(0, "Couldn't reach GitHub: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (it.isSuccessful) cont.resume(text) else cont.resumeWithException(error(it, text))
                }
            }
        })
    }

    private fun error(r: Response, body: String): GitHubException {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty().ifBlank { "HTTP ${r.code}" }
        val details = runCatching {
            JSONObject(body).optJSONArray("errors")?.let { a -> (0 until a.length()).joinToString("; ") { a.getJSONObject(it).optString("message").ifBlank { a.getJSONObject(it).optString("code") } } }
        }.getOrNull().orEmpty()
        val limited = r.header("x-ratelimit-remaining") == "0"
        val text = when {
            r.code == 401 -> "GitHub rejected the token (expired or revoked?)"
            limited -> "GitHub rate limit reached; it resets at " + (r.header("x-ratelimit-reset")?.toLongOrNull()?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000))
            } ?: "the top of the hour")
            r.code == 403 -> "The GitHub token isn't allowed to do that ($msg). Check its repository permissions."
            r.code == 404 -> "Not found on GitHub, or the token can't see it ($msg)"
            r.code == 409 -> "GitHub reported a conflict: $msg"
            r.code == 405 -> "GitHub won't merge this yet: $msg"
            r.code == 422 -> "GitHub refused it: $msg" + if (details.isNotBlank()) " — $details" else ""
            else -> "GitHub error ${r.code}: $msg"
        }
        return GitHubException(if (limited) 429 else r.code, text)
    }

    private fun repo(o: JSONObject) = GhRepo(
        o.getString("full_name"), o.optBoolean("private"), o.optString("default_branch", "main"),
        if (o.isNull("description")) "" else o.optString("description"), time(o.optString("pushed_at")),
    )

    private fun issue(o: JSONObject) = GhIssue(o.getInt("number"), o.optString("title"), o.optString("state"), o.optString("html_url"),
        if (o.isNull("state_reason")) null else o.optString("state_reason"), isPull = o.has("pull_request"))

    private fun comment(o: JSONObject) = GhComment(o.getLong("id"), o.optJSONObject("user")?.optString("login").orEmpty(), o.optString("body"),
        time(o.optString("created_at")), time(o.optString("updated_at")), o.optString("html_url"))

    private fun pull(o: JSONObject) = GhPull(
        number = o.getInt("number"), title = o.optString("title"), state = o.optString("state"),
        merged = o.optBoolean("merged") || (!o.isNull("merged_at") && o.optString("merged_at").isNotBlank()),
        draft = o.optBoolean("draft"), url = o.optString("html_url"),
        headRef = o.optJSONObject("head")?.optString("ref").orEmpty(), headSha = o.optJSONObject("head")?.optString("sha").orEmpty(),
        baseRef = o.optJSONObject("base")?.optString("ref").orEmpty(),
        mergeable = if (!o.has("mergeable") || o.isNull("mergeable")) null else o.optBoolean("mergeable"),
        mergeableState = o.optString("mergeable_state"),
        additions = o.optInt("additions"), deletions = o.optInt("deletions"), changedFiles = o.optInt("changed_files"),
    )

    private fun file(o: JSONObject) = GhFile(o.optString("filename"), o.optString("status"), o.optInt("additions"), o.optInt("deletions"),
        if (o.isNull("patch")) null else o.optString("patch"))

    companion object {
        const val API = "https://api.github.com"
        private val JSON = "application/json".toMediaType()
        val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

        fun time(iso: String?): Long {
            if (iso.isNullOrBlank() || iso == "null") return 0
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)!!.time
            }.getOrDefault(0)
        }
    }
}
