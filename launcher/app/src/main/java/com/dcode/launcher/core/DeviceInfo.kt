package com.dcode.launcher.core

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Battery(val percent: Int, val charging: Boolean)

object DeviceInfo {
    fun battery(context: Context): Battery {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Battery(pct, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    fun storage(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return st.availableBytes to st.totalBytes
    }

    fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "online"
        }
    }

    fun nextAlarm(context: Context): Long? =
        context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (h > 0 || d > 0) append("${h}h ")
            append("${m}m")
        }
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun model(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun nowLine(): String =
        SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()) + " (" + TimeZone.getDefault().id + ")"

    /** Compact context sent with each message so Claude knows "now" without breaking the prompt cache. */
    fun contextLine(context: Context): String {
        val b = battery(context)
        val (free, total) = storage()
        val alarm = nextAlarm(context)?.let { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it)) } ?: "none"
        return "now: ${nowLine()} | battery: ${b.percent}%${if (b.charging) " charging" else ""} | " +
            "network: ${network(context)} | storage free: ${human(free)} of ${human(total)} | next alarm: $alarm"
    }

    /** neofetch-style block for the Netrunner easter egg. */
    fun neofetch(context: Context, user: String): String {
        val b = battery(context)
        val (free, total) = storage()
        val dm = context.resources.displayMetrics
        return """
            |${user.ifBlank { "runner" }}@dcode
            |-----------------
            |OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            |Host: ${model()}
            |Kernel: ${System.getProperty("os.version") ?: "?"}
            |Uptime: ${uptime()}
            |Shell: dcode-sh 1.0 (claude-link)
            |Resolution: ${dm.widthPixels}x${dm.heightPixels}
            |CPU: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} x${Runtime.getRuntime().availableProcessors()}
            |Memory: ${human(Runtime.getRuntime().totalMemory())} heap
            |Disk: ${human(total - free)} / ${human(total)}
            |Battery: ${b.percent}%${if (b.charging) " ⚡" else ""}
            |Network: ${network(context)}
        """.trimMargin()
    }
}
