package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.solis.coding.CodingBridge
import com.axiom.launcher.solis.coding.CodingJob
import com.axiom.launcher.solis.coding.JobState
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle

/** Glyph per coding state, so state never rests on color alone. */
fun JobState.glyph(): String = when (this) {
    JobState.SENT -> "◌"
    JobState.WORKING -> "◐"
    JobState.READY -> "◆"
    JobState.REPLIED -> "✉"
    JobState.PR_OPEN -> "⇄"
    JobState.CHECKS_FAILED -> "✗"
    JobState.ERROR -> "!"
    JobState.STALLED -> "…"
    JobState.MERGED -> "✓"
    JobState.CLOSED -> "–"
}

/** Settings › Coding: connect GitHub, choose the repository, and set up Claude Code on GitHub Actions. */
@Composable
fun CodingSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val rev = vm.settingsRevision
    var token by remember { mutableStateOf("") }
    var repo by remember(rev) { mutableStateOf(vm.prefs.githubRepo) }
    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    val login = remember(rev, vm.githubConnected) { vm.prefs.githubLogin }
    LaunchedEffect(vm.githubConnected, rev) { if (vm.githubConnected && repos.isEmpty()) repos = vm.githubRepos() }

    Text(
        "Solis plans the change and writes the spec; Claude Code does the coding in your repository's GitHub Actions, " +
            "on your Claude subscription. Axiom follows the issue, branch, pull request and checks and tells you when it's ready. " +
            "Nothing merges without your OK.",
        style = lStyle(13.sp, color = p.dim),
    )

    SectionLabel("GitHub")
    Text(if (vm.githubConnected) "Connected" + (login.takeIf { it.isNotBlank() }?.let { " as @$it" } ?: "") + " ✓" else "Not connected",
        style = lStyle(14.sp, color = if (vm.githubConnected) p.fg else p.dim))
    Spacer(Modifier.height(6.dp))
    SecretField(token, { token = it }, if (vm.githubConnected) "••••••••  (paste to replace)" else "Fine-grained personal access token")
    Text(
        "Token access: only the repositories you want Solis to use. Permissions: Issues and Pull requests read & write, Contents read & write " +
            "(to open pull requests and install the workflow), Actions and Checks read, Workflows write (only for “Install workflow”). " +
            "Stored encrypted with the Android Keystore and sent only to api.github.com.",
        style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (token.isNotBlank()) Chip("Save & test", selected = true) { vm.saveGithubToken(token); token = "" }
        if (vm.githubConnected) Chip("Disconnect") { vm.saveGithubToken(null) }
        Chip("Create a token", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.openUrl("https://github.com/settings/personal-access-tokens/new") }
    }

    SectionLabel("Default repository")
    InputBar(repo, { repo = it.trim() }, "owner/name", onSubmit = { vm.setGithubRepo(repo) })
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (repo != vm.prefs.githubRepo) Chip("Save", selected = true) { vm.setGithubRepo(repo) }
        repos.take(12).forEach { r -> Chip(r.substringAfter('/'), selected = r == vm.prefs.githubRepo) { repo = r; vm.setGithubRepo(r) } }
    }

    SectionLabel("Claude Code on GitHub")
    listOf(
        "1. Install the Claude GitHub app on the repository.",
        "2. On a computer with Claude Code, run `claude setup-token` (uses your Pro or Max plan) and add the token as the repository secret $SECRET.",
        "3. Add the workflow: tap Install workflow, or copy it to ${CodingBridge.WORKFLOW_PATH}.",
        "Shortcut: run /install-github-app in Claude Code on a computer; it walks you through all three.",
    ).forEach { Text(it.replace("`", ""), style = lStyle(13.sp), modifier = Modifier.padding(vertical = 2.dp)) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Check setup", selected = true) { vm.checkCodingSetup() }
        if (vm.githubConnected && vm.prefs.githubRepo.isNotBlank()) Chip("Install workflow") { vm.installClaudeWorkflow() }
        Chip("Copy workflow") { vm.copyWorkflow() }
        Chip("Claude GitHub app", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.openUrl("https://github.com/apps/claude") }
        if (vm.prefs.githubRepo.isNotBlank()) Chip("Repository secrets", icon = Icons.AutoMirrored.Outlined.OpenInNew) {
            vm.actions.openUrl("https://github.com/${vm.prefs.githubRepo}/settings/secrets/actions/new")
        }
    }
    vm.codingSetup.forEach { line ->
        Text(line, style = lStyle(13.sp, color = if (line.startsWith("✗")) p.danger else p.fg), modifier = Modifier.padding(top = 4.dp))
    }

    SectionLabel("Updates")
    SettingSwitch("Notify me", "When Claude finishes, needs you, checks fail or a pull request is ready to merge", vm.prefs.codingNotify) { v ->
        vm.editSolisPrefs { it.codingNotify = v }
    }
    Text(
        "Axiom checks GitHub every 30–60 s while Claude works, every few minutes while a pull request waits, and stops a day after the last change. " +
            "In the omnibox, “code: fix the login crash” files the task directly without using any model credits.",
        style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp),
    )
}

private const val SECRET = CodingBridge.SECRET

/** One coding job in the task overview, with the actions its state allows. */
@Composable
fun CodingJobRow(vm: LauncherViewModel, j: CodingJob) {
    val p = LocalPalette.current
    var confirmMerge by remember(j.id) { mutableStateOf(false) }
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .let { if (j.mergeRequested) it.border(1.dp, p.accent, shape) else it }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.state.glyph() + "  ", style = lStyle(14.sp, FontWeight.Bold, p.accent), modifier = Modifier.semantics { contentDescription = j.state.label })
            Text(j.title, style = lStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("#${j.issue}", style = lStyle(11.sp, color = p.dim))
        }
        Text("${j.repo} · ${j.state.label}", style = lStyle(12.sp, FontWeight.Medium, p.fg))
        Text(j.detail, style = lStyle(12.sp, color = p.dim), maxLines = 3, overflow = TextOverflow.Ellipsis)
        j.branch?.let { Text(it, style = lStyle(11.sp, color = p.dim).copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        j.pollError?.let { Text("Last check failed: $it", style = lStyle(11.sp, color = p.danger), maxLines = 2) }
        if (j.state == JobState.WORKING && j.stepsTotal > 0) {
            Spacer(Modifier.height(6.dp))
            Progress(j.stepsDone / j.stepsTotal.toFloat())
        }
        if (confirmMerge || j.mergeRequested) {
            Text(
                if (j.mergeRequested && !confirmMerge) "Solis suggests merging PR #${j.pr}. Merge it into ${j.baseBranch ?: "the default branch"}?"
                else "Squash-merge PR #${j.pr} into ${j.baseBranch ?: "the default branch"}? This changes the repository.",
                style = lStyle(13.sp, color = p.accent), modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (confirmMerge || j.mergeRequested) {
                Chip("Merge now", selected = true) { confirmMerge = false; vm.mergeCoding(j.id) }
                Chip("Not now") { confirmMerge = false; vm.dismissMerge(j.id) }
            } else {
                if (j.state == JobState.READY) Chip("Open pull request", selected = true) { vm.openCodingPr(j.id) }
                if (j.state == JobState.PR_OPEN && j.checksDone) Chip("Merge…") { confirmMerge = true }
                Chip("GitHub", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.openUrl(j.prUrl ?: j.url) }
                if (!j.state.terminal) Chip("Check now") { vm.recheckCoding(j.id) }
                if (j.threadId != null && vm.threads.any { it.id == j.threadId }) Chip("Solis thread") { vm.selectedThread = j.threadId; vm.pageRequest = com.axiom.launcher.PAGE_SOLIS }
                if (!j.state.terminal) Chip("Claude Code", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.continueInClaudeCode(j) }
                if (j.active) Chip("Stop following") { vm.stopWatchingCoding(j.id) } else Chip("Remove") { vm.forgetCoding(j.id) }
            }
        }
    }
}

/** Compact card on the home screen for a coding job that is active or needs the user. */
@Composable
fun CodingCard(vm: LauncherViewModel, j: CodingJob, open: () -> Unit) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape).background(p.surface)
            .let { if (j.mergeRequested) it.border(1.dp, p.accent, shape) else it }
            .clickable(onClick = open).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.state.glyph() + "  ", style = lStyle(13.sp, FontWeight.Bold, p.accent))
            Text("Claude Code · ${j.title}", style = lStyle(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text(if (j.mergeRequested) "Merge PR #${j.pr}? Tap to decide" else "${j.state.label} · ${j.detail}",
            style = lStyle(12.sp, color = if (j.mergeRequested) p.accent else p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (j.state == JobState.WORKING && j.stepsTotal > 0) {
            Spacer(Modifier.height(5.dp))
            Progress(j.stepsDone / j.stepsTotal.toFloat())
        }
    }
}

/** Jobs worth a home card: still active, or finished in the last hour and waiting for the user. */
fun homeCodingJobs(jobs: List<CodingJob>, now: Long = System.currentTimeMillis()): List<CodingJob> = jobs.filter {
    it.mergeRequested || (it.active && it.state != JobState.STALLED) || (it.state == JobState.MERGED && now - it.changedAt < 3_600_000L)
}.take(3)
