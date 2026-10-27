package com.axiom.launcher.solis

import com.axiom.launcher.data.CalEvent
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a suggestion card does when its button is tapped. Nothing runs until then. */
sealed interface CardAction {
    data class Navigate(val place: String) : CardAction
    data class OpenUrl(val url: String) : CardAction
    data class Settings(val panel: String) : CardAction
    data class Ask(val prompt: String) : CardAction
    data object PlayMedia : CardAction
}

data class ProactiveCard(val id: String, val title: String, val detail: String, val actionLabel: String, val action: CardAction)

/** A snapshot of the phone the rules look at; built on the device, never sent anywhere. */
data class ProactiveInput(
    val now: Long,
    val hour: Int,
    val day: String,
    val device: DeviceSnapshot,
    val events: List<CalEvent>,
    val headphones: Boolean,
    val musicActive: Boolean,
    val storageFreePct: Int?,
    /** A provider is set up and the active mode allows AI. */
    val aiReady: Boolean,
    val briefToday: Boolean,
    val clock24: Boolean = true,
)

/**
 * Home-screen suggestions from simple, local rules (no model call, nothing leaves the phone): leave for an
 * event with a place, join a call that's starting, low battery, headphones just connected, storage almost
 * full, and a morning brief on request. Each card can be dismissed for the day.
 */
object ProactiveRules {
    private val MEETING = Regex("https://(?:[\\w-]+\\.)?(?:meet\\.google\\.com|zoom\\.us/j|teams\\.microsoft\\.com|teams\\.live\\.com|[\\w-]+\\.webex\\.com)/\\S+", RegexOption.IGNORE_CASE)

    fun cards(i: ProactiveInput, dismissed: Set<String>, max: Int = 2): List<ProactiveCard> {
        val out = ArrayList<ProactiveCard>()
        fun add(c: ProactiveCard) { if (c.id !in dismissed && out.none { it.id == c.id }) out += c }
        val timeFmt = SimpleDateFormat(if (i.clock24) "HH:mm" else "h:mm a", Locale.getDefault())

        for (e in i.events.filter { !it.allDay }.sortedBy { it.begin }) {
            val mins = (e.begin - i.now) / 60_000
            val link = MEETING.find(e.location.orEmpty())?.value ?: MEETING.find(e.title)?.value
            if (link != null && mins in -5..10) {
                add(ProactiveCard("join:${e.begin}", e.title.ifBlank { "Meeting" },
                    if (mins > 0) "Starts in $mins min" else if (mins == 0L) "Starting now" else "Started ${-mins} min ago", "Join", CardAction.OpenUrl(link)))
            } else if (link == null && !e.location.isNullOrBlank() && mins in 15..60) {
                add(ProactiveCard("leave:${e.begin}", "Leave soon for ${e.title.ifBlank { "your event" }}",
                    "${e.location.trim().take(60)} · ${timeFmt.format(Date(e.begin))}", "Directions", CardAction.Navigate(e.location.trim())))
            }
        }

        val d = i.device
        if (d.batteryPct in 1..20 && !d.charging && !d.powerSave) {
            add(ProactiveCard("battery:${i.day}", "Battery at ${d.batteryPct}%", "Battery saver makes it last longer; Solis already slows its background work.",
                "Battery saver", CardAction.Settings("battery saver")))
        }
        if (i.headphones && !i.musicActive) {
            add(ProactiveCard("headphones:${i.day}", "Headphones connected", "Pick up where your music left off?", "Play", CardAction.PlayMedia))
        }
        i.storageFreePct?.let { free ->
            if (free < 8) add(ProactiveCard("storage:${i.day}", "Storage almost full", "$free% free. Solis can find what's taking the space.",
                if (i.aiReady) "Ask Solis" else "Storage", if (i.aiReady) CardAction.Ask("What's taking up my storage? Suggest what I could safely clear.") else CardAction.Settings("storage")))
        }
        if (i.aiReady && !i.briefToday && i.hour in 6..10) {
            add(ProactiveCard("brief:${i.day}", "Morning brief", "Agenda, notifications and what to focus on, when you want it.", "Brief me", CardAction.Ask(com.axiom.launcher.BRIEF_PROMPT)))
        }
        return out.take(max)
    }
}
