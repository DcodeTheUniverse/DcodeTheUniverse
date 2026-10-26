package com.axiom.launcher.data

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.UserHandle

enum class AppCategory(val title: String) {
    ALL("All"),
    SOCIAL("Social"),
    MEDIA("Media"),
    GAMES("Games"),
    WORK("Work"),
    TOOLS("Tools"),
    TRAVEL("Travel"),
    OTHER("Other");

    companion object {
        fun fromSystem(cat: Int, pkg: String): AppCategory {
            val byPkg = guessFromPackage(pkg)
            if (byPkg != null) return byPkg
            return when (cat) {
                ApplicationInfo.CATEGORY_GAME -> GAMES
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> MEDIA
                ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS -> SOCIAL
                ApplicationInfo.CATEGORY_MAPS -> TRAVEL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> WORK
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> TOOLS
                else -> OTHER
            }
        }

        private fun guessFromPackage(pkg: String): AppCategory? {
            val p = pkg.lowercase()
            return when {
                listOf("whatsapp", "telegram", "instagram", "facebook", "twitter", "messenger", "snapchat",
                    "discord", "reddit", "tiktok", "musically", "signal", "linkedin", "threads", "mastodon",
                    "bluesky", "messaging", "mms", ".talk").any { p.contains(it) } -> SOCIAL
                listOf("spotify", "youtube", "netflix", "music", "photos", "gallery", "camera", "podcast",
                    "twitch", "soundcloud", "audible", "primevideo", "disney", "hulu", "vlc", "player").any { p.contains(it) } -> MEDIA
                listOf("gmail", "outlook", "docs", "sheets", "slides", "drive", "notion", "slack", "teams",
                    "zoom", "calendar", "office", "keep", "todo", "trello", "asana", "evernote", "obsidian",
                    "anthropic", "claude", "github").any { p.contains(it) } -> WORK
                listOf("maps", "uber", "lyft", "airbnb", "booking", "waze", "transit", "citymapper", "trip").any { p.contains(it) } -> TRAVEL
                listOf("settings", "calculator", "clock", "files", "documentsui", "contacts", "dialer",
                    "phone", "vending", "authenticator", "vpn", "wallet", "bank", "pay", "weather",
                    "recorder", "notes", "chrome", "firefox", "browser", "brave").any { p.contains(it) } -> TOOLS
                listOf("game", "games", "supercell", "king.", "mojang", "roblox", "chess").any { p.contains(it) } -> GAMES
                else -> null
            }
        }
    }
}

data class AppInfo(
    val key: String,
    val label: String,
    val originalLabel: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWork: Boolean,
    val category: AppCategory,
) {
    val packageName: String get() = component.packageName
}

data class Note(val id: Long, val text: String, val created: Long, val pinned: Boolean = false)

data class ContactHit(val name: String, val number: String?)

data class FileHit(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

data class ChatMsg(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val error: Boolean = false,
)
