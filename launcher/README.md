# Dcode — AI-first Android launcher

A minimalist home screen with Claude built in. Three themes:

- **Paper** — white canvas, black ink
- **Void** — true OLED black, white light
- **Netrunner** — cyberpunk terminal: neon-on-black, digital rain, scanlines, glitching clock, `./app_names`, boot sequence when you jack in

## Highlights

- **Omnibox** (tap the bar): fuzzy app search (initials work: `gm` → Google Maps), contacts, files, settings, notes, inline calculator, and instant offline commands — `timer 10 min`, `alarm 6:45am`, `call mom`, `text alex: running late`, `note …`, `navigate to …`, `play …`, `pause`/`next`, `volume 40`, `focus 45`, `theme netrunner`, `brief`, `digest`, `neofetch`, `matrix`.
- **Claude, two engines**
  - *Claude app* — questions hand off to the official Claude app, so they run on your Claude subscription.
  - *Built-in agent* — add an Anthropic API key and Claude runs inside the launcher with ~35 phone tools: open apps, search/read files, timers, alarms, calendar, contacts, SMS/email drafts, maps, media, volume, clipboard, notes, long-term memory, notification digest, theme and focus control, plus web search. Streaming replies, voice in, optional spoken replies, attach images/PDFs/text from the Files page.
- **Glance**: next calendar event, notification count → one-tap AI digest, tap the greeting for a daily brief.
- **Predictive row**: apps you usually open at this hour.
- **Focus mode**: pauses distracting apps for N minutes.
- **Files page** (swipe right): browse, search everything, open/share/delete, "Ask Claude about this file".
- Gestures: swipe up = apps, down = notifications, left/right = Claude/Files, long-press = settings, double-tap = lock (optional accessibility service). Hold Home (assist) opens Claude.

API keys are encrypted with the Android Keystore and only sent to `api.anthropic.com`.

## Build

```
cd launcher
./gradlew :app:assembleRelease      # needs Android SDK 36; set sdk.dir in local.properties
./gradlew :app:testDebugUnitTest    # agent loop test + screenshot renders in app/build/screens
```

Requires Android 11+. Prebuilt APK: [`releases/Dcode-1.0.0.apk`](../releases/Dcode-1.0.0.apk).
