# Axiom — an AI-first Android launcher, with Solis

Axiom is a minimalist home screen with **Solis**, an on-device AI brain that plans tasks, routes each one
to a suitable model, works on the phone through tools, and keeps going in the background. Basic launcher
features never need AI or a network connection.

Three themes: **Paper** (white, black ink) · **Void** (true OLED black) · **Netrunner** (neon terminal,
digital rain, scanlines, glitching clock, boot sequence).

## Solis

**Face.** A 3D dot-matrix head: each dot is a small lit sphere set into a curved surface, seen in
perspective; lit dots stand out of it with a glow (dark themes) or a cast shadow (Paper), and the head turns
with its state — up and aside while thinking, leaning in while listening, down when paused, tilted on an
error. Drag it to turn it; tap it on the Solis page to open the brain view. The expression is derived from
real engine state (ready, listening only while voice capture is open, thinking, working, paused, offline,
error). Every state has a distinct still pose and expression, so reduced motion, battery saver and thermal
pressure stop the animation without hiding the state; idle only wakes to blink or glance now and then.
Dots are pre-rendered sphere sprites, so a frame is ~100 bitmap draws. Screen readers get "Solis" plus the
state.

**Brain view.** Memory as a brain seen from above: personal memory in the left hemisphere, work in the right,
each task's notes in a labelled cluster below, Solis's core in the middle. Shapes mark the kind (fact,
preference, decision, note, task summary), pinned memories are ringed, and links join memories that share
distinctive terms. When Solis recalls or writes memory during a task, pulses travel from the core (or that
task's cluster) to exactly those memories, and an activity feed says what happened; while tasks run, the core
breathes and one satellite orbits per running task. Nothing moves unless something really happened; with
reduced motion the touched memories are ringed instead. Pinch to zoom, tap a node to read, pin or open it,
type to highlight. The layout is deterministic and needs no simulation (sunflower spirals per hemisphere),
capped at the 360 most important memories.

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

**Prompt caching.** Worker calls mark the stable prefix (frozen system prompt + tools) and the latest request
as cacheable: explicit `cache_control` breakpoints for Claude models through OpenRouter, and a system breakpoint
plus automatic caching on the Anthropic API. Tool loops and back-to-back tasks re-read that prefix at a
fraction of the input price; one-off planner, verifier and narrator calls skip it so they never pay the
cache-write premium. Cache reads and writes are tracked per task and in Budget, and cost estimates use the
catalog's cache prices.
Nothing about models is hardcoded; pick a model per role (coordinator, worker, verifier, combiner,
background updates) or per task, or leave it on Auto. Provider formats never leave their adapter.

**Threads.** Each request is an independent agent thread with its own goal, transcript, checkpoints,
permissions, budget, model assignments and lifecycle. Concurrency limits (lowered on low battery, heat or
metered data), bounded queues, dependencies, pause/resume/cancel, resource leases between threads, and a
task overview with models, usage and controls. A separate cheap model writes status lines only for
multi-step work, triggered by real events, coalesced and capped; it never starts work.

## Coding with Claude Code

Solis can hand coding work in your GitHub repositories to Claude Code, which runs in the repository's GitHub
Actions on your Claude (Pro/Max) subscription via `claude-code-action` and a `CLAUDE_CODE_OAUTH_TOKEN` secret.

- Ask Solis ("add a dark mode toggle to my app") and it writes a self-contained spec and files it as an issue
  that mentions `@claude`; or type `code: fix the login crash` in the omnibox to file it directly with no model
  call at all.
- Axiom follows the job: Claude's progress checklist, the branch it pushes, the diff size, the pull request
  and its checks. Polling runs only while a job is active (30–60 s while Claude works, minutes while a PR
  waits) and stops a day after the last change. Updates land in the Solis thread that started the job, on a
  home-screen card and in the task overview, with notifications when Claude finishes, needs you, checks fail
  or a PR is ready.
- Solis can check status, read the diff to review or explain it, ask Claude for follow-ups or fixes, and open
  the pull request. It cannot merge: it can only ask, and the merge (squash, pinned to the head it just
  checked) happens when you confirm it; drafts, conflicts and failing checks are refused.
- Settings › Coding: a fine-grained GitHub token (Keystore-encrypted, sent only to api.github.com), the
  default repository, a setup check (token, repository, workflow, secret) and one-tap workflow install.
  Running `/install-github-app` in Claude Code on a computer walks through the same setup.

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
- **Share → Ask Solis** from any app (text, links, images, PDFs): the launcher opens on Solis with it attached;
  nothing is sent until you send it. A **Quick Settings tile** asks Solis by voice from anywhere. A **Morning
  brief** routine template (off by default; asks before using AI).
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
adapter against a mock server (SSE, tool-call deltas, mid-stream errors, cancellation), prompt-cache
breakpoints on the wire (OpenRouter body and the Anthropic SDK request), the coding bridge against an
in-memory GitHub over HTTP (issue → Claude working → branch → PR → checks → user-confirmed merge, errors,
stalls, refusals to merge drafts or failing checks, setup check and workflow install, Solis calling the tool),
the brain layout and its events, the 3D face's poses and fit, share and Quick Settings registration, UI flows
(mode switching, palette, commands, asking Solis) and screenshots of every screen.

Requires Android 11+. Prebuilt APK: [`releases/Axiom-2.1.0.apk`](../releases/Axiom-2.1.0.apk).
