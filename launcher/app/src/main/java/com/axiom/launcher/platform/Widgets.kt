package com.axiom.launcher.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.axiom.launcher.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A widget placed on the home screen; [heightDp] is the slot the user chose. */
data class HomeWidget(val id: Int, val provider: String, val heightDp: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("provider", provider).put("h", heightDp)

    companion object {
        fun fromJson(o: JSONObject) = HomeWidget(o.getInt("id"), o.optString("provider"), o.optInt("h", 180))
    }
}

/** Height presets offered in the widget menu. */
val WIDGET_HEIGHTS = listOf(110, 180, 260, 360)

/**
 * Holds a widget's view. A long press opens Axiom's widget menu and the widget doesn't also get a click;
 * taps, scrolling and the widget's own buttons work as usual.
 */
@SuppressLint("ViewConstructor")
class WidgetFrame(context: Context, val hostView: AppWidgetHostView?) : FrameLayout(context) {
    var onLongPress: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    init {
        hostView?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false; downX = ev.x; downY = ev.y
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        super.dispatchTouchEvent(ev)
        // Keep the whole gesture even where the widget has nothing to tap: the timer has to see the finger
        // lift, and the home screen behind mustn't start its own long press.
        return true
    }

    // Once the long press fired, the children get a cancel instead of the rest of the gesture.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = fired

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Widget options"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && onLongPress != null) { onLongPress?.invoke(); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        // Not during layout: a widget with responsive layouts swaps its views when told its size.
        post { applySize(w / d, h / d) }
    }

    /** Tells the widget how much room it has, so it can pick a layout. */
    fun applySize(widthDp: Float, heightDp: Float) {
        val v = hostView ?: return
        if (widthDp <= 0f || heightDp <= 0f) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
        }
    }
}

private const val HOST_ID = 0x0A11

/** Request code for a widget's own setup screen (below the range ActivityResultRegistry uses). */
const val REQUEST_WIDGET_CONFIGURE = 0x0A12

/**
 * Hosts ordinary Android widgets on Axiom's home screen: lists what's installed (personal and work, never
 * Private space), binds with the system's permission dialog when needed, runs a widget's own setup screen,
 * and remembers placement and height. Widgets only receive updates while the launcher is visible.
 */
class Widgets(private val app: Context, private val prefs: Prefs, private val profiles: () -> List<ProfileInfo>) {
    val manager: AppWidgetManager? = runCatching { AppWidgetManager.getInstance(app) }.getOrNull()
    private val host: AppWidgetHost? = runCatching { AppWidgetHost(app, HOST_ID) }.getOrNull()
    private val _placed = MutableStateFlow(load())
    val placed: StateFlow<List<HomeWidget>> = _placed.asStateFlow()

    /** For screenshots and tests: draws placements without a real widget behind them. */
    internal var debugView: ((Context, HomeWidget) -> View)? = null

    private fun load(): List<HomeWidget> = runCatching {
        val a = JSONArray(prefs.widgetsJson ?: "[]")
        (0 until a.length()).map { HomeWidget.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<HomeWidget>) {
        _placed.value = list
        prefs.widgetsJson = JSONArray(list.map { it.toJson() }).toString()
    }

    /** Widgets installed for the personal and work profiles (not paused), by app then name. */
    fun providers(): List<AppWidgetProviderInfo> {
        val m = manager ?: return emptyList()
        val users = profiles().filter { it.kind != ProfileKind.PRIVATE && !it.paused }.map { it.user }
            .ifEmpty { listOf(android.os.Process.myUserHandle()) }
        val pm = app.packageManager
        return users.flatMap { u -> runCatching { m.getInstalledProvidersForProfile(u) }.getOrDefault(emptyList()) }
            .sortedWith(compareBy({ appLabel(it).lowercase() }, { it.loadLabel(pm).lowercase() }))
    }

    fun appLabel(info: AppWidgetProviderInfo): String = runCatching {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString()
    }.getOrDefault(info.provider.packageName)

    fun label(info: AppWidgetProviderInfo): String = runCatching { info.loadLabel(app.packageManager) }.getOrNull() ?: info.provider.className

    fun allocate(): Int = runCatching { host?.allocateAppWidgetId() }.getOrNull() ?: -1

    /** True when Android lets Axiom bind without asking; otherwise the bind dialog has to be shown. */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { manager?.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) == true }.getOrDefault(false)

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager?.getAppWidgetInfo(id) }.getOrNull()

    /** Whether the widget needs its own setup screen before it can be shown. */
    fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 31 && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0) return false
        return true
    }

    /** Whether the widget's setup screen can be reopened later ("Widget settings"). */
    fun reconfigurable(info: AppWidgetProviderInfo): Boolean =
        info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0

    /** Opens the widget's setup screen through the system (works for work-profile and non-exported screens). */
    fun startConfigure(activity: Activity, id: Int): Boolean {
        val h = host ?: return false
        return runCatching { h.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_WIDGET_CONFIGURE, null); true }.getOrDefault(false)
    }

    /** The view for a placed widget, or null if it can't be shown (its app is gone or the profile is off). */
    fun frameFor(context: Context, w: HomeWidget): WidgetFrame? {
        debugView?.let { make -> return WidgetFrame(context, null).apply { addView(make(context, w)) } }
        val info = info(w.id) ?: return null
        val view = runCatching { host?.createView(context, w.id, info) }.getOrNull() ?: return null
        return WidgetFrame(context, view)
    }

    fun available(w: HomeWidget): Boolean = debugView != null || info(w.id) != null

    fun place(id: Int, info: AppWidgetProviderInfo) {
        val density = app.resources.displayMetrics.density
        val h = (info.minHeight / density).toInt()
        val slot = WIDGET_HEIGHTS.firstOrNull { it >= h + 16 } ?: WIDGET_HEIGHTS.last()
        save(_placed.value.filterNot { it.id == id } + HomeWidget(id, info.provider.flattenToString(), slot))
    }

    fun remove(id: Int) {
        runCatching { host?.deleteAppWidgetId(id) }
        save(_placed.value.filterNot { it.id == id })
    }

    /** Releases an id that was allocated but never placed (the user cancelled). */
    fun discard(id: Int) {
        if (id >= 0 && _placed.value.none { it.id == id }) runCatching { host?.deleteAppWidgetId(id) }
    }

    fun resize(id: Int, heightDp: Int) = save(_placed.value.map { if (it.id == id) it.copy(heightDp = heightDp) else it })

    fun move(id: Int, delta: Int) {
        val l = _placed.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i == j) return
        l.add(j, l.removeAt(i))
        save(l)
    }

    /**
     * Frees ids this host still holds but never placed, e.g. when the process died while a widget's setup
     * screen was open. Only call this when no add flow is in progress.
     */
    fun releaseOrphans() {
        val ids = runCatching { host?.appWidgetIds }.getOrNull() ?: return
        val placedIds = _placed.value.map { it.id }.toSet()
        ids.filter { it !in placedIds }.forEach { runCatching { host?.deleteAppWidgetId(it) } }
    }

    fun startListening() { runCatching { host?.startListening() } }
    fun stopListening() { runCatching { host?.stopListening() } }

    /** For tests and screenshots: a placement without a real widget behind it. */
    internal fun debugPlace(w: HomeWidget) = save(_placed.value + w)
}
