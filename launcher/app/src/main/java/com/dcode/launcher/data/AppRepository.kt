package com.dcode.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.core.graphics.createBitmap

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val iconCache = LruCache<String, Bitmap>(256)

    fun load(renames: Map<String, String>): List<AppInfo> {
        val me = Process.myUserHandle()
        val out = ArrayList<AppInfo>()
        for (user in userManager.userProfiles) {
            val list = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            for (info in list) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val key = keyOf(cn.packageName, cn.className, user)
                val original = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                out += AppInfo(
                    key = key,
                    label = renames[key] ?: original,
                    originalLabel = original,
                    component = cn,
                    user = user,
                    isWork = user != me,
                    category = AppCategory.fromSystem(info.applicationInfo.category, cn.packageName),
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun icon(app: AppInfo, sizePx: Int): Bitmap? {
        iconCache.get(app.key)?.let { return it }
        val bmp = runCatching {
            val act = launcherApps.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(app.component), app.user
            ) ?: return null
            val d = act.getBadgedIcon(0)
            val b = createBitmap(sizePx, sizePx)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(Canvas(b))
            b
        }.getOrNull() ?: return null
        iconCache.put(app.key, bmp)
        return bmp
    }

    fun launch(app: AppInfo) {
        launcherApps.startMainActivity(app.component, app.user, null, null)
    }

    fun appDetails(app: AppInfo) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    fun registerCallback(onChange: () -> Unit): LauncherApps.Callback {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = onChange()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = onChange()
        }
        launcherApps.registerCallback(cb)
        return cb
    }

    fun unregister(cb: LauncherApps.Callback) = launcherApps.unregisterCallback(cb)

    fun clearIcons() = iconCache.evictAll()

    private fun keyOf(pkg: String, cls: String, user: UserHandle) =
        "$pkg/$cls/${user.hashCode()}"
}
