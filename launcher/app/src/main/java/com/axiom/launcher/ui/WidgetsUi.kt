package com.axiom.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.Overlay
import com.axiom.launcher.core.Fuzzy
import com.axiom.launcher.platform.HomeWidget
import com.axiom.launcher.platform.WIDGET_HEIGHTS
import com.axiom.launcher.platform.WidgetFrame
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The home screen's widgets, stacked in its free space; scrolls only when they don't fit. */
@Composable
fun WidgetArea(vm: LauncherViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        vm.widgets.forEach { w -> key(w.id) { WidgetSlot(vm, w) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetSlot(vm: LauncherViewModel, w: HomeWidget) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(18.dp)
    // Re-checked when profiles change: a paused work profile hides its widgets until it's back.
    val available = remember(w.id, vm.profiles) { vm.core.widgets.available(w) }
    val slot = Modifier.fillMaxWidth().height(w.heightDp.dp).clip(shape)
    if (available) {
        AndroidView(
            factory = { c -> vm.core.widgets.frameFor(c, w) ?: WidgetFrame(c, null) },
            modifier = slot,
            update = { it.onLongPress = { vm.widgetMenu = w.id } },
            onRelease = { it.onLongPress = null },
        )
    } else {
        Column(
            slot.border(1.dp, p.faint, shape)
                .combinedClickable(onClick = { vm.widgetMenu = w.id }, onLongClick = { vm.widgetMenu = w.id })
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (Netrunner) "widget: offline" else "Widget unavailable", style = lStyle(14.sp, FontWeight.Medium))
            Text("Its app was removed or its profile is paused. Tap for options.", style = lStyle(12.sp, color = p.dim))
        }
    }
}

/** Long-press menu for a widget: height, order, its own settings, remove. */
@Composable
fun WidgetMenu(vm: LauncherViewModel, id: Int, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val w = vm.widgets.firstOrNull { it.id == id }
    if (w == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val widgets = vm.core.widgets
    val info = remember(id) { widgets.info(id) }
    LDialog(onDismiss) {
        Text(info?.let { widgets.label(it) } ?: "Widget", style = lStyle(22.sp, FontWeight.Medium))
        Text(
            info?.let { widgets.appLabel(it) + if (it.profile != Process.myUserHandle()) " · work" else "" } ?: "Not available right now",
            style = lStyle(12.sp, color = p.dim),
        )
        Text("Height", style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WIDGET_HEIGHTS.zip(listOf("S", "M", "L", "XL")).forEach { (h, name) -> Chip(name, w.heightDp == h) { vm.resizeWidget(id, h) } }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.widgets.size > 1) Row {
            Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.ArrowUpward, "Move up") { vm.moveWidget(id, -1) } }
            Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.ArrowDownward, "Move down") { vm.moveWidget(id, 1) } }
        }
        if (info != null && widgets.reconfigurable(info)) ActionRow(Icons.Outlined.Tune, "Widget settings") { onDismiss(); vm.reconfigureWidget(id) }
        ActionRow(Icons.Outlined.Add, "Add another widget") { onDismiss(); vm.overlayRequest = Overlay.WIDGETS }
        ActionRow(Icons.Outlined.Delete, "Remove from home") { vm.removeWidget(id); onDismiss() }
    }
}

/** Long-press on an empty part of the home screen. */
@Composable
fun HomeMenu(vm: LauncherViewModel, openWidgets: () -> Unit, openSettings: () -> Unit, onDismiss: () -> Unit) {
    LDialog(onDismiss) {
        Text(if (Netrunner) "~/home" else "Home screen", style = lStyle(22.sp, FontWeight.Medium))
        Spacer(Modifier.height(8.dp))
        ActionRow(Icons.Outlined.Widgets, "Add a widget", "Clock, calendar, weather, notes and more from your apps") { onDismiss(); openWidgets() }
        ActionRow(Icons.Outlined.SwapHoriz, "Change mode", "${vm.mode.glyph} ${vm.mode.name} mode is active") { onDismiss(); vm.modeSheet = true }
        ActionRow(Icons.Outlined.Settings, "Settings") { onDismiss(); openSettings() }
    }
}

/** One installable widget, with labels resolved once (PackageManager calls are slow). */
private class PickItem(val info: AppWidgetProviderInfo, val app: String, val label: String, val detail: String)

private fun pickItems(ctx: Context, vm: LauncherViewModel): List<PickItem> {
    val widgets = vm.core.widgets
    val density = ctx.resources.displayMetrics.density
    val me = Process.myUserHandle()
    return widgets.providers().map { info ->
        val size = if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) "${info.targetCellWidth}×${info.targetCellHeight}"
        else "${((info.minWidth / density + 30) / 70).toInt().coerceAtLeast(1)}×${((info.minHeight / density + 30) / 70).toInt().coerceAtLeast(1)}"
        val description = if (Build.VERSION.SDK_INT >= 31) runCatching { info.loadDescription(ctx)?.toString() }.getOrNull() else null
        val detail = listOfNotNull(size, description?.takeIf { it.isNotBlank() }, if (info.profile != me) "work" else null).joinToString(" · ")
        PickItem(info, widgets.appLabel(info) + if (info.profile != me) " (work)" else "", widgets.label(info), detail)
    }
}

private fun previewBitmap(ctx: Context, info: AppWidgetProviderInfo, maxW: Int, maxH: Int): ImageBitmap? {
    val d = runCatching { info.loadPreviewImage(ctx, 0) }.getOrNull() ?: runCatching { info.loadIcon(ctx, 0) }.getOrNull() ?: return null
    val iw = d.intrinsicWidth.takeIf { it > 0 } ?: maxW
    val ih = d.intrinsicHeight.takeIf { it > 0 } ?: maxH
    val s = minOf(maxW / iw.toFloat(), maxH / ih.toFloat(), 1f)
    return d.toBitmap((iw * s).toInt().coerceAtLeast(1), (ih * s).toInt().coerceAtLeast(1)).asImageBitmap()
}

/** Every widget the phone's apps offer (personal and work), searchable, grouped by app. */
@Composable
fun WidgetPicker(vm: LauncherViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val all by produceState<List<PickItem>?>(null) { value = withContext(Dispatchers.IO) { runCatching { pickItems(ctx, vm) }.getOrDefault(emptyList()) } }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(p.bg).blockTouches().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Netrunner) "widgets --add" else "Add a widget", style = lStyle(28.sp, FontWeight.Light), modifier = Modifier.weight(1f).semantics { heading() })
            IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { InputBar(filter, { filter = it }, "Search widgets", onSubmit = {}) }
        val list = all
        if (list == null) {
            Text("Loading widgets…", style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(24.dp))
            return@Column
        }
        val q = filter.trim()
        val shown = if (q.isEmpty()) list else list.filter { Fuzzy.score(q, it.label) > 0 || Fuzzy.score(q, it.app) > 0 }
        if (shown.isEmpty()) {
            Text(if (list.isEmpty()) "No app on this phone offers widgets." else "No widget matches “$q”.", style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            shown.groupBy { it.app }.forEach { (app, items) ->
                item(key = "app:$app") { SectionLabel(app) }
                items.forEach { item ->
                    item(key = item.info.provider.flattenToString() + "|" + item.info.profile.hashCode()) {
                        WidgetPickRow(item) { vm.pickWidget(item.info); onClose() }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPickRow(item: PickItem, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val maxW = with(LocalDensity.current) { 96.dp.roundToPx() }
    val maxH = with(LocalDensity.current) { 72.dp.roundToPx() }
    val img by produceState<ImageBitmap?>(null, item) { value = withContext(Dispatchers.IO) { runCatching { previewBitmap(ctx, item.info, maxW, maxH) }.getOrNull() } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)).background(p.surface), contentAlignment = Alignment.Center) {
            img?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, style = lStyle(16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.detail, style = lStyle(12.sp, color = p.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
