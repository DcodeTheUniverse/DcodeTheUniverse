package com.axiom.launcher.solis.coding

import com.axiom.launcher.solis.engine.SideEffect
import com.axiom.launcher.solis.engine.SolisTool
import com.axiom.launcher.solis.engine.SolisTool.Companion.bool
import com.axiom.launcher.solis.engine.SolisTool.Companion.int
import com.axiom.launcher.solis.engine.SolisTool.Companion.str
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.engine.ToolHost
import com.axiom.launcher.solis.engine.ToolResult
import org.json.JSONObject

/**
 * Coding tools: Solis writes the spec and follows up; Claude Code (in the repository's GitHub Actions, on
 * the user's Claude subscription) does the work. Offered only once GitHub is connected.
 */
class CodingTools(private val bridge: CodingBridge) : ToolHost {

    private val list: List<SolisTool> by lazy { build() }

    override fun tools(): List<SolisTool> = if (bridge.configured) list else emptyList()

    private suspend fun guard(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: GitHubException) {
        ToolResult(e.message ?: "GitHub error", false, "GitHub: ${e.message?.take(60)}")
    } catch (e: IllegalStateException) {
        ToolResult(e.message ?: "Not possible", false, e.message?.take(60) ?: "failed")
    } catch (e: IllegalArgumentException) {
        ToolResult(e.message ?: "Invalid input", false, e.message?.take(60) ?: "bad input")
    }

    private fun jobOf(a: JSONObject): CodingJob {
        val repo = bridge.repoOrDefault(a.optString("repo").ifBlank { null })
        val n = a.optInt("number", a.optInt("issue", -1))
        if (n > 0) return bridge.jobFor(repo, n) ?: throw IllegalStateException("#$n in $repo isn't a tracked coding job; use code_track first")
        return bridge.jobs.value.firstOrNull { it.repo == repo && it.active } ?: throw IllegalStateException("No active coding job in $repo; pass number")
    }

    private fun describe(j: CodingJob): String = buildString {
        append("${j.id} “${j.title}” — ${j.state.label}: ${j.detail}\n")
        append("issue: ${j.url}\n")
        j.branch?.let { append("branch: $it\n") }
        j.prUrl?.let { append("pull request: $it\n") }
        j.checks?.let { append("checks: $it\n") }
        j.summary?.let { append("Claude's latest note: ${it.take(400)}\n") }
        if (j.mergeRequested) append("A merge is waiting for the user's confirmation.\n")
    }.trim()

    private fun build(): List<SolisTool> = listOf(
        SolisTool("code_repos", "List the user's GitHub repositories (most recently pushed first) and the default one for coding tasks.",
            ToolGroup.CODE, SideEffect.NONE, emptyMap()) { _, _ ->
            guard {
                val def = runCatching { bridge.repoOrDefault(null) }.getOrNull()
                val repos = bridge.github.repos(40)
                ToolResult(repos.joinToString("\n") { r ->
                    "${r.fullName}${if (r.fullName == def) " (default)" else ""}${if (r.private) " · private" else ""}" +
                        (r.description.takeIf { it.isNotBlank() }?.let { " · ${it.take(80)}" } ?: "")
                }.ifEmpty { "No repositories visible to this token." }, true, "listed ${repos.size} repos")
            }
        },
        SolisTool(
            "code_delegate",
            "Hand a coding task in one of the user's GitHub repositories to Claude Code. It files an issue that mentions @claude; " +
                "Claude Code then works in the repository's GitHub Actions on the user's Claude subscription and pushes a branch. " +
                "Write a complete, self-contained spec: the goal, relevant context and files if known, acceptance criteria and constraints. " +
                "Pass issue to ask Claude again on an existing issue instead of opening a new one.",
            ToolGroup.CODE, SideEffect.EXTERNAL,
            mapOf(
                "title" to str("Short issue title"),
                "spec" to str("The full task for Claude Code, in Markdown"),
                "repo" to str("owner/name; omit for the default repository"),
                "issue" to int("Optional existing issue number to continue"),
            ), listOf("title", "spec"),
        ) { a, ctx ->
            guard {
                val issue = a.optInt("issue", -1).takeIf { it > 0 }
                val j = bridge.delegate(a.optString("repo").ifBlank { null }, a.getString("title"), a.getString("spec"), ctx.threadId, issue)
                ToolResult("Filed ${j.id} (${j.url}) and asked Claude Code to do it. Axiom follows the issue, branch and checks and notifies the user; " +
                    "use code_status for updates. Claude usually takes a few minutes.", true, "sent to Claude Code: ${j.id}")
            }
        },
        SolisTool("code_status", "Current state of a coding job (Claude's progress, branch, pull request, checks). Omit number for all active jobs.",
            ToolGroup.CODE, SideEffect.NONE,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue or pull request number"))) { a, _ ->
            guard {
                if (a.optInt("number", -1) <= 0 && a.optString("repo").isBlank()) {
                    val active = bridge.jobs.value.filter { it.active }
                    if (active.isEmpty()) return@guard ToolResult("No active coding jobs.", true, "no coding jobs")
                    return@guard ToolResult(active.joinToString("\n\n") { describe(it) }, true, "${active.size} coding jobs")
                }
                val j = bridge.refresh(jobOf(a).id)
                ToolResult(describe(j), true, "${j.id}: ${j.state.label}")
            }
        },
        SolisTool("code_diff", "The files a coding job changed, with line counts and the patch text, to review or explain the change.",
            ToolGroup.CODE, SideEffect.NONE,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue or pull request number"))) { a, _ ->
            guard {
                val j = jobOf(a)
                ToolResult("<diff job=\"${j.id}\">\n${bridge.diff(j.id)}\n</diff>", true, "read diff of ${j.id}")
            }
        },
        SolisTool("code_comment", "Comment on a coding job's issue. With ask_claude, the comment starts with @claude so Claude Code acts on it (follow-up changes, fixes for failed checks).",
            ToolGroup.CODE, SideEffect.EXTERNAL,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue number"), "body" to str("Comment in Markdown"),
                "ask_claude" to bool("Address it to Claude Code")), listOf("number", "body")) { a, ctx ->
            guard {
                val repo = bridge.repoOrDefault(a.optString("repo").ifBlank { null })
                val n = a.getInt("number")
                if (a.optBoolean("ask_claude")) {
                    val j = bridge.delegate(repo, "", a.getString("body"), ctx.threadId, existingIssue = n)
                    ToolResult("Asked Claude Code on ${j.id}; tracking it again.", true, "asked Claude on #$n")
                } else {
                    bridge.github.comment(repo, n, a.getString("body"))
                    ToolResult("Commented on $repo#$n.", true, "commented on #$n")
                }
            }
        },
        SolisTool("code_fix_checks", "Ask Claude Code to fix the failing checks on a job's pull request: posts the failures (names, summaries, " +
            "annotations) on the PR with @claude so it fixes them on the same branch.", ToolGroup.CODE, SideEffect.EXTERNAL,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue or pull request number")), listOf("number")) { a, _ ->
            guard {
                val j = bridge.askFix(jobOf(a).id)
                ToolResult("Asked Claude Code to fix the checks on PR #${j.pr}; following it.", true, "asked Claude to fix #${j.pr}")
            }
        },
        SolisTool("code_open_pr", "Open a pull request from the branch Claude Code pushed for a job, so its checks run and the user can review it.",
            ToolGroup.CODE, SideEffect.EXTERNAL,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue number"), "draft" to bool("Open as draft")),
            listOf("number")) { a, _ ->
            guard {
                val j = bridge.openPullRequest(jobOf(a).id, a.optBoolean("draft"))
                ToolResult("Pull request: ${j.prUrl} — ${j.detail}", true, "opened PR #${j.pr}")
            }
        },
        SolisTool("code_track", "Start following an existing issue or pull request in a repository (for work the user filed elsewhere).",
            ToolGroup.CODE, SideEffect.LOCAL,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue or pull request number")), listOf("number")) { a, ctx ->
            guard {
                val j = bridge.track(a.optString("repo").ifBlank { null }, a.getInt("number"), ctx.threadId)
                ToolResult(describe(j), true, "tracking ${j.id}")
            }
        },
        SolisTool("code_request_merge", "Ask the user to merge a job's pull request. You cannot merge: this puts a Merge button on the job for the user to confirm.",
            ToolGroup.CODE, SideEffect.LOCAL,
            mapOf("repo" to str("owner/name; omit for the default repository"), "number" to int("Issue or pull request number")), listOf("number")) { a, _ ->
            guard {
                val j = bridge.refresh(jobOf(a).id)
                val r = bridge.requestMerge(j.id)
                if (r == null) ToolResult("Not mergeable now: ${j.state.label} — ${j.detail}", false, "merge not possible")
                else ToolResult("The user will see “Merge PR #${r.pr}?” on the coding job and decides there.", true, "merge awaiting confirmation")
            }
        },
    )
}
