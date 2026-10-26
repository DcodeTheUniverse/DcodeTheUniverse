package com.axiom.launcher.platform

import android.app.Activity
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import com.axiom.launcher.core.DeviceInfo

/**
 * On-device performance instrumentation: cold-start time, frame times and jank, memory, phone battery
 * drain while Axiom runs, and Solis end-to-end latency. Collection is passive and cheap (frame metrics
 * arrive on a background thread); nothing leaves the phone. Shown in Settings › Diagnostics.
 */
object PerfMonitor {
    @Volatile private var startedAt = 0L
    @Volatile var startupMs: Long = -1; private set

    private val lock = Any()
    // Frame-time histogram in 1 ms buckets up to 250 ms (last bucket = slower).
    private val buckets = IntArray(251)
    private var frames = 0L
    private var janky = 0L
    private var frozen = 0L

    private val latencies = HashMap<String, ArrayDeque<Long>>()
    private val battery = ArrayDeque<Triple<Long, Int, Boolean>>()

    private val metricsThread by lazy { HandlerThread("axiom-frames").also { it.start() } }
    private val listeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()

    fun markProcessStart() {
        startedAt = Process.getStartUptimeMillis()
    }

    /** Records time from process start to the launcher's first drawn frame (once per process). */
    fun watchFirstFrame(activity: Activity) {
        if (startupMs >= 0) return
        val view = activity.window.decorView
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (startupMs < 0) startupMs = SystemClock.uptimeMillis() - (startedAt.takeIf { it > 0 } ?: Process.getStartUptimeMillis())
                view.post { runCatching { view.viewTreeObserver.removeOnDrawListener(this) } }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }

    fun attach(window: Window) {
        if (listeners.containsKey(window)) return
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
            synchronized(lock) {
                frames++
                buckets[(total / 1_000_000L).toInt().coerceIn(0, 250)]++
                if (total > deadline) janky++
                if (total > 700_000_000L) frozen++
            }
        }
        listeners[window] = l
        runCatching { window.addOnFrameMetricsAvailableListener(l, Handler(metricsThread.looper)) }
    }

    fun detach(window: Window) {
        listeners.remove(window)?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
    }

    fun recordLatency(kind: String, ms: Long) = synchronized(lock) {
        val q = latencies.getOrPut(kind) { ArrayDeque() }
        q.addLast(ms)
        while (q.size > 50) q.removeFirst()
    }

    /** Called on battery broadcasts (via DeviceConditions): phone-wide level samples for a drain estimate. */
    fun sampleBattery(pct: Int, charging: Boolean) = synchronized(lock) {
        val last = battery.lastOrNull()
        if (last != null && last.second == pct && last.third == charging) return@synchronized
        battery.addLast(Triple(SystemClock.elapsedRealtime(), pct, charging))
        while (battery.size > 200) battery.removeFirst()
    }

    private fun percentile(p: Double): Int {
        if (frames == 0L) return 0
        val target = (frames * p).toLong().coerceAtLeast(1)
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= target) return i
        }
        return 250
    }

    /** Phone battery drain in %/hour across recent unplugged stretches, or null without enough data. */
    private fun drainPerHour(): Double? {
        var drop = 0
        var ms = 0L
        battery.zipWithNext().forEach { (a, b) ->
            if (!a.third && !b.third && b.second <= a.second) {
                drop += a.second - b.second
                ms += b.first - a.first
            }
        }
        return if (ms >= 20 * 60_000L && drop > 0) drop * 3_600_000.0 / ms else null
    }

    fun report(context: Context): String = synchronized(lock) {
        val pss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L }.getOrDefault(-1L)
        val bm = context.getSystemService(BatteryManager::class.java)
        val charge = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(-1L)
        buildString {
            appendLine("startup: ${if (startupMs >= 0) "$startupMs ms to first frame (this process)" else "not measured yet"}")
            if (frames > 0) {
                appendLine("frames: $frames · p50 ${percentile(0.5)} ms · p90 ${percentile(0.9)} ms · p99 ${percentile(0.99)} ms")
                appendLine("jank: ${"%.1f".format(janky * 100.0 / frames)}% missed deadline · $frozen frozen (>700 ms)")
            } else appendLine("frames: none recorded yet")
            appendLine("memory: PSS ${if (pss >= 0) DeviceInfo.human(pss) else "n/a"} · Java heap ${DeviceInfo.human(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())}")
            appendLine("battery (whole phone while Axiom runs): " + (drainPerHour()?.let { "%.1f%%/h unplugged".format(it) } ?: "collecting…") +
                if (charge > 0) " · counter ${charge / 1000} mAh" else "")
            latencies.toSortedMap().forEach { (k, q) ->
                val s = q.sorted()
                appendLine("solis $k: n=${s.size} · median ${s[s.size / 2]} ms · p90 ${s[((s.size - 1) * 0.9).toInt()]} ms")
            }
        }.trimEnd()
    }
}
