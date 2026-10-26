# Axiom — an AI-first Android launcher, with Solis

Axiom is a minimalist home screen with **Solis**, an on-device AI brain that plans tasks, routes each one
to a suitable model, works on the phone through tools, and keeps going in the background. Basic launcher
features never need AI or a network connection.

Three themes: **Paper** (white, black ink) · **Void** (true OLED black) · **Netrunner** (neon terminal,
digital rain, scanlines, glitching clock, boot sequence).

## Solis

**Face.** A dot-matrix face whose expression is derived from real engine state: ready, listening (only while
voice capture is open), thinking, working, paused, offline and error. Each state has a distinct still frame,
so reduced motion, battery saver and thermal pressure stop the animation without hiding the state. Screen
readers get "Solis" plus the state.

**Brain (Solis_Kai, native).**

- *Long-term memory*: preferences, facts and decisions with source, date, scope, confidence and caveats.
  BM25 retrieval injects only task-relevant items, fenced as evidence, never as instructions. Personal, work
  and per-task memory are separate; cross-space reading is an explicit switch.
- *Short-term memory*: threads, plans and checkpoints (assessed → planned → each step → combined → verified →
  done) in a SQLite database, so work survives process death, reboots and model changes. Side-effecting tool
  calls are idempotency-keyed: resuming never sets the same alarm twice.
- *Cortex*: a versioned, editable decision matrix — **T0** device commands and quick lookups with no model;
  **T1** routine work on one efficient model; **T2** planning and parallel specialist steps; **T3** stronger
  reasoning plus independent verification by a different model family. It weighs ambiguity, risk, sensitive
  domains, tools and modalities, context size, deadlines, budget, privacy and device conditions.
- *Routing*: hard constraints first (privacy, capabilities, context, price caps, retiring models), then
  measured quality, latency, reliability and cost. Reasoning effort is set only where a model advertises it.
  Fallback to another model/family on outages and refusals; retries and escalations stay within limits.
- *Adaptive policy*: learned ranking adjustments need minimum evidence (Wilson bounds), run as a trial on a
  share of tasks, are promoted only if they do no worse, and roll back otherwise. Full history with manual
  rollback. Learning can only reorder models that already pass every constraint.
- *Budget*: daily, monthly, per-mode and per-task caps, enforced by reserving before each call. Planning,
  background updates, retries and verification all count.

**Providers.** OpenRouter (first-class: model discovery with prices/capabilities/benchmarks cached with
freshness, streaming, tool calls, reasoning, `models` fallbacks, data-collection/ZDR preferences, web
plugin, key status), Anthropic direct, and any OpenAI-compatible local runtime (Ollama, llama.cpp, LM Studio).
Nothing about models is hardcoded; pick a model per role (coordinator, worker, verifier, combiner,
background updates) or per task, or leave it on Auto. Provider formats never leave their adapter.

**Threads.** Each request is an independent agent thread with its own goal, transcript, checkpoints,
permissions, budget, model assignments and lifecycle. Concurrency limits (lowered on low battery, heat or
metered data), bounded queues, dependencies, pause/resume/cancel, resource leases between threads, and a
task overview with models, usage and controls. A separate cheap model writes status lines only for
multi-step work, triggered by real events, coalesced and capped; it never starts work.

## Launcher

- **Omnibox** with a `>` **command palette**: apps (fuzzy, usage-aware), contacts, files, settings, notes,
  Solis tasks and memory, calculator, and on-device commands (`timer 10 min`, `alarm 6:45`, `call mom`,
  `text alex: running late`, `navigate to …`, `focus 45`, `work mode` …). Compound requests ("wake me at 6:30
  and remind me to pack") go to Solis instead of silently running half.
- **Modes**: Personal, Work, Focus, Travel (and your own) — home apps, shortcuts, notification presentation,
  work-app placement, theme, AI privacy (cloud / zero-retention / local-only / off), daily AI budget, data
  saver and a home-time clock. Temporary modes ("Focus for 25 min"). Modes are presets; they never create
  Android profiles or grant permissions.
- **Work profile & Private space** via `LauncherApps`/`UserManager`: profile-aware discovery, launching and
  badges, paused states with a one-tap (policy-respecting) resume, Private space only while unlocked and
  never visible to Solis. Personal tasks never see work apps or work notifications.
- **Home**: resumable task cards, routine prompts, next event, notifications per mode, predicted apps.
- **Routines**: at a time on chosen days, switch mode, run a device command, or ask Solis (AI routines need a
  per-routine opt-in, otherwise they wait for your OK on the home screen).
- **Memory inspector**: search, correct, pin, move between spaces, forget, export/import JSON.
- **Offline mode**: only local models and device actions; tasks that need the cloud wait and resume.
- Files page, focus blocking, gestures, voice in, spoken replies, and the Claude-app hand-off for people who
  prefer their Claude subscription.

## Privacy & security

API keys are encrypted with an Android Keystore key, cached only in process memory, never backed up, and sent
only to their provider. Solis's database moves with device-to-device transfer but stays out of cloud backups
(work memory must not leave through the personal account); use Memory › Export for your own backup.
Cleartext HTTP is allowed only so a local runtime on your LAN works, and the UI warns when it is used.

## Performance

Kotlin throughout. JVM micro-benchmarks of the hot paths (`PerfBenchTest`, sized like a heavy install) show
no case for Rust/Go/C: routing over a 470-model catalog ≈ 0.1 ms, memory search over 5,000 items ≈ 1–1.4 ms
(synthetic worst case), local assessment ≈ 30 µs, parsing a 760 KB catalog ≈ 30 ms on a background thread.
These are desktop-JVM proxies, not phone measurements. Inference, storage and indexing run off the UI thread;
scheduling is event-driven; memory and transcripts are bounded. Settings › Diagnostics shows what is measured
on the phone itself: cold start to first frame, frame-time percentiles and jank, memory, battery drain while
unplugged, and Solis submit→first-token / submit→done latency.

## Build & test

```
cd launcher
./gradlew :app:assembleRelease      # Android SDK 36; set sdk.dir in local.properties
./gradlew :app:testDebugUnitTest    # brain + Robolectric app tests; screenshots in app/build/screens
```

The release key lives outside git (`keystore/axiom-release.jks` or `AXIOM_KEYSTORE`); without it, release
builds use the debug key.

What the tests cover — all simulated (scripted providers, Robolectric), not physical devices or live models:
routing and constraint precedence, model fallback, coordinator plans with parallel steps, independent
verification and escalation, thread isolation, concurrency and queue limits, pause/resume/cancel,
checkpoint recovery after simulated process death on the real SQLite store (no repeated side effects),
budget caps, narrator coalescing, offline waits, memory space boundaries, profile and notification
boundaries, mode-driven privacy/budget, routines and consent, T0 commands without inference, the OpenRouter
adapter against a mock server (SSE, tool-call deltas, mid-stream errors, cancellation), UI flows (mode
switching, palette, commands, asking Solis) and screenshots of every screen.

Requires Android 11+. Prebuilt APK: [`releases/Axiom-2.0.0.apk`](../releases/Axiom-2.0.0.apk).
