# Bluff Club

> **Everyone knows. Someone’s pretending.**

An offline-first, pass-the-phone party game for 3–10 players in one room. Everyone
privately sees the same secret word — except the bluffer. Give clues, argue, vote.

V1 has a single mode, **Find the Bluffer**. The free **General** pack is unlimited.
Four premium packs (Food Fight, Office Chaos, SA Game Night, Wild Card) and a fixed
Party Bundle are once-off Google Play purchases through RevenueCat.

Status: **MVP / pre-release.** It is not production ready. See
[`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) for what is
verified and what is still blocked on devices, consoles, or owner decisions.

## Requirements

| Tool | Version used / required | Notes |
| --- | --- | --- |
| Node.js | ≥ 22.13 (built with 22.22.0) | Required by Vite 8, Vitest 5, jsdom 29 and Capacitor 8 |
| pnpm | 10.28.0 (`packageManager` field) | `corepack enable` installs it |
| Java (JDK) | 21 | Capacitor 8 Android and the RevenueCat plugin compile with Java 21 |
| Android SDK | Platform 36, Build-Tools 36.0.0 | compile/target SDK 36, min SDK 24 |
| Android Studio | Otter 2025.2.1 or newer | Capacitor 8 requirement |
| Gradle | 8.14.3 (wrapper) | Downloaded by `./gradlew` |

Exact JavaScript dependency versions are pinned in `package.json` and `pnpm-lock.yaml`.
[`docs/SOURCES.md`](docs/SOURCES.md) lists them and the docs they were checked against.

## Quick start: a complete free game, no credentials needed

```bash
cd bluff-club
pnpm install
pnpm dev            # http://localhost:5173 (purchases show as unavailable)
pnpm dev --mode demo  # same, plus the clearly labelled demo store ("Demo purchase — no charge")
```

Open it in a phone-sized browser window. Tap **Play** and then **Start game**, and pass the
"phone" around.

## Scripts

| Script | What it does |
| --- | --- |
| `pnpm dev` | Vite dev server |
| `pnpm build` | Refuses mock billing, typechecks, then builds production to `dist/` |
| `pnpm build:demo` | Demo-mode build with the mock store, for local demos only |
| `pnpm typecheck` | `tsc` on app and node/tooling configs |
| `pnpm lint` | Biome lint + format check (`pnpm lint:fix` to apply) |
| `pnpm test` | Vitest unit and component tests |
| `pnpm test:e2e` | Playwright: demo-mode suite and production-build suite |
| `pnpm content:validate` | Pack schema, counts, IDs, duplicates and lengths. Also flags near-duplicates for an editor |
| `pnpm android:assets` | Renders `assets/*.svg` into launcher icons and splash PNGs |
| `pnpm android:sync` | Production build, then `cap sync android` |
| `pnpm android:open` | Opens the Android project in Android Studio |
| `pnpm android:debug-apk` | Sync, then `./gradlew assembleDebug` (debug key only) |
| `pnpm release:check` | Fails on machine-detectable release blockers. Lists owner attestations and device tests |
| `pnpm screenshots` | Captures real UI screenshots from the production build into `docs/screenshots/` |

Playwright uses Chromium. In this environment it is pre-installed. Elsewhere, run
`pnpm exec playwright install chromium` once.

## Configuration

Copy `.env.example` to `.env.local` and fill in **public** values only:

- `VITE_REVENUECAT_ANDROID_API_KEY`: the RevenueCat **public** Android SDK key (`goog_…`)
- `VITE_SUPPORT_EMAIL`: support route shown in Settings
- `VITE_PRIVACY_POLICY_URL`: the hosted privacy policy (https)

Everything prefixed `VITE_` ships inside the app. Never put RevenueCat secret keys,
Google service-account JSON, keystores or passwords in `.env` files or in git.

Branding, the proposed application ID (`com.zenfracta.bluffclub`), product IDs and
entitlements are centralised in `src/app/config.ts`, `capacitor.config.ts` and
`src/services/billing/catalog.ts`.

## Android

```bash
pnpm android:sync
cd android && ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease                   # unsigned unless android/keystore.properties exists
```

A debug APK is signed with the local debug key. That is **not** a production signature.
For release signing, Play App Signing and the store steps, see
[`docs/PLAY_STORE_RELEASE_CHECKLIST.md`](docs/PLAY_STORE_RELEASE_CHECKLIST.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md): state machine, adapters, persistence
- [Decisions](docs/DECISIONS.md): assumptions and trade-offs
- [Implementation status](docs/IMPLEMENTATION_STATUS.md): what is implemented, tested, device-tested or blocked
- [Content guide](docs/CONTENT_GUIDE.md): writing, validating and playtesting packs
- [Billing setup](docs/BILLING_SETUP.md): product/entitlement mapping and console steps
- [Play Store release checklist](docs/PLAY_STORE_RELEASE_CHECKLIST.md)
- [Android manual test checklist](docs/ANDROID_TEST_CHECKLIST.md)
- [Privacy policy draft](docs/PRIVACY_POLICY_DRAFT.md) and [Data safety notes](docs/DATA_SAFETY_NOTES.md)
- [Playtest plan](docs/PLAYTEST_PLAN.md)
- [Store listing draft](docs/store/STORE_LISTING_DRAFT.md)
- [Backlog](docs/BACKLOG.md)
- [Sources and versions](docs/SOURCES.md)
