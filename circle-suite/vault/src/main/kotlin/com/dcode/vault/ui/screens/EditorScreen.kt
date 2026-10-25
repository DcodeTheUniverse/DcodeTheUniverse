package com.dcode.vault.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcode.vault.core.Node
import com.dcode.vault.core.TextTypes
import com.dcode.vault.core.VaultSession
import com.dcode.vault.ui.components.ButtonStyle
import com.dcode.vault.ui.components.Card
import com.dcode.vault.ui.components.Hint
import com.dcode.vault.ui.components.IconTile
import com.dcode.vault.ui.components.PillButton
import com.dcode.vault.ui.components.TopBar
import com.dcode.vault.ui.markdown.MarkdownView
import com.dcode.vault.ui.theme.Mono
import com.dcode.vault.ui.theme.V
import com.dcode.vault.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(FlowPreview::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(session: VaultSession, id: String, onBack: () -> Unit) {
    val p = V.palette
    val manager = LocalContext.current.vault
    val scope = rememberCoroutineScope()
    val snap by session.snapshot.collectAsState()
    val node = snap.nodes[id]
    val prefsChanges by manager.prefs.changes.collectAsState()
    val mono = remember(prefsChanges) { manager.prefs.monospaceEditor }
    val state = rememberTextFieldState()
    var loaded by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val markdown = node != null && (TextTypes.isMarkdown(node.name) || node.mime == "text/markdown")

    LaunchedEffect(id) {
        val text = withContext(Dispatchers.IO) { runCatching { session.readText(id) }.getOrDefault("") }
        state.setTextAndPlaceCursorAtEnd(text)
        state.undoState.clearHistory()
        reading = markdown && text.isNotBlank()
        loaded = true
        // Autosave: every pause in typing re-encrypts the note with a fresh salt.
        snapshotFlow { state.text.toString() }.drop(1).distinctUntilChanged().also { flow ->
            launch { flow.collect { dirty = true } }
        }.debounce(650).collect { t ->
            withContext(Dispatchers.IO) { runCatching { session.saveText(id, t) } }
            // Keystrokes typed during the save keep the note dirty so a lock-time flush still writes them.
            if (state.text.toString() == t) dirty = false
            savedAt = System.currentTimeMillis()
        }
    }

    fun saveNow() {
        if (!dirty || session.isClosed) return
        val t = state.text.toString()
        dirty = false
        // Short synchronous save so leaving the screen never loses the last keystrokes.
        runBlocking(Dispatchers.IO) { runCatching { session.saveText(id, t) } }
    }
    DisposableEffect(Unit) {
        val hook: () -> Unit = { saveNow() }
        manager.lockHooks.add(hook)
        onDispose {
            manager.lockHooks.remove(hook)
            saveNow()
        }
    }
    BackHandler { saveNow(); onBack() }

    if (node == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding().imePadding()) {
        TopBar(
            node.name, onBack = { saveNow(); onBack() },
            subtitle = when {
                dirty -> "Encrypting…"
                savedAt != null -> "Encrypted · saved ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(savedAt!!))}"
                else -> "Encrypted · ${formatSize(node.size)}"
            },
        ) {
            if (!reading) {
                IconButton(onClick = { state.undoState.undo() }, enabled = state.undoState.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (state.undoState.canUndo) p.ink else p.inkFaint) }
                IconButton(onClick = { state.undoState.redo() }, enabled = state.undoState.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", tint = if (state.undoState.canRedo) p.ink else p.inkFaint) }
            }
            IconButton(onClick = { saveNow(); reading = !reading }) {
                Icon(if (reading) Icons.Rounded.Edit else Icons.Rounded.Visibility, if (reading) "Edit" else "Read", tint = p.accent)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = p.ink) }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = p.surface) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(if (mono) "Proportional font" else "Monospace font") }, leadingIcon = { Icon(Icons.Rounded.Code, null) }, onClick = { menu = false; manager.prefs.monospaceEditor = !mono })
                    val words = state.text.split(Regex("\\s+")).count { it.isNotBlank() }
                    DropdownMenuItem(text = { Text("$words words · ${state.text.length} characters", color = p.inkSoft) }, onClick = { menu = false })
                }
            }
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint("Decrypting…") }
            return@Column
        }
        AnimatedContent(reading, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "mode") { read ->
            if (read) {
                if (markdown) {
                    MarkdownView(state.text.toString(), Modifier.fillMaxSize())
                } else {
                    Text(
                        state.text.toString(), style = MaterialTheme.typography.bodyLarge, color = p.ink,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    )
                }
            } else {
                Editor(state, mono)
            }
        }
    }

    if (renaming) {
        NamePrompt("Rename", node.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch(Dispatchers.IO) { runCatching { session.rename(id, name) } }
        }
    }
}

@Composable
private fun Editor(state: TextFieldState, mono: Boolean) {
    val p = V.palette
    BasicTextField(
        state = state,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono, fontSize = 14.sp, lineHeight = 22.sp, color = p.ink)
        else MaterialTheme.typography.bodyLarge.copy(color = p.ink),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        decorator = { inner ->
            Box {
                if (state.text.isEmpty()) Text("Start writing… Markdown is supported.", style = MaterialTheme.typography.bodyLarge, color = p.inkFaint)
                inner()
            }
        },
    )
}

/** Images open in a zoomable viewer; other files show details with export and "open as text". */
@Composable
fun FileScreen(session: VaultSession, id: String, onBack: () -> Unit, onOpenAsText: (Node) -> Unit) {
    val p = V.palette
    val context = LocalContext.current
    val manager = context.vault
    val scope = rememberCoroutineScope()
    val snap by session.snapshot.collectAsState()
    val node = snap.nodes[id] ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val isImage = TextTypes.isImage(node.name, node.mime)
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(id) {
        if (!isImage) return@LaunchedEffect
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = session.readBytes(id)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }).also { java.util.Arrays.fill(bytes, 0) }
            }.getOrNull()
        }
        if (bitmap == null) error = "This image couldn't be decoded."
    }
    DisposableEffect(Unit) { onDispose { bitmap?.recycle() } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(node.mime ?: "application/octet-stream")) { uri ->
        manager.holdLock = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { session.exportBlob(id, it) } } }
            status = if (r.isSuccess) "Exported a decrypted copy" else "Export failed"
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        TopBar(node.name, onBack, subtitle = "${formatSize(node.size)} · ${node.mime ?: "unknown type"}")
        val b = bitmap
        if (isImage && b != null) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    b.asImageBitmap(), node.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                IconTile(iconFor(node), tintFor(node, p), size = 88.dp)
                Spacer(Modifier.height(20.dp))
                Text(node.name, style = MaterialTheme.typography.headlineSmall, color = p.ink)
                Spacer(Modifier.height(8.dp))
                Hint(error ?: if (isImage) "Decrypting…" else "Stored encrypted with a per-file key. Export writes a decrypted copy outside Vault.")
            }
        }
        Card(Modifier.padding(16.dp)) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = p.accent, modifier = Modifier.size(18.dp))
                Text(
                    "Added ${DateFormat.getDateInstance().format(Date(node.createdAt))}", style = MaterialTheme.typography.bodySmall, color = p.inkSoft,
                    modifier = Modifier.weight(1f),
                )
                if (!isImage) PillButton("Open as text", onClick = { onOpenAsText(node) }, style = ButtonStyle.Outline)
                PillButton("Export", onClick = { manager.holdLock = true; exporter.launch(node.name) }, style = ButtonStyle.Ink)
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.inkSoft, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) }
        }
        Spacer(Modifier.width(1.dp))
    }
}
