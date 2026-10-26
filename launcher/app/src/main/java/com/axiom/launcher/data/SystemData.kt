package com.axiom.launcher.data

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Calendar

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun upcoming(ctx: Context, days: Int = 1, limit: Int = 30): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, days.coerceIn(1, 31))
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath(end.toString()).build()
        val out = ArrayList<CalEvent>()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += CalEvent(c.getString(0) ?: "(untitled)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4)?.ifBlank { null })
                }
            }
        }
        return out.filter { it.end > now }
    }
}

data class NotifItem(
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val key: String,
    /** Posted in another profile (e.g. the work profile); kept out of personal-space tasks. */
    val otherProfile: Boolean = false,
)

/** Opt-in: keeps the latest notifications in memory so Claude can summarize them. Nothing is stored on disk. */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.forEach { add(it) } }
    }

    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { add(it) } }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(items) { items.removeAll { it.key == sbn.key } }
        version++
    }

    private fun add(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(items) {
            items.removeAll { it.key == sbn.key }
            items.add(0, NotifItem(label, title, text.take(400), sbn.postTime, sbn.key, otherProfile = sbn.user != android.os.Process.myUserHandle()))
            while (items.size > 60) items.removeAt(items.size - 1)
        }
        version++
    }

    companion object {
        @Volatile private var instance: NotificationWatcher? = null
        @Volatile var version = 0
            private set
        private val items = ArrayList<NotifItem>()

        fun snapshot(): List<NotifItem> = synchronized(items) { items.toList() }
        fun connected() = instance != null

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationWatcher::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
