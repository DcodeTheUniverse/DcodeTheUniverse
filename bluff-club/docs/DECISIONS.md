# Decisions and assumptions

Recorded 2026-10-25. Owner review is welcome on any of these.

## Repository

1. **The project lives in `bluff-club/` inside `DcodeTheUniverse/DcodeTheUniverse`.**
   The request asked for a new repository. Repository creation through this session's
   GitHub integration returned `403 Resource not accessible by integration`, so the
   work went on the assigned branch instead. Because the host repo is a GitHub profile
   README, everything sits in a self-contained subfolder. To move it into its own
   repository with history:
   `git subtree split --prefix bluff-club -b bluff-club-only`, then push that branch
   to the new repo.

## Stack and versions

2. Versions were chosen from npm on 2026-10-25. Where possible I picked stable
   releases at least about two weeks old, and checked them against each other's peer
   dependencies. TypeScript is **6.0.3** rather than 7.x. TS 7 is the native port;
   6.x is the conservative choice for the current tooling (vite-plugin-solid,
   Vitest). See `SOURCES.md`.
3. Vite 8, Vitest 5, Tailwind 4 (CSS-first `@theme`) and Biome 2.5 are used.
   No router: a typed navigation controller handles six screens.
4. `@playwright/test` is pinned to **1.63.0**, matching the Chromium build
   pre-installed in the development container.
5. Android assets come from a small Playwright-based script, not
   `@capacitor/assets`. That package pulls an old Capacitor CLI (v5) and `sharp`
   0.32, which has an install script.

## Gameplay

6. **Name length limit: 20 grapheme clusters.** Emoji and combined characters count
   as one, and the input hard-caps at 48 code units. Names are trimmed, internal
   whitespace is collapsed, and they are NFC-normalised. Duplicates are compared with
   NFKC plus locale-independent lower-casing.
7. **Renaming players keeps the score session. Adding or removing players starts a
   new one** (after confirmation, if a round has been scored). Player IDs are stable
   across renames.
8. **Reveal order follows roster order.** The starting clue-giver is random and
   independent of the bluffer.
9. "Done — pass the phone" stays disabled until the player has revealed the card at
   least once and it is covered again. This prevents accidentally skipping a player.
10. The runoff's 20-second discussion is opt-in: the host taps "Start 20-second
    discussion". "Start runoff vote" is available at any time, because the timer
    never forces the vote.
11. Any background or visibility interruption pauses the round, including on
    handover screens. The resume screen reminds players to check who is holding the
    phone. A plain window `blur` only covers the card.
12. During voting, a candidate who is also a voter sees only the other candidates.
    With two tied players, each of them has exactly one choice. This follows "self
    voting remains prohibited".
13. Results show public totals for the initial ballot and, if needed, the runoff.
    Who voted for whom is never displayed.

## Content

14. All 340 entries were written for this project (AI-assisted drafting, 2026-10-25)
    and still need human editorial review. Preview words shown in the store are real
    entries from each pack, so three words per pack are known in advance; that is the
    price of honest previews.
15. Entries carry `id`, `packId`, `word`, `difficulty`, and optional `tags` (used
    only for SA slang). IDs are stable and must never be reused.

## Monetisation and billing

16. RevenueCat entitlement identifiers equal the suggested names (`pack_food_fight` …).
    The app trusts only entitlements it knows, so a bundle can never mean "all future
    packs".
17. The bundle is hidden as soon as any of its packs is owned. There is no prorated
    upgrade.
18. Anonymous RevenueCat App User IDs; no login. Restores depend on the Play account
    and the project's transfer behaviour (see `BILLING_SETUP.md`).
19. No ownership is persisted by the app. Offline access to paid packs relies on the
    RevenueCat SDK's own CustomerInfo cache (documented in its caching docs). If the
    SDK has no cache, for example on first launch after a reinstall while offline,
    paid packs stay locked until a restore or refresh succeeds. This is deliberate:
    there is no authoritative local "premium" flag.
20. Mock billing exists only for development and tests. Every surface it touches says
    "Demo purchase — no charge". It is compiled out of production builds.

## Platform

21. `MainActivity` uses `launchMode="singleTop"`. The Capacitor template's
    `singleTask` can cancel purchases that hand off to a banking app (RevenueCat docs).
22. The WebView text zoom follows the Android font scale (`WebSettings.setTextZoom`),
    because WebView ignores system font size by default. Layout is tested at 200% in
    the browser; this has not been tested on a device yet.
23. `FLAG_SECURE` is on for the whole active round, including neutral handover and
    discussion screens. This keeps Recents previews and screenshots safe. It is off
    on home, setup, packs, settings and results.
24. Auto Backup stays enabled (`allowBackup="true"`). RevenueCat recommends backing up
    its SharedPreferences for restore robustness. Game data (settings, roster, scores)
    may also be included in the user's Google backup. This is disclosed in the privacy
    draft and needs owner review.
25. `minifyEnabled` stays `false` for release builds (Capacitor default). R8 rules for
    the plugins have not been validated on a device.
26. Sound is on by default and uses synthesized tones only. The same sounds play for
    every role, and nothing depends on hearing them.
27. The ProGuard/R8 and signing configuration supports Play App Signing with an upload
    key kept outside git (`android/keystore.properties`).

## Measurement

28. Only aggregate local counters are kept, with no event log and no IDs. Export
    copies JSON to the clipboard, and only when a playtester taps it.
