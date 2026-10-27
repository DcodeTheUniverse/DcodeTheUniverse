package com.axiom.launcher

import android.app.Application
import com.axiom.launcher.platform.CrashLog
import com.axiom.launcher.platform.PerfMonitor
import com.axiom.launcher.solis.SolisHost

/**
 * Process-wide owner of the launcher core and the Solis brain, so background tasks, the foreground
 * service and routines never depend on an Activity being alive.
 */
class AxiomApp : Application() {
    val core: LauncherCore by lazy { LauncherCore(this) }
    val solis: SolisHost by lazy { SolisHost(this, core) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // The on-device model runs in its own process (":llm"); it needs none of the launcher.
        if (getProcessName().endsWith(":llm")) return
        PerfMonitor.markProcessStart()
        core.start()
        solis.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!getProcessName().endsWith(":llm")) solis.onDevice.client.onTrimMemory(level)
    }
}
