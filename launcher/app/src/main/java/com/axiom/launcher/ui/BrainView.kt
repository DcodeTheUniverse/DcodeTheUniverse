package com.axiom.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.plainText
import com.axiom.launcher.solis.memory.BrainGraph
import com.axiom.launcher.solis.memory.BrainLayout
import com.axiom.launcher.solis.memory.BrainNode
import com.axiom.launcher.solis.memory.MemoryActivity
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.effects.motionAllowed
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val PULSE_MS = 1700L
private const val TRAVEL_MS = 850L
private const val STILL_HIGHLIGHT_MS = 6000L
private val CORE = Offset(0f, -0.08f)

/**
 * Solis's memory as a brain: personal memory on the left, work on the right, each task's notes in a
 * cluster below, Solis's core in the middle. Links join memories that share distinctive terms. When Solis
 * recalls or writes memory during a task, pulses travel from the core (or the task's cluster) to exactly
 * those memories; while tasks run, the core breathes and each running task orbits it. Nothing moves
 * unless something really happened, and with reduced motion recalled memories are ringed instead.
 */
@Composable
fun BrainView(vm: LauncherViewModel, items: List<MemoryItem>, query: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val motion = motionAllowed()
    val titles = remember(vm.threads) { vm.threads.associate { it.id to it.title } }
    val graph by produceState(BrainGraph.EMPTY, items, titles) {
        fun build() = BrainLayout.build(items, titles, { vm.solis.memory.related(it) })
        // A small memory lays out in well under a frame; a big one moves off the main thread.
        value = if (items.size <= 120) build() else withContext(Dispatchers.Default) { build() }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showLinks by remember { mutableStateOf(true) }
    val highlight = remember(graph, query) {
        if (query.isBlank()) emptySet() else graph.nodes.filter { it.text.contains(query, ignoreCase = true) }.map { it.id }.toSet()
    }
    val activity = vm.brainActivity
    val running = vm.threads.filter { it.status == ThreadStatus.RUNNING }
    val lastTs = activity.lastOrNull()?.ts ?: 0L

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastTs, running.size, motion) {
        now = System.currentTimeMillis()
        if (!motion) {
            // Still mode: redraw once when a highlight should disappear.
            val left = STILL_HIGHLIGHT_MS - (now - lastTs)
            if (left > 0) { delay(left + 50); now = System.currentTimeMillis() }
            return@LaunchedEffect
        }
        while (true) {
            now = System.currentTimeMillis()
            val pulsing = now - lastTs < PULSE_MS
            if (!pulsing && running.isEmpty()) break
            delay(if (pulsing) 16L else 66L)
        }
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = p.dim, fontWeight = FontWeight.Medium)
    val hubStyle = TextStyle(fontSize = 9.sp, color = p.dim)
    val counts = remember(graph) {
        "${graph.nodes.count { it.space == Space.PERSONAL && it.threadId == null }} personal, ${graph.nodes.count { it.space == Space.WORK && it.threadId == null }} work, " +
            "${graph.nodes.count { it.threadId != null }} task notes, ${graph.edges.size} links"
    }

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1.06f)
                .semantics { contentDescription = "Memory brain: $counts. Use the list view to read each memory." }
                .pointerInput(graph) {
                    detectTapGestures { pos ->
                        val m = BrainMapping(size.width.toFloat(), size.height.toFloat(), zoom, pan)
                        val hit = graph.nodes.minByOrNull { n -> val s = m.map(n.x, n.y); hypot(s.x - pos.x, s.y - pos.y) }
                        selected = hit?.takeIf { n -> val s = m.map(n.x, n.y); hypot(s.x - pos.x, s.y - pos.y) < 28.dp.toPx() }?.id
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.7f, 5f)
                        pan += panChange
                    }
                },
        ) {
            val m = BrainMapping(size.width, size.height, zoom, pan)
            drawAnatomy(m, p.dim, p.faint)
            for ((text, x, y) in listOf(Triple("personal", -0.47f, -0.78f), Triple("work", 0.47f, -0.78f), Triple("tasks", 0f, 0.62f))) {
                val layout = measurer.measure(text, labelStyle)
                val s = m.map(x, y)
                drawText(layout, topLeft = Offset(s.x - layout.size.width / 2f, s.y - layout.size.height / 2f))
            }
            if (graph.nodes.isEmpty()) return@Canvas
            val nodeR = (4.dp.toPx() * min(zoom, 2.2f)).coerceAtLeast(2.5.dp.toPx())

            // Links: task spokes, then similarity links.
            graph.nodes.forEach { n ->
                val hub = graph.hub(n.threadId) ?: return@forEach
                drawLine(p.faint.copy(alpha = 0.55f), m.map(hub.x, hub.y), m.map(n.x, n.y), strokeWidth = 1f)
            }
            if (showLinks) graph.edges.forEach { e ->
                val a = graph.nodes[e.a]; val b = graph.nodes[e.b]
                val sel = selected == a.id || selected == b.id
                drawLine((if (sel) p.accent else p.dim).copy(alpha = if (sel) 0.7f else 0.06f + 0.16f * e.weight),
                    m.map(a.x, a.y), m.map(b.x, b.y), strokeWidth = if (sel) 2f else 1f)
            }
            graph.hubs.forEach { h ->
                val c = m.map(h.x, h.y)
                drawCircle(p.dim.copy(alpha = 0.7f), nodeR * 0.7f, c, style = Stroke(1.5f))
                val t = measurer.measure(if (h.title.length > 18) h.title.take(17) + "…" else h.title, hubStyle)
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y + m.size(0.13f)))
            }

            // Recently touched memories (rings, also the reduced-motion signal).
            val recent = HashMap<String, Float>()
            activity.forEach { a ->
                val age = now - a.ts
                val window = if (motion) PULSE_MS else STILL_HIGHLIGHT_MS
                if (age in 0 until window && a.kind != MemoryActivity.Kind.FORGET) a.ids.forEach { id -> recent[id] = maxOf(recent[id] ?: 0f, 1f - age / window.toFloat()) }
            }

            // Nodes.
            graph.nodes.forEach { n ->
                val s = m.map(n.x, n.y)
                val hot = n.id in highlight || n.id == selected
                val dim = highlight.isNotEmpty() && n.id !in highlight
                val base = when {
                    hot -> p.accent
                    n.space == Space.WORK -> p.fg.copy(alpha = 0.85f)
                    else -> p.fg
                }
                val c = base.copy(alpha = if (dim) 0.18f else if (n.kind.longTerm) base.alpha else 0.55f)
                val r = nodeR * (if (n.pinned) 1.45f else 1f) * (if (n.kind.longTerm) 1f else 0.75f)
                drawNode(n.kind, s, r, c)
                if (n.pinned) drawCircle(c, r * 1.9f, s, style = Stroke(1f))
                recent[n.id]?.let { f -> drawCircle(p.accent.copy(alpha = 0.85f * f), r * (2.2f + if (motion) (1f - f) * 2.5f else 0f), s, style = Stroke(2f)) }
                if (n.id == selected) drawCircle(p.accent, r * 2.4f, s, style = Stroke(2.5f))
            }

            // The core, breathing while tasks run, with one orbiting satellite per running task.
            val core = m.map(CORE.x, CORE.y)
            val breathe = if (motion && running.isNotEmpty()) (sin(now / 380.0) * 0.18 + 1).toFloat() else 1f
            drawCircle(p.accent.copy(alpha = 0.16f), nodeR * 4.2f * breathe, core)
            drawCircle(p.accent, nodeR * 1.9f, core)
            drawCircle(p.bg, nodeR * 0.8f, core)
            running.forEachIndexed { i, _ ->
                val a = (if (motion) now / 1400.0 else 0.0) + i * 2 * PI / running.size
                drawCircle(p.accent, nodeR * 0.9f, Offset(core.x + cos(a).toFloat() * nodeR * 6.5f, core.y + sin(a).toFloat() * nodeR * 6.5f))
            }

            // Pulses: from the core (or the task's cluster) to each memory recalled or written.
            if (motion) activity.forEach { a ->
                val age = now - a.ts
                if (age !in 0 until PULSE_MS || a.kind == MemoryActivity.Kind.FORGET) return@forEach
                val hub = graph.hub(a.by)
                val from = hub?.let { m.map(it.x, it.y) } ?: core
                val u = (age / TRAVEL_MS.toFloat()).coerceAtMost(1f)
                a.ids.take(10).forEachIndexed { k, id ->
                    val n = graph.indexOf(id)?.let { graph.nodes[it] } ?: return@forEachIndexed
                    val to = m.map(n.x, n.y)
                    val uu = ((u * 1.15f) - k * 0.03f).coerceIn(0f, 1f)
                    if (uu >= 1f) return@forEachIndexed
                    val ctrl = Offset((from.x + to.x) / 2 + (to.y - from.y) * 0.25f, (from.y + to.y) / 2 - (to.x - from.x) * 0.25f)
                    for (trail in 0..3) {
                        val tt = (uu - trail * 0.035f).coerceAtLeast(0f)
                        val pt = bezier(from, ctrl, to, tt)
                        drawCircle(p.accent.copy(alpha = 0.9f - trail * 0.22f), nodeR * (0.95f - trail * 0.15f), pt)
                    }
                }
            }
        }

        // Selected memory, legend and live activity.
        val sel = selected?.let { id -> items.firstOrNull { it.id == id } }
        if (sel != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(if (Netrunner) 2.dp else 12.dp)).background(p.surface).padding(12.dp)) {
                Text(plainText(sel.text), style = lStyle(14.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                val date = remember(sel.updatedAt) { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(sel.updatedAt)) }
                Text("${sel.kind.name.lowercase().replace('_', ' ')} · ${sel.scope.threadId?.let { "task: ${titles[it] ?: "a finished task"}" } ?: sel.scope.space.name.lowercase()} · from ${memorySource(sel.source)} · $date",
                    style = lStyle(11.sp, color = p.dim), modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Open in list", selected = true) { onOpen(sel.id) }
                    Chip(if (sel.pinned) "Unpin" else "Pin") { vm.editMemory(sel, pinned = !sel.pinned) }
                    Chip("Close") { selected = null }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (showLinks) "Links on" else "Links off", selected = showLinks) { showLinks = !showLinks }
            Chip("Fit") { zoom = 1f; pan = Offset.Zero }
        }
        Text("● fact  ◆ preference  ■ decision  ▲ note  ○ task summary", style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp))
        Text(
            if (graph.hidden > 0) "$counts · ${graph.hidden} older memories not shown" else counts,
            style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp),
        )
        activity.asReversed().take(3).forEach { a -> Text(describe(a, titles, items, now), style = lStyle(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Layout units → canvas pixels, fitted to the canvas, then zoomed and panned. */
private class BrainMapping(w: Float, h: Float, zoom: Float, pan: Offset) {
    private val scale = min(w / 2.2f, h / 1.95f) * zoom
    private val origin = Offset(w / 2f + pan.x, h / 2f + pan.y)
    fun map(x: Float, y: Float) = Offset(origin.x + x * scale, origin.y + (y - 0.06f) * scale)
    fun size(v: Float) = v * scale
}

private fun DrawScope.drawAnatomy(m: BrainMapping, dim: Color, faint: Color) {
    // Two hemispheres seen from above, a midline, and the task area below.
    for (cx in listOf(-0.47f, 0.47f)) {
        val tl = m.map(cx - 0.45f, -0.74f)
        drawOval(faint.copy(alpha = 0.5f), tl, Size(m.size(0.9f), m.size(1.32f)), style = Stroke(1.2f))
    }
    drawLine(dim.copy(alpha = 0.35f), m.map(0f, -0.7f), m.map(0f, 0.5f), strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    val tl = m.map(-0.8f, 0.66f)
    drawRoundRect(faint.copy(alpha = 0.35f), tl, Size(m.size(1.6f), m.size(0.3f)), androidx.compose.ui.geometry.CornerRadius(m.size(0.15f)), style = Stroke(1f))
}

private fun DrawScope.drawNode(kind: MemoryKind, c: Offset, r: Float, color: Color) {
    when (kind) {
        MemoryKind.FACT -> drawCircle(color, r, c)
        MemoryKind.PREFERENCE -> drawPath(Path().apply {
            moveTo(c.x, c.y - r * 1.3f); lineTo(c.x + r * 1.3f, c.y); lineTo(c.x, c.y + r * 1.3f); lineTo(c.x - r * 1.3f, c.y); close()
        }, color)
        MemoryKind.DECISION -> drawRect(color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
        MemoryKind.NOTE -> drawPath(Path().apply {
            moveTo(c.x, c.y - r * 1.25f); lineTo(c.x + r * 1.15f, c.y + r * 0.9f); lineTo(c.x - r * 1.15f, c.y + r * 0.9f); close()
        }, color)
        MemoryKind.SUMMARY, MemoryKind.TASK_STATE -> drawCircle(color, r, c, style = Stroke(1.5f))
    }
}

private fun bezier(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val u = 1 - t
    return Offset(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
}

private fun describe(a: MemoryActivity, titles: Map<String, String>, items: List<MemoryItem>, now: Long): String {
    val ago = ((now - a.ts) / 1000).coerceAtLeast(0).let { if (it < 60) "${it}s" else "${it / 60}m" }
    val task = a.by?.let { titles[it] }?.let { " for “$it”" } ?: ""
    return when (a.kind) {
        MemoryActivity.Kind.RECALL -> "$ago  ↯ recalled ${a.ids.size} memor${if (a.ids.size == 1) "y" else "ies"}$task"
        MemoryActivity.Kind.WRITE -> "$ago  + remembered “${items.firstOrNull { it.id == a.ids.first() }?.let { plainText(it.text).take(60) } ?: "a memory"}”$task"
        MemoryActivity.Kind.FORGET -> "$ago  − forgot a memory"
    }
}

/** Node glyph legend for screen readers and tests. */
fun BrainNode.glyph(): String = when (kind) {
    MemoryKind.FACT -> "●"; MemoryKind.PREFERENCE -> "◆"; MemoryKind.DECISION -> "■"; MemoryKind.NOTE -> "▲"
    MemoryKind.SUMMARY, MemoryKind.TASK_STATE -> "○"
}
