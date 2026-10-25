# Implementation status

As of 2026-10-25. **This is an MVP for playtesting, not a production-ready release.**

Legend:

- **Implemented**: code exists.
- **Auto-tested**: covered by Vitest or Playwright in a browser.
- **Device-tested**: run on a physical Android device.
- **Blocked**: needs something external (device, console, credentials or owner).

No area has been device-tested. The build environment had no Android device or
emulator (no KVM) and no Play Console or RevenueCat access.

| Area | Implemented | Auto-tested | Device-tested | Blocked on |
| --- | --- | --- | --- | --- |
| Domain engine (roles, decks, voting, runoff, scoring, recovery, snapshots) | ✅ | ✅ unit (57 domain/content tests) | — | — |
| Unbiased crypto randomness, deterministic test sources | ✅ | ✅ | — | — |
| Setup: roster 3–10, Unicode names, validation, pack, timer | ✅ | ✅ unit + e2e | ☐ | device |
| Private reveal (hold, tap with 5 s auto-cover, keyboard) | ✅ | ✅ component + e2e | ☐ | device |
| Secrets unmounted when covered; cover on cancel/blur/visibility/background | ✅ | ✅ component + e2e | ☐ | device |
| Discussion timer (elapsed time, pause/resume, no auto-advance) | ✅ | ✅ unit + component (controlled clock) + e2e | ☐ | device |
| Private voting, no self votes, double-tap safety, runoff, repeated tie | ✅ | ✅ unit + e2e | ☐ | device |
| Results, public tallies only, idempotent scoring, play again | ✅ | ✅ unit + e2e | ☐ | device |
| Persistence (versioned envelopes, serial writes, recovery screen, corrupt-state handling) | ✅ | ✅ unit + e2e (reload) | ☐ process death on Android | device |
| Android back handling | ✅ | ✅ unit (mocked native events) | ☐ | device |
| FLAG_SECURE / Recents protection (PrivacyScreenPlugin.java) | ✅ compiles into APK | ⚠ JS calls tested with fakes only | ☐ | device |
| WebView text zoom follows system font scale | ✅ | ⚠ browser 200% CSS test only | ☐ | device |
| Settings, tutorial, reset, privacy text, version | ✅ | ✅ e2e | ☐ | — |
| Packs/paywall UI, states (loading, offline, unavailable, error, owned, pending) | ✅ | ✅ e2e with mock store | ☐ | — |
| RevenueCat adapter | ✅ (documented SDK API only) | ✅ unit with the SDK module mocked | ☐ | Play Console products, RevenueCat project, public key, licence testers |
| Real purchases, restore, refunds, bank verification, offline cached ownership | — | — | ☐ | ⛔ external |
| Mock store excluded from production | ✅ | ✅ build guard + prod e2e + `release:check` scan | n/a | — |
| Content: 100 + 4×60 original entries, validator | ✅ | ✅ | n/a | ⛔ human editorial review |
| Local metrics + consented export | ✅ | ✅ unit (no PII) | n/a | — |
| Layout: 360 px, 200% text, 48 px targets, focus ring, reduced motion | ✅ | ✅ e2e (Chromium, Pixel 7 profile) | ☐ | device |
| Icons, splash, store icon | ✅ generated from original SVG | visual check | ☐ | — |
| Android debug APK / unsigned release AAB | ✅ built | n/a | ☐ install | — |
| Signed release | config only | — | — | ⛔ owner upload key |
| Store listing, privacy policy, data safety | drafts | — | — | ⛔ owner/legal review, hosted URL, support email |

## Commands executed in this environment (final run)

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | ✅ |
| `pnpm lint` | ✅ 0 errors |
| `pnpm typecheck` | ✅ |
| `pnpm test` | ✅ 116 tests in 9 files |
| `pnpm test:e2e` | ✅ 23 tests (demo project + production-build project) |
| `pnpm content:validate` | ✅ (6 near-duplicate review flags, not failures) |
| `pnpm build` | ✅ |
| `pnpm release:check` | ❌ **expected**: 3 blockers (RevenueCat public key, support email, privacy URL are not configured). All other static checks pass |
| `pnpm android:sync` and `./gradlew assembleDebug bundleRelease assembleRelease` | ✅ debug APK, unsigned release AAB and APK |

## Artifacts produced (not committed: build outputs are git-ignored)

- `android/app/build/outputs/apk/debug/app-debug.apk`: debug key, **not** a production signature
- `android/app/build/outputs/bundle/release/app-release.aab`: **unsigned**
- `android/app/build/outputs/apk/release/app-release-unsigned.apk`
- `dist/`: production web bundle

Committed: `docs/screenshots/*.png` (real UI, synthetic names) and `docs/store/icon-512.png`.

## Known limitations

- No on-device verification of any kind yet: back gestures, process death, FLAG_SECURE,
  TalkBack, text zoom, haptics, billing.
- Offline access to paid packs depends on the RevenueCat SDK cache. After a reinstall
  while offline, paid packs stay locked until the user is online and restores.
- Bundled content is not DRM. Rooted or modified devices can access locked words.
- `FLAG_SECURE` cannot stop someone photographing the screen.
- Screen readers will read a revealed card aloud. The app warns about this but cannot
  prevent it.
- Store screenshots come from the browser build, not a device.
- No crash reporting, by design. Field issues will only arrive via support email.
- The repository-creation request could not be fulfilled from this session (GitHub
  integration 403). The project lives in `bluff-club/` (see DECISIONS #1).
