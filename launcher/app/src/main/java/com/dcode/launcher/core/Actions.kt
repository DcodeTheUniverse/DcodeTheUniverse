package com.dcode.launcher.core

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioManager
import android.view.KeyEvent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Every "do something on the phone" lives here, shared by the omnibox, the UI and the AI agent. */
class Actions(private val context: Context) {

    var torchOn = false
        private set

    fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun setTimer(seconds: Int, label: String?): Boolean = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_399))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Dcode timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun setAlarm(hour: Int, minute: Int, label: String?): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Dcode alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun showAlarms(): Boolean = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun dial(number: String): Boolean = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))

    fun sms(to: String, body: String?): Boolean = start(
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(to)}")).putExtra("sms_body", body ?: "")
    )

    fun email(to: String, subject: String?, body: String?): Boolean = start(
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}"))
            .putExtra(Intent.EXTRA_SUBJECT, subject ?: "")
            .putExtra(Intent.EXTRA_TEXT, body ?: "")
    )

    fun webSearch(query: String): Boolean =
        start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            openUrl("https://www.google.com/search?q=${Uri.encode(query)}")

    fun openUrl(url: String): Boolean {
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun navigate(destination: String): Boolean =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))

    fun playMedia(query: String): Boolean = start(
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
    )

    /** play / pause / toggle / next / previous for whatever app owns media playback. */
    fun mediaKey(action: String): Boolean {
        val code = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    fun isMusicActive(): Boolean = context.getSystemService(AudioManager::class.java).isMusicActive

    /** Sets media volume to [percent] (0-100), or nudges it by [delta] steps. Returns the new percent. */
    fun setVolume(percent: Int? = null, delta: Int = 0): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = if (percent != null) (percent.coerceIn(0, 100) * max + 50) / 100
        else (am.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return if (max == 0) 0 else target * 100 / max
    }

    fun clipboard(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    fun setClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dcode", text))
    }

    fun camera(): Boolean = start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    fun calendarEvent(title: String, startMs: Long?, endMs: Long?, location: String?, description: String?): Boolean {
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        startMs?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        (endMs ?: startMs?.plus(3_600_000))?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        description?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return start(i)
    }

    fun openCalendar(): Boolean = start(
        Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
    )

    fun toggleTorch(on: Boolean? = null): Boolean = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else {
            torchOn = on ?: !torchOn
            cm.setTorchMode(id, torchOn)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun openSettings(panel: String): Boolean {
        val action = SETTINGS[panel.lowercase().trim()] ?: Settings.ACTION_SETTINGS
        return start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))
    }

    fun openFile(path: String): Boolean {
        val f = File(path)
        if (f.isDirectory) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            start(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    f.name
                )
            )
        } catch (e: Exception) {
            false
        }
    }

    fun shareFile(path: String): Boolean = try {
        val f = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        start(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"
            )
        )
    } catch (e: Exception) {
        false
    }

    fun shareText(text: String): Boolean = start(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")
    )

    /** Hands a prompt to the official Claude app, which runs on the user's Claude subscription. */
    fun askClaudeApp(prompt: String?): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(CLAUDE_PKG)
        if (launch == null) {
            return start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CLAUDE_PKG"))) ||
                openUrl("https://play.google.com/store/apps/details?id=$CLAUDE_PKG")
        }
        if (prompt.isNullOrBlank()) return start(launch)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(CLAUDE_PKG)
            .putExtra(Intent.EXTRA_TEXT, prompt)
        return start(send) || start(launch)
    }

    fun isClaudeAppInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PKG) != null

    @SuppressLint("WrongConstant")
    fun expandNotifications(): Boolean = try {
        val sbs = context.getSystemService("statusbar")
        sbs.javaClass.getMethod("expandNotificationsPanel").invoke(sbs)
        true
    } catch (e: Exception) {
        false
    }

    @SuppressLint("WrongConstant")
    fun expandQuickSettings(): Boolean = try {
        val sbs = context.getSystemService("statusbar")
        sbs.javaClass.getMethod("expandSettingsPanel").invoke(sbs)
        true
    } catch (e: Exception) {
        false
    }

    fun uninstall(pkg: String): Boolean =
        start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    fun chooseDefaultLauncher(): Boolean = start(Intent(Settings.ACTION_HOME_SETTINGS)) ||
        start(Intent(Settings.ACTION_SETTINGS))

    fun allFilesAccessSettings(): Boolean =
        start(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) ||
            start(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))

    fun accessibilitySettings(): Boolean = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun notificationAccessSettings(): Boolean = start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    companion object {
        const val CLAUDE_PKG = "com.anthropic.claude"

        val SETTINGS: Map<String, String> = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "internet" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "brightness" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "volume" to Settings.Panel.ACTION_VOLUME,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "notifications" to "android.settings.NOTIFICATION_SETTINGS",
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "time" to Settings.ACTION_DATE_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "hotspot" to "android.settings.TETHER_SETTINGS",
            "data" to Settings.ACTION_DATA_USAGE_SETTINGS,
            "mobile" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
            "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
            "default apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            "launcher" to Settings.ACTION_HOME_SETTINGS,
            "dnd" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            "do not disturb" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            "vpn" to Settings.ACTION_VPN_SETTINGS,
            "cast" to Settings.ACTION_CAST_SETTINGS,
            "settings" to Settings.ACTION_SETTINGS,
        )

        /** Friendly names shown as search results. */
        val SETTINGS_TITLES = listOf(
            "Wi-Fi" to "wifi", "Bluetooth" to "bluetooth", "Display & brightness" to "display",
            "Sound & volume" to "sound", "Battery" to "battery", "Apps" to "apps", "Storage" to "storage",
            "Location" to "location", "Notifications" to "notifications", "Accessibility" to "accessibility",
            "Date & time" to "date", "Language" to "language", "Keyboard" to "keyboard",
            "Security" to "security", "Privacy" to "privacy", "Airplane mode" to "airplane",
            "Hotspot & tethering" to "hotspot", "Data usage" to "data", "Developer options" to "developer",
            "About phone" to "about", "Default apps" to "default apps", "Do not disturb" to "dnd",
            "VPN" to "vpn", "Cast" to "cast", "NFC" to "nfc", "Internet panel" to "internet",
        )
    }
}
