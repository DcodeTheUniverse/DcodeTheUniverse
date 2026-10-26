package com.axiom.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.core.graphics.createBitmap
import com.axiom.launcher.platform.ProfileInfo
import com.axiom.launcher.platform.ProfileKind

/** Profile-aware app discovery and launching through LauncherApps (the supported API for launchers). */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val iconCache = LruCache<String, Bitmap>(256)

    fun load(renames: Map<String, String>, profiles: List<ProfileInfo>): List<AppInfo> {
        val me = Process.myUserHandle()
        val out = ArrayList<AppInfo>()
        val list = profiles.ifEmpty { listOf(ProfileInfo(me, ProfileKind.PERSONAL, paused = false, unlocked = true)) }
        for (p in list) {
            // A locked Private Space must not reveal its apps.
            if (p.kind == ProfileKind.PRIVATE && p.paused) continue
            val acts = runCatching { launcherApps.getActivityList(null, p.user) }.getOrDefault(emptyList())
            for (info in acts) {
                val cn = info.componentName
                if (cn.packageName == context.packageName && p.user == me) continue
                val key = keyOf(cn.packageName, cn.className, p.user)
                val original = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                out += AppInfo(
                    key = key,
                    label = renames[key] ?: original,
                    originalLabel = original,
                    component = cn,
                    user = p.user,
                    isWork = p.kind == ProfileKind.WORK,
                    category = AppCategory.fromSystem(info.applicationInfo.category, cn.packageName),
                    profile = p.kind,
                    paused = p.paused,
                    suspended = (info.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun icon(app: AppInfo, sizePx: Int): Bitmap? {
        iconCache.get(app.key)?.let { return it }
        val bmp = runCatching {
            val act = launcherApps.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(app.component), app.user) ?: return null
            val d = act.getBadgedIcon(0) // includes the system's work-profile badge
            val b = createBitmap(sizePx, sizePx)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(Canvas(b))
            b
        }.getOrNull() ?: return null
        iconCache.put(app.key, bmp)
        return bmp
    }

    fun launch(app: AppInfo) = launcherApps.startMainActivity(app.component, app.user, null, null)

    fun appDetails(app: AppInfo) = launcherApps.startAppDetailsActivity(app.component, app.user, null, null)

    fun registerCallback(onChange: () -> Unit): LauncherApps.Callback {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChange()
            override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) = onChange()
            override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) = onChange()
        }
        launcherApps.registerCallback(cb)
        return cb
    }

    fun unregister(cb: LauncherApps.Callback) = launcherApps.unregisterCallback(cb)

    fun clearIcons() = iconCache.evictAll()

    private fun keyOf(pkg: String, cls: String, user: UserHandle) = "$pkg/$cls/${user.hashCode()}"
}
