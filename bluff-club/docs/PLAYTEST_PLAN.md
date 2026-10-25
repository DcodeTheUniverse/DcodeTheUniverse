# Playtest plan

## Goals

1. Can a new group start a game within 2 minutes, with no explanation from us?
2. Do the rules (secret word, bluffer, clues, vote, tie and runoff) feel fair and clear?
3. Is the private reveal actually private in a real room?
4. Do groups play more than one round unprompted? How many?
5. Which words are too easy, too hard or unknown?
6. Do the paywall and packs screen feel honest and non-pushy? Would anyone pay?

## Format

- 6–10 groups of 3–10 people, mixed ages (teens and adults), including at least 3
  South African groups for SA Game Night.
- Android build installed from internal testing. For packs, use licence testers or
  the demo build. The demo build is labelled "Demo purchase — no charge"; never let
  testers think a demo purchase was real.
- One observer per session who does not explain the game. They note confusion,
  hesitation, privacy leaks (someone sees another card), and the moments people laugh.
- 30–45 minutes per session, followed by a 5-minute survey.

## Consent and data

- Get verbal consent before observing. Do not record audio or video unless everyone
  agrees in writing.
- Player names in the app should be first names or nicknames. Do not collect them in
  survey data.
- **Stats export:** with consent, after the session, open Settings → Playtest stats →
  Copy, and paste the JSON into the session notes. It contains only aggregate counters
  (rounds started and finished, outcomes, runoffs, pack usage, purchase attempts and
  outcomes, session sizes). No names, words, roles or votes.

## Survey (1–5 scale plus one open question each)

- How easy was it to start the first round?
- How fair did the result feel?
- Did you ever see someone else's card or vote? (yes/no and when)
- Would you play again with this group? With another group?
- Which pack would you buy, if any? At what price does it feel fair?
- What would you change?

## Interpreting the numbers honestly

- Local counters describe **one shared device**, not individual players. A phone used
  by eight people is one install and, at most, one paying account. Never count
  players as installs or as paying users.
- Counters are not population analytics. They are small-sample observations from
  consenting testers.
- After launch, use **Play Console** (installs, retention, ratings, country) and
  **RevenueCat / Play order reports** (purchases, refunds, restores) for aggregate
  commercial signals. Both are per account/device, not per player.
- Repeat play signal: rounds per session (`round_complete / session_start`) and
  sessions per device over time (local export only).
- Purchase signal: `paywall_view → purchase_attempt → purchase_success` per device,
  plus RevenueCat conversion. Treat small numbers as anecdotes.
- Do not promise revenue, downloads or virality from playtest results.

## Exit criteria for public release

- ≥ 80% of groups start a round unaided in under 2 minutes
- No reproducible privacy leak in the reveal or vote flows
- Every pack reviewed per `CONTENT_GUIDE.md`, with flagged words fixed
- The Android device checklist is complete on at least three devices
