package com.axiom.launcher

import android.app.Application
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
        PerfMonitor.markProcessStart()
        core.start()
        solis.start()
    }
}
