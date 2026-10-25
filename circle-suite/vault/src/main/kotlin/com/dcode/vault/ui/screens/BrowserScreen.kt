package com.dcode.vault.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dcode.vault.core.Node
import com.dcode.vault.core.NodeKind
import com.dcode.vault.core.TextTypes
import com.dcode.vault.core.VaultSession
import com.dcode.vault.core.VaultSnapshot
import com.dcode.vault.core.search.SearchHit
import com.dcode.vault.core.search.SearchScope
import com.dcode.vault.ui.components.Chip
import com.dcode.vault.ui.components.Hint
import com.dcode.vault.ui.components.IconTile
import com.dcode.vault.ui.components.SearchField
import com.dcode.vault.ui.components.VaultDial
import com.dcode.vault.ui.components.VaultField
import com.dcode.vault.ui.theme.V
import com.dcode.vault.ui.theme.VaultPalette
import com.dcode.vault.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class Filter(val label: String) { All("All"), Notes("Notes"), Files("Files"), Passwords("Passwords"), Favorites("Favorites") }

/** Browser state hoisted above navigation so folder/search survive opening an item. */
@Stable
class BrowserState {
    var folder by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.All)
    var scope by mutableStateOf(SearchScope.ALL)
}

fun iconFor(n: Node): ImageVector = when (n.kind) {
    NodeKind.FOLDER -> Icons.Rounded.Folder
    NodeKind.NOTE -> Icons.AutoMirrored.Rounded.Notes
    NodeKind.PASSWORD -> Icons.Rounded.Key
    NodeKind.FILE -> when {
        TextTypes.isImage(n.name, n.mime) -> Icons.Rounded.Image
        TextTypes.isTextLike(n.name, n.mime) -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

fun tintFor(n: Node, p: VaultPalette) = when (n.kind) {
    NodeKind.FOLDER -> p.folder
    NodeKind.NOTE -> p.note
    NodeKind.PASSWORD -> p.key
    NodeKind.FILE -> p.file
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun subtitle(n: Node, snap: VaultSnapshot): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(n.modifiedAt))
    return when (n.kind) {
        NodeKind.FOLDER -> snap.childrenOf(n.id).size.let { c -> "$c item${if (c == 1) "" else "s"}" }
        NodeKind.PASSWORD -> n.password?.username?.ifBlank { null }?.let { "$it · $date" } ?: date
        else -> "${formatSize(n.size)} · $date"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    session: VaultSession,
    state: BrowserState,
    onOpen: (Node) -> Unit,
    onNewPassword: (String?) -> Unit,
    onSettings: () -> Unit,
) {
    val p = V.palette
    val context = LocalContext.current
    val manager = context.vault
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snap by session.snapshot.collectAsState()
    val prefsChanges by manager.prefs.changes.collectAsState()
    val sortByModified = remember(prefsChanges) { manager.prefs.sortByModified }

    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var actionsFor by remember { mutableStateOf<Node?>(null) }
    var moving by remember { mutableStateOf<Node?>(null) }
    var deleting by remember { mutableStateOf<Node?>(null) }
    var exporting by remember { mutableStateOf<Node?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    // If the open folder disappears (deleted), fall back to root.
    if (state.folder != null && snap.nodes[state.folder] == null && snap.revision > 0) state.folder = null

    LaunchedEffect(state.query, state.scope, snap.revision) {
        val q = state.query.trim()
        if (q.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(60) // debounce keystrokes
        results = withContext(Dispatchers.Default) { session.search(q, state.scope) }
        searching = false
    }

    BackHandler(enabled = state.query.isNotEmpty() || state.folder != null || fabOpen || state.filter != Filter.All) {
        when {
            fabOpen -> fabOpen = false
            state.query.isNotEmpty() -> state.query = ""
            state.filter != Filter.All -> state.filter = Filter.All
            else -> state.folder = snap.nodes[state.folder]?.parentId
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        manager.holdLock = false
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = state.folder
        scope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) runCatching { importUri(context, session, target, uri) }.onSuccess { ok++ }
            }
            toast = "Encrypted $ok of ${uris.size} file${if (uris.size == 1) "" else "s"}"
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        manager.holdLock = false
        val n = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { session.exportBlob(n.id, it) } } }
            toast = if (r.isSuccess) "Exported a decrypted copy of ${n.name}" else "Export failed"
        }
    }

    val items = remember(snap, state.folder, state.filter, sortByModified) {
        val base = when (state.filter) {
            Filter.All -> snap.childrenOf(state.folder)
            Filter.Notes -> snap.nodes.values.filter { it.kind == NodeKind.NOTE || (it.kind == NodeKind.FILE && TextTypes.isTextLike(it.name, it.mime)) }
            Filter.Files -> snap.nodes.values.filter { it.kind == NodeKind.FILE }
            Filter.Passwords -> snap.nodes.values.filter { it.kind == NodeKind.PASSWORD }
            Filter.Favorites -> snap.nodes.values.filter { it.favorite }
        }
        base.sortedWith(
            compareBy<Node> { if (it.isFolder) 0 else 1 }.let { c ->
                if (sortByModified) c.thenByDescending { it.modifiedAt } else c.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            },
        )
    }
    val path = remember(snap, state.folder) { snap.path(state.folder) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(state.folder == null && state.filter == Filter.All, label = "title") { root ->
                    if (root) {
                        Text("Vault", style = MaterialTheme.typography.headlineLarge, color = p.ink)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (state.filter != Filter.All) state.filter = Filter.All else state.folder = snap.nodes[state.folder]?.parentId
                            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up", tint = p.ink) }
                            Text(
                                if (state.filter != Filter.All) state.filter.label else path.lastOrNull()?.name.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { manager.lock() }) { Icon(Icons.Rounded.Lock, "Lock", tint = p.ink) }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = p.ink) }
            }
            SearchField(state.query, { state.query = it }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            AnimatedContent(state.query.isNotEmpty(), label = "chips") { searchingMode ->
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searchingMode) {
                        listOf(SearchScope.ALL to "Everywhere", SearchScope.NAMES to "File names", SearchScope.CONTENT to "Contents").forEach { (sc, label) ->
                            Chip(label, state.scope == sc, { state.scope = sc })
                        }
                    } else {
                        Filter.entries.forEach { f -> Chip(f.label, state.filter == f, { state.filter = f; if (f != Filter.All) state.folder = null }) }
                    }
                }
            }
            if (state.query.isEmpty() && state.folder != null && state.filter == Filter.All && path.size > 1) {
                Breadcrumbs(path) { state.folder = it }
            }
            Spacer(Modifier.height(6.dp))

            if (state.query.isNotEmpty()) {
                SearchResults(results, snap, searching, state.query, onOpen = { n -> onOpen(n) })
            } else if (items.isEmpty()) {
                EmptyState(state.filter)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(items, key = { it.id }) { n ->
                        NodeRow(
                            n, subtitle(n, snap),
                            Modifier.animateItem(),
                            onClick = { if (n.isFolder && state.filter == Filter.All) state.folder = n.id else if (n.isFolder) { state.filter = Filter.All; state.folder = n.id } else onOpen(n) },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actionsFor = n },
                        )
                    }
                }
            }
        }

        // Scrim + speed dial
        AnimatedVisibility(fabOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(p.background.copy(alpha = 0.82f)).clickable(interactionSource = null, indication = null) { fabOpen = false })
        }
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.End) {
            val actions = listOf(
                Triple("Import files", Icons.Rounded.FileUpload) { manager.holdLock = true; importer.launch(arrayOf("*/*")) },
                Triple("New password", Icons.Rounded.Key) { onNewPassword(state.folder) },
                Triple("New folder", Icons.Rounded.CreateNewFolder) { prompt = Prompt.NewFolder },
                Triple("New note", Icons.Rounded.Edit) { prompt = Prompt.NewNote },
            )
            actions.forEachIndexed { i, (label, icon, action) ->
                AnimatedVisibility(
                    fabOpen,
                    enter = fadeIn(tween(160, delayMillis = (actions.size - i) * 35)) + slideInVertically(tween(220, delayMillis = (actions.size - i) * 35)) { it / 2 } + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(120)) + slideOutVertically { it / 3 } + scaleOut(targetScale = 0.8f),
                ) {
                    Row(Modifier.padding(bottom = 14.dp).clickable(interactionSource = null, indication = null) { fabOpen = false; action() }, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.clip(CircleShape).background(p.surfaceHigh).padding(horizontal = 14.dp, vertical = 8.dp))
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(48.dp).clip(CircleShape).background(p.surfaceHigh), contentAlignment = Alignment.Center) { Icon(icon, label, tint = p.accent) }
                    }
                }
            }
            val rot by animateFloatAsState(if (fabOpen) 135f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "fab")
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(p.accent).clickable { fabOpen = !fabOpen },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "New", tint = p.onAccent, modifier = Modifier.size(30.dp).rotate(rot)) }
        }

        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp), enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
            Text(toast.orEmpty(), color = p.background, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clip(CircleShape).background(p.ink).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }

    // ---- dialogs & sheets
    prompt?.let { pr ->
        NamePrompt(
            title = when (pr) { Prompt.NewFolder -> "New folder"; Prompt.NewNote -> "New note"; is Prompt.Rename -> "Rename" },
            initial = when (pr) { Prompt.NewFolder -> ""; Prompt.NewNote -> "Untitled.md"; is Prompt.Rename -> pr.node.name },
            onDismiss = { prompt = null },
            onConfirm = { name ->
                prompt = null
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        runCatching {
                            when (pr) {
                                Prompt.NewFolder -> session.createFolder(state.folder, name).also { }
                                Prompt.NewNote -> session.createNote(state.folder, if (name.contains('.')) name else "$name.md", "")
                                is Prompt.Rename -> session.rename(pr.node.id, name)
                            }
                        }.getOrNull()
                    }
                    if (created != null && pr == Prompt.NewNote) onOpen(created)
                }
            },
        )
    }

    actionsFor?.let { n ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, containerColor = p.background) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(iconFor(n), tintFor(n, p))
                    Spacer(Modifier.width(14.dp))
                    Text(n.name, style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SheetAction(if (n.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (n.favorite) "Remove from favorites" else "Add to favorites") {
                    actionsFor = null; scope.launch(Dispatchers.IO) { session.setFavorite(n.id, !n.favorite) }
                }
                SheetAction(Icons.Rounded.DriveFileRenameOutline, "Rename") { actionsFor = null; prompt = Prompt.Rename(n) }
                SheetAction(Icons.AutoMirrored.Rounded.DriveFileMove, "Move") { actionsFor = null; moving = n }
                if (n.hasBlob) SheetAction(Icons.Rounded.Save, "Export decrypted copy") {
                    actionsFor = null; exporting = n; manager.holdLock = true; exporter.launch(n.name)
                }
                SheetAction(Icons.Rounded.Delete, "Delete", danger = true) { actionsFor = null; deleting = n }
            }
        }
    }

    moving?.let { n ->
        FolderPicker(snap, exclude = n, onDismiss = { moving = null }) { target ->
            moving = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { session.move(n.id, target) } }
                toast = if (r.isSuccess) "Moved" else r.exceptionOrNull()?.message
            }
        }
    }

    deleting?.let { n ->
        val count = if (n.isFolder) countDescendants(snap, n.id) else 0
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = p.background,
            title = { Text("Delete ${n.name}?") },
            text = { Text(if (n.isFolder) "This folder and its $count item${if (count == 1) "" else "s"} will be shredded. This can't be undone." else "The encrypted data is overwritten and removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { withContext(Dispatchers.IO) { session.delete(n.id) }; toast = "Deleted" }
                }) { Text("Delete", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = p.ink) } },
        )
    }
}

private fun countDescendants(snap: VaultSnapshot, id: String): Int = snap.childrenOf(id).sumOf { 1 + if (it.isFolder) countDescendants(snap, it.id) else 0 }

private sealed interface Prompt {
    data object NewFolder : Prompt
    data object NewNote : Prompt
    data class Rename(val node: Node) : Prompt
}

private fun importUri(context: android.content.Context, session: VaultSession, folder: String?, uri: Uri) {
    val cr = context.contentResolver
    var name = "Imported file"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { name = it }
    }
    val mime = cr.getType(uri)
    cr.openInputStream(uri)!!.use { session.importFile(folder, name, mime, it.buffered(64 * 1024)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeRow(n: Node, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = V.palette
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(iconFor(n), tintFor(n, p))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(n.name, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (n.favorite) Icon(Icons.Rounded.Star, "Favorite", tint = p.note, modifier = Modifier.size(18.dp))
        if (n.isFolder) Icon(Icons.Rounded.ChevronRight, null, tint = p.inkFaint)
    }
}

@Composable
private fun Breadcrumbs(path: List<Node>, onClick: (String?) -> Unit) {
    val p = V.palette
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Vault", style = MaterialTheme.typography.labelLarge, color = p.inkSoft, modifier = Modifier.clickable { onClick(null) })
        path.forEachIndexed { i, n ->
            Icon(Icons.Rounded.ChevronRight, null, tint = p.inkFaint, modifier = Modifier.size(16.dp))
            Text(
                n.name, style = MaterialTheme.typography.labelLarge, color = if (i == path.lastIndex) p.ink else p.inkSoft,
                modifier = Modifier.clickable { onClick(n.id) },
            )
        }
    }
}

@Composable
private fun SearchResults(results: List<SearchHit>, snap: VaultSnapshot, searching: Boolean, query: String, onOpen: (Node) -> Unit) {
    val p = V.palette
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
            Hint(if (searching) "Searching…" else "Nothing matches “$query”.\nTry fewer words, or a prefix.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Text("${results.size} result${if (results.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = p.inkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        items(results, key = { it.id }) { hit ->
            val n = snap.nodes[hit.id] ?: return@items
            val where = snap.path(n.parentId).joinToString(" / ") { it.name }.ifEmpty { "Vault" }
            Column(Modifier.animateItem().fillMaxWidth().clickable { onOpen(n) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(iconFor(n), tintFor(n, p), size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(highlight(n.name, hit.nameRanges, p), style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(where, style = MaterialTheme.typography.bodySmall, color = p.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                hit.snippet?.let { s ->
                    Text(
                        highlight(s, hit.snippetRanges, p), style = MaterialTheme.typography.bodyMedium, color = p.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 50.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

fun highlight(text: String, ranges: List<IntRange>, p: VaultPalette): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (r in ranges.sortedBy { it.first }) {
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (start < i) continue
        append(text.substring(i, start))
        withStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.SemiBold, background = p.accentSoft)) { append(text.substring(start, end)) }
        i = end
    }
    if (i < text.length) append(text.substring(i))
}

@Composable
private fun EmptyState(filter: Filter) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VaultDial(Modifier.size(140.dp))
        Spacer(Modifier.height(24.dp))
        Text(if (filter == Filter.All) "Nothing here yet" else "No ${filter.label.lowercase()}", style = MaterialTheme.typography.headlineSmall, color = V.palette.ink)
        Spacer(Modifier.height(8.dp))
        Hint("Tap + to write a note, add a password, create a folder or import files. Everything is encrypted the moment it's saved.")
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = V.palette
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) p.danger else p.ink)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (danger) p.danger else p.ink)
    }
}

@Composable
fun NamePrompt(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val p = V.palette
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.background,
        title = { Text(title) },
        text = { VaultField(text, { text = it }, placeholder = "Name") },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save", color = p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.ink) } },
    )
}

@Composable
private fun FolderPicker(snap: VaultSnapshot, exclude: Node, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val p = V.palette
    val folders = remember(snap) {
        fun forbidden(id: String): Boolean {
            var cur: String? = id
            while (cur != null) {
                if (cur == exclude.id) return true
                cur = snap.nodes[cur]?.parentId
            }
            return false
        }
        snap.nodes.values.filter { it.isFolder && !forbidden(it.id) }.map { it to snap.path(it.id).joinToString(" / ") { n -> n.name } }.sortedBy { it.second.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.background,
        title = { Text("Move ${exclude.name}") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item { FolderOption("Vault (top level)") { onPick(null) } }
                items(folders, key = { it.first.id }) { (f, label) -> FolderOption(label) { onPick(f.id) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.ink) } },
    )
}

@Composable
private fun FolderOption(label: String, onClick: () -> Unit) {
    val p = V.palette
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = p.folder)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
