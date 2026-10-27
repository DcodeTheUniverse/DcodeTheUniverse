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

**Ask about this screen.** Pick Axiom as the phone's digital assistant (Settings › Apps › Default apps) and
hold Home in any app: Android hands Axiom the screen's text and, if you allow it, a screenshot. Solis opens
with both attached and quick questions (what's this, summarize, translate, draft a reply); nothing is sent
until you ask. Password fields, card numbers and one-time codes are skipped; apps that block capture (banking,
`FLAG_SECURE`) hand over nothing. An assistant must also offer speech recognition to other apps, so Axiom
passes those requests to the phone's on-device recognizer (or another installed one); the self-test warns if
that can't work.

**Voice mode.** A hands-free conversation: Solis listens (on-device recognition when the phone has it),
answers aloud in a shortened spoken version, then listens again, until you say "stop" or tap End. The face has
a speaking expression; tap it to interrupt.

**Reading links.** Solis can read public web pages you give it or find (`read_web_page`): title and main text
only, no scripts. It reaches the public internet only: every hop, redirects included, resolves through a
filter that drops loopback, private, link-local, carrier-NAT and multicast addresses (also when embedded in
IPv6), so a page can't make Solis probe your router or the phone itself. Page text is fenced as data.

**Smarter memory.** Memories are tagged by topic with an on-device lexicon (allergic, vegan → diet; sister →
family …), and Solis adds its own tags (synonyms, categories) when it remembers, so "what can't I eat?" finds
"allergic to peanuts". A weekly tidy-up, also one tap in the Memory inspector, merges near-duplicates (keeping
the newest wording, highest confidence and every tag; pinned stays pinned), clears old notes of finished tasks
and tags older memories. No model call, no embeddings service.

**Half-price batches.** For questions that can wait, the "Half-price batch" chip sends the message through
Anthropic's Message Batches API at half the usual price; the answer arrives as a normal task (notification
included), usually within the hour and always within 24 h, and can be followed up live. A batch answer is one
reply with no tools, so it suits writing, explaining and planning. Needs an Anthropic API key; modes that
require zero retention or local AI refuse it; spending caps apply before sending.

**Second opinions.** A task can be marked independent of a model maker; the router then uses another vendor
family whenever one is eligible (and says so when none is). Coding uses it for reviews (below).

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
- **Failing checks:** "Ask Claude to fix" posts the failures on the pull request (check names, GitHub's
  summaries and first annotations) with `@claude`, and follows Claude's new run on the PR; old failures don't
  count as its answer. Solis can do the same with `code_fix_checks`.
- **Changes:** a diff viewer with every file and patch (added and removed lines keep their +/− signs).
- **Second opinion:** before merging, a model from another maker than the one that wrote the code reviews the
  pull request in a Solis thread and ends with a verdict. It posts nothing to GitHub.
- Settings › Coding: a fine-grained GitHub token (Keystore-encrypted, sent only to api.github.com), the
  default repository, a repository per mode (Work → the company repo), a setup check (token, repository,
  workflow, secret) and one-tap workflow install. Running `/install-github-app` in Claude Code on a computer
  walks through the same setup.
- If Android stops Axiom while Claude works, a WorkManager job (every ~15 min, only with a network) keeps
  checking, so finished work and failed checks still reach you.

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
- **Widgets**: any app's Android widgets in the home screen's free space (personal and work profiles, never
  Private space), with the system's permission and setup screens. Long-press a widget to resize (S–XL), move,
  open its settings or remove it; long-press empty space for Add a widget, Change mode or Settings. Widgets
  only update while the launcher is visible.
- **Suggestions**: cards from simple on-device rules: leave for an event that has a place, join a call that's
  starting (Meet, Zoom, Teams, Webex links), low battery, headphones connected, storage almost full, and a
  morning brief on request. Nothing runs until you tap; each card can be dismissed for the day.
- **Notification badges** next to apps (needs notification access; hidden for apps Focus blocks).
- **Icon packs** in the common ADW/Nova format: exact activity matches, then the app's other icon, then the
  pack's backdrop/mask for apps it doesn't cover; work-profile badges kept.
- **Your prompts**: save a draft as a quick prompt on the Solis page (also in the `>` palette). **Snap & ask**:
  take a photo from the Solis page and ask about it (photos are turned upright before sending).
- **Backup**: export layout, modes, notes and settings to a JSON file and import it here or on another phone.
  API keys, the GitHub token, Solis memory and widget placements aren't included. Imports are validated before
  anything is written; spending caps, models, the local runtime address, routines and sharing rules come along
  only if you switch that on, and notes are merged rather than replaced.
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

- **Hide personal details** (Settings › Privacy, off by default): emails, phone numbers, card numbers (Luhn
  checked) and IBANs (checksum checked) are sent to cloud models as placeholders like `⟦EMAIL_1⟧` and filled
  back in on the phone, in replies and in tool arguments, so drafts and calls still use the real values.
  Numbering is stable across turns, so prompt caching keeps working. Local runtimes see everything; images and
  files go as they are.
- **What was sent**: every task has receipts of its model calls (provider, model, what the call carried, what
  was hidden, tokens and cost) and the exact text of its latest request, kept in memory only.
- **Lock Solis**: fingerprint, face or screen lock before Solis, memory, tasks or settings open; it locks again
  when the screen turns off or after five minutes away. Home and apps stay as they are, and task results leave
  the home screen and search while locked.
- **Diagnostics**: Settings › Diagnostics runs a self-test (launcher and assistant roles, storage, each AI
  provider by listing its models, which costs nothing, GitHub setup, permissions, background limits, last
  crash) with one-tap fixes, and shares a report with performance numbers, Android's own records of recent
  exits and ANRs, and crashes kept on the phone. The report never contains keys, memories, conversations,
  notes, notifications or contacts.

## Performance

Kotlin throughout. JVM micro-benchmarks of the hot paths (`PerfBenchTest`, sized like a heavy install) show
no case for Rust/Go/C: routing over a 470-model catalog ≈ 0.1 ms, memory search over 5,000 items ≈ 1–1.4 ms
(synthetic worst case), local assessment ≈ 30 µs, parsing a 760 KB catalog ≈ 30 ms on a background thread.
These are desktop-JVM proxies, not phone measurements. Inference, storage and indexing run off the UI thread;
scheduling is event-driven; memory and transcripts are bounded. Settings › Diagnostics shows what is measured
on the phone itself: cold start to first frame, frame-time percentiles and jank, memory, battery drain while
unplugged, and Solis submit→first-token / submit→done latency.

## Build & test

CI (`.github/workflows/axiom.yml`) runs the unit and Robolectric tests and builds the release APK on every
push and pull request that touches `launcher/`, and keeps the test reports, screenshots and APK as artifacts.

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
(mode switching, palette, commands, asking Solis) and screenshots of every screen. Added in 2.2: hiding
personal details end to end through the engine (the provider sees placeholders, tools and the transcript get
real values, receipts count what was hidden), second-opinion routing, imported batch answers that take
follow-ups, the Message Batches calls against a local stand-in (results, errors, expiry, the key never sent to
another host), the web reader against a local server (main-content extraction, redirects to private addresses
refused, IPv4/IPv6 address classes), topic search and memory tidy-up, suggestion rules, "ask Claude to fix"
against the in-memory GitHub (failures and annotations on the PR, following Claude's run there), settings
export/import (validation, clamping, the Solis-settings opt-in, merged notes), icon-pack filters, screen text
without passwords or codes, widget placement, the lock (including re-locking on screen off), the self-test
report leaving private content out, assistant and recognizer registration, and screenshots of the new screens.
Not covered by tests: real widgets from other apps, the assistant gesture with real AssistStructure data,
speech recognition, the biometric prompt and live Anthropic batches; those need a phone.

Requires Android 11+. Prebuilt APK: [`releases/Axiom-2.2.0.apk`](../releases/Axiom-2.2.0.apk).
