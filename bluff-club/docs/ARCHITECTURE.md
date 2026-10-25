# Architecture

```
src/
  domain/            framework-independent rules (no Solid, no Capacitor)
    random.ts        RandomSource, unbiased randomInt, Fisher–Yates shuffle
    game/
      roster.ts      players, name validation (graphemes, case-insensitive dupes)
      deck.ts        per-pack shuffled decks + content-version reconciliation
      timer.ts       elapsed-time timer (timestamps, never tick counting)
      types.ts       Session / Round / Phase discriminated union / actions
      machine.ts     createSession, startRound, transition(), scoring, recovery
      snapshot.ts    runtime validation of persisted sessions
  content/           bundled JSON packs + validation.ts (shared with the CLI script)
  services/
    storage/         KeyValueStore, versioned envelopes, SerialWriter, codecs
    billing/         catalog (product→entitlement), adapters: revenuecat | mock | unavailable
    native/          NativeBridge interface + Capacitor implementation, WebAudio cues
    metrics/         bounded local counters + explicit export
  app/               controller (Solid signals), context, config, App shell
  components/ui/     small design system (Button, Card, Dialog, Toggle, RadioCard…)
  features/          home, setup, game, packs, settings, tutorial screens
android/             Capacitor Android project (+ PrivacyScreenPlugin.java)
scripts/             content validation, release check, assets, screenshots
tests/unit           Vitest + Solid Testing Library
tests/e2e            Playwright (demo project + production-build project)
```

## Navigation versus game phases

Public screens use a small typed navigation controller with a history stack:
`home | setup | game | packs | settings | tutorial`. There is no router, no URL
parameters, and no browser history entries. Gameplay phases live only in the domain
state machine. A URL therefore never contains a word, a role or a vote. The browser
back button cannot reach an earlier private screen, because private screens are not
history entries.

## Round state machine

`Phase` is a discriminated union. `transition(session, action, now)` returns
`{ ok: true, session } | { ok: false, error }`, and it rejects every action that is
invalid for the current phase.

```
handover(i) ──OPEN_CARD──▶ reveal(i) ──FINISH_REVEAL──▶ handover(i+1) … ──▶ clues
clues ──START_DISCUSSION──▶ discussion(timer running)
discussion ──PAUSE_TIMER/RESUME_TIMER──▶ discussion
discussion ──START_VOTING──▶ vote-handover(0, initial ballot)
vote-handover(v) ──OPEN_BALLOT──▶ vote(v) ──CAST_VOTE──▶ vote-handover(v+1) …
last CAST_VOTE:
  single top vote ───────────────▶ results (scored in the same update)
  tie on initial ballot ─────────▶ runoff-intro(candidates, 20 s timer)
runoff-intro ──START_TIMER/PAUSE/RESUME──▶ runoff-intro
runoff-intro ──START_RUNOFF_VOTE──▶ vote-handover(0, runoff ballot)
last runoff CAST_VOTE:
  single top vote ──▶ results       tie again ──▶ results (bluffer wins)
any live phase ──INTERRUPT──▶ paused(resume = public phase) ──RESUME──▶ resume
```

- `paused.resume` is always a **public** phase. `reveal(i)` maps to `handover(i)`,
  `vote(v)` maps to `vote-handover(v)`, and running timers are paused. A pause or a
  recovery can never land directly on a secret.
- Timer expiry is derived state (`isExpired(timer, now)`). It never triggers a
  transition.
- Scoring happens inside the same immutable update that produces `results`.
  `lastScoredRoundId` makes `applyScores` idempotent.
- `abandonRound` clears the round and awards nothing.
- Vote targets are validated: only ballot candidates, never yourself, one vote per
  voter, in roster order.

## Randomness

`RandomSource.nextUint32()` is injected. Production uses `crypto.getRandomValues`
with a 64-value buffer. `randomInt(n)` uses rejection sampling: values at or above
`2^32 − (2^32 mod n)` are discarded, so every outcome is equally likely.
`shuffle` is Fisher–Yates. The bluffer and the starting player are two independent
uniform picks each round. There is no rotation and no seed control in production.

## Decks

`DeckState = { remaining (shuffled queue), drawn (this cycle), lastWordId, contentVersion }`.
A draw takes the head of `remaining`. When `remaining` is empty, the whole pack is
reshuffled. If the head of the new cycle equals `lastWordId`, it is swapped with a
uniformly chosen later position. `reconcileDeck` runs on every draw. It drops IDs no
longer in the pack, deduplicates, and shuffles new entries into the not-yet-drawn
part of the cycle, so content updates never repeat a word early.

## Persistence

- `KeyValueStore`: Capacitor Preferences (SharedPreferences on Android, localStorage
  on the web), or memory in tests.
- Every value is wrapped in an envelope `{ v, savedAt, data }` and decoded by a codec
  with runtime validation. A future-version or invalid envelope yields
  `status: 'invalid'`, never a crash.
- `SerialWriter` runs writes one at a time, in call order. A queued write is skipped
  if a newer write for the same key arrived before it started. A slow, stale save
  therefore cannot overwrite newer state.
- Keys: `bc.settings`, `bc.setup`, `bc.session` (scores and the round in **one**
  snapshot), `bc.decks`, `bc.metrics`. The transient "card visible" state is never
  persisted.
- Resets remove only `bc.*` keys. RevenueCat keeps its own native storage, and the
  demo store uses `demo-store.*`. Neither is touched by resets.
- Writes happen on state transitions only, never per frame or per timer tick.

## Interruption and privacy

| Event | Effect |
| --- | --- |
| pointer up / cancel / leave / lost capture | cover card (hold mode) |
| window `blur` | cover card |
| `visibilitychange` (hidden), `pagehide`, Capacitor `pause`, `appStateChange{isActive:false}` | cover card, then `INTERRUPT` (paused, timer stopped) and flush storage |
| Android back | close modal, otherwise pause the round, otherwise go home (round kept), otherwise minimise |
| process death | on launch, `recoverSession` produces `paused(reason: recovered)` |

Secret content is rendered only inside `<Show when={visible()}>`. When covered, it is
unmounted. It never appears in attributes, `aria-label`s or live regions.

`PrivacyScreenPlugin.java` toggles `FLAG_SECURE`, plus `setRecentsScreenshotEnabled(false)`
on Android 13 and later. It is on while `screen === 'game'` and a round is active.
`openCard()` awaits `setPrivacyScreen(true)` before dispatching `OPEN_CARD`.

## Adapter boundaries

| Boundary | Interface | Implementations |
| --- | --- | --- |
| Storage | `KeyValueStore` | Preferences, memory |
| Native | `NativeBridge` | Capacitor (`App`, `Haptics`, `PrivacyScreen`), noop/fake |
| Billing | `BillingAdapter` | `revenuecat.ts`, `mock.ts` (dev only), `unavailable.ts` |
| Randomness | `RandomSource` | crypto, seeded (tests only) |
| Measurement | `Metrics` | local bounded counters |

UI components only call the controller (`src/app/controller.ts`). The controller only
talks to these interfaces.

### Billing selection (`services/billing/index.ts`)

1. Native Android with a public RevenueCat key uses RevenueCat.
2. A non-production build with `__MOCK_BILLING_ENABLED__` uses the mock. It is
   dynamically imported, so it is dead code in production.
3. Anything else uses `unavailable`. Purchases are shown as unavailable; success is
   never faked.

`__MOCK_BILLING_ENABLED__` is a compile-time constant. `vite.config.ts` throws if
mock billing is requested in production mode, `scripts/check-build-mode.ts` stops
`pnpm build`, and `release:check` plus the production Playwright suite scan the
shipped bundle for demo-store code.

### Ownership

The set of unlocked packs is `knownEntitlements(customerInfo.entitlements.active)`.
Only identifiers this app version knows are honoured. The app never writes an
"is premium" flag. Offline, the RevenueCat SDK returns its cached CustomerInfo. A
failed refresh never removes ownership already confirmed in the current run. A store
update that drops an entitlement (refund or revocation) removes access.
