package com.axiom.launcher.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.axiom.launcher.MainActivity
import com.axiom.launcher.R

/** One-off notifications about work that finished away from the screen (coding jobs). */
object Notify {
    const val CODING = "coding"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CODING) == null) {
            nm.createNotificationChannel(NotificationChannel(CODING, "Coding with Claude", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When Claude Code finishes, needs you, or a pull request is ready"
            })
        }
    }

    /** A half-price batch answer arrived (or failed); tapping it opens the task overview. */
    fun batch(context: Context, key: String, title: String, text: String) = coding(context, "batch:$key", title, text)

    /** Posts (or replaces) the notification for one coding job; tapping it opens the task overview. */
    fun coding(context: Context, key: String, title: String, text: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, key.hashCode(), Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TASKS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CODING)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(CODING, key.hashCode(), n) }
    }
}
