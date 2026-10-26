package com.axiom.launcher.ui

import android.os.Environment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.data.FileHit
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RECENT = "::recent"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesPage(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val root = vm.files.root.path
    var path by remember { mutableStateOf(root) }
    var filter by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<FileHit?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(vm.files.hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = vm.files.hasAllFilesAccess()
        reload++
        onPauseOrDispose { }
    }
    LaunchedEffect(vm.filesPath) {
        vm.filesPath?.let { path = it; filter = ""; vm.filesPath = null }
    }

    val items by produceState(emptyList<FileHit>(), path, filter, reload, granted) {
        value = withContext(Dispatchers.IO) {
            when {
                filter.length >= 2 -> vm.files.search(filter, 80)
                path == RECENT || !granted -> vm.files.recent(60)
                else -> vm.files.list(File(path))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (path != root && path != RECENT && granted) {
                IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Up") { path = File(path).parent ?: root }
            }
            Column(Modifier.weight(1f)) {
                Text(if (Netrunner) "/fs" else "Files", style = lStyle(30.sp, FontWeight.Light))
                Text(
                    when {
                        filter.length >= 2 -> "search · $filter"
                        path == RECENT || !granted -> "recent"
                        else -> path.removePrefix(root).ifEmpty { "/" }.let { if (Netrunner) "~$it" else "Internal storage$it" }
                    },
                    style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!granted) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(16.dp)
            ) {
                Text("Unlock full file search", style = lStyle(16.sp, FontWeight.Medium))
                Text(
                    "Allow \"All files access\" so Axiom (and Claude) can browse folders and search every file by name. " +
                        "Nothing leaves your phone unless you send it to Claude.",
                    style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(vertical = 6.dp),
                )
                Chip("Allow access", selected = true) { vm.actions.allFilesAccessSettings() }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Recent", selected = path == RECENT) { path = RECENT; filter = "" }
                Chip("Storage", selected = path == root) { path = root; filter = "" }
                listOf(
                    "Downloads" to Environment.DIRECTORY_DOWNLOADS, "Documents" to Environment.DIRECTORY_DOCUMENTS,
                    "Camera" to Environment.DIRECTORY_DCIM, "Pictures" to Environment.DIRECTORY_PICTURES,
                    "Music" to Environment.DIRECTORY_MUSIC, "Movies" to Environment.DIRECTORY_MOVIES,
                ).forEach { (label, dir) ->
                    val full = File(root, dir).path
                    Chip(label, selected = path == full) { path = full; filter = "" }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(Modifier.weight(1f)) {
            if (items.isEmpty()) item {
                Text(if (Netrunner) "> null" else "Nothing here", style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(vertical = 20.dp))
            }
            items(items, key = { it.path }) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = {
                                if (f.isDir) { path = f.path; filter = "" }
                                else if (!vm.actions.openFile(f.path)) vm.say("No app can open ${f.name}")
                            },
                            onLongClick = { menu = f },
                        )
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fileIcon(f), null, tint = if (f.isDir) p.accent else p.dim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = lStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = buildString {
                            if (!f.isDir) append(DeviceInfo.human(f.size)).append(" · ")
                            append(SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(f.modified)))
                            if (filter.isNotEmpty() || path == RECENT) append(" · ").append(f.path.removePrefix(root).substringBeforeLast('/').ifEmpty { "/" })
                        }
                        Text(meta, style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        InputBar(
            value = filter, onValue = { filter = it },
            placeholder = if (Netrunner) "find -name" else "Search all files",
            onSubmit = {},
            trailing = { if (filter.isNotEmpty()) IconBtn(Icons.Outlined.Close, "Clear") { filter = "" } },
        )
        Spacer(Modifier.height(10.dp))
    }

    menu?.let { f -> FileMenu(vm, f, onDone = { menu = null; reload++ }) }
}

fun fileIcon(f: FileHit): ImageVector {
    if (f.isDir) return if (f.name.startsWith(".")) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
    return when (f.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Outlined.Image
        "mp4", "mkv", "mov", "webm", "avi", "3gp" -> Icons.Outlined.VideoFile
        "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac" -> Icons.Outlined.AudioFile
        "pdf" -> Icons.Outlined.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Outlined.FolderZip
        "apk" -> Icons.Outlined.Android
        "txt", "md", "doc", "docx", "odt", "rtf", "csv", "json", "xml", "html" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}
