package com.axiom.launcher.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.MainActivity
import com.axiom.launcher.R
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.statusLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps user-started Solis tasks running while Axiom is in the background. It is started when a task
 * starts (the user is interacting, as Android 12+ requires) and stops itself as soon as nothing is active.
 * The notification only ever shows real engine state.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val solis = (application as AxiomApp).solis
        if (intent?.action == ACTION_PAUSE_ALL) {
            solis.engine.threads.value.filter { it.isActive }.forEach { solis.engine.pause(it.id) }
        }
        ensureChannel(this)
        val first = buildNotification(this, "Solis is working", "Starting…", 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, first)
        if (watcher == null) watcher = scope.launch {
            // Thread changes only (not streamed tokens). StateFlow is conflated, so the delay below caps
            // updates at one per 800 ms and each update shows the latest state.
            solis.engine.threads.collect { threads ->
                // Threads parked until the network returns don't need the service; the engine wakes them on change.
                val active = threads.filter { it.status == ThreadStatus.RUNNING || it.status == ThreadStatus.QUEUED }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val running = active.filter { it.status == ThreadStatus.RUNNING }
                val title = when {
                    running.size == 1 -> "Solis: ${running[0].title}"
                    running.isNotEmpty() -> "Solis is working on ${running.size} tasks"
                    else -> "Solis has ${active.size} task${if (active.size == 1) "" else "s"} queued"
                }
                val lead = running.firstOrNull() ?: active.first()
                val text = lead.statusLine
                val progress = if (lead.stepsTotal > 0) (lead.stepsDone * 100 / lead.stepsTotal) else 0
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(this@AgentService, title, text, progress))
                delay(800)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "solis_tasks"
        const val NOTIFICATION_ID = 4242
        const val ACTION_PAUSE_ALL = "com.axiom.launcher.PAUSE_ALL"

        fun start(context: Context) {
            runCatching {
                val i = Intent(context, AgentService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Solis tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of tasks you started with Solis"
                    setShowBadge(false)
                })
            }
        }

        fun buildNotification(context: Context, title: String, text: String, progress: Int): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SOLIS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val pause = PendingIntent.getService(context, 1, Intent(context, AgentService::class.java).setAction(ACTION_PAUSE_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open)
                .setProgress(100, progress, progress == 0)
                .addAction(Notification.Action.Builder(null, "Pause all", pause).build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
