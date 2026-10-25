# Content guide

## Packs in v1

| Pack | ID | Tier | Entries | Brief |
| --- | --- | --- | --- | --- |
| General | `general` | free | 100 | Familiar everyday things |
| Food Fight | `food_fight` | premium | 60 | Food, cooking, kitchen situations |
| Office Chaos | `office_chaos` | premium | 60 | Workplace objects and situations |
| SA Game Night | `sa_game_night` | premium | 60 | South African references for a braai or game night |
| Wild Card | `wild_card` | premium | 60 | Unusual but understandable objects or situations |

Files: `src/content/packs/<id>.json`. Each pack has `id`, `name`, `tier`,
`contentVersion`, `description` (≤ 120 chars), `previews` (exactly 3 real entries for
premium packs) and `entries`.

Each entry:

```json
{ "id": "sa_001", "packId": "sa_game_night", "word": "Braai", "difficulty": "easy", "tags": ["slang"] }
```

- `id`: stable, lowercase, never reused or renumbered. Retire entries by deleting
  them; never reassign an ID to a different word.
- `difficulty`: `easy | medium | hard`. "Hard" means harder to clue without giving it
  away, not obscure.
- `tags`: optional. Used only where helpful (SA slang).

When you change a pack's entries, **increment `contentVersion`**. The deck reconciler
then shuffles new entries into each player's current cycle and drops removed ones.
No word repeats early, and saved progress survives.

## Writing rules

- Prefer broadly recognisable prompts over trivia. Someone at a family braai should
  know them.
- A good word has several honest one-word clues and some ambiguity for the bluffer to
  hide in.
- No ethnic stereotypes, no humiliating identity-based prompts, no explicit sexual
  content, no drinking dares, no gambling. Teens and adults should both be
  comfortable.
- Avoid brand names and trademarks (e.g. "Puzzle cube", not the brand name).
- Avoid real living people and political figures.
- Within one pack, avoid entries that share an obvious word ("Toast" / "Burnt
  toast"). A clue for one would give the other away.
- Write everything yourself. Do not copy any competitor's word lists, tutorials,
  screenshots or packs.

## Validation

```bash
pnpm content:validate
```

Hard failures (exit code 1):

- schema or field types
- required packs and minimum counts
- duplicate pack or entry IDs
- empty words
- `packId` mismatches
- exact duplicate phrases after normalisation (case, accents, punctuation)
- words over 28 characters or descriptions over 120
- previews that are not real entries

Review flags (printed, not failures):

- possible near-duplicates: shared significant words within one pack, or an edit
  distance of 2 or less across packs

These need a human decision. The current flags are listed by the script; at the time
of writing they were 6 low-risk pairs (e.g. *Bicycle / Unicycle*, *Reply all /
Out-of-office reply*).

**Passing validation does not mean the content is funny, fair or balanced.** Only
playtesting can show that.

## Provenance

- All 340 v1 entries, pack descriptions and preview choices were written for Bluff
  Club on 2026-10-25, with AI assistance, from the briefs in the product spec. No
  external word database was used or consulted.
- SA Game Night examples given in the brief (braai, bunny chow, vuvuzela, minibus taxi,
  koeksister) are included as requested.
- Icon and splash art (`assets/*.svg`) are original vector drawings made for this
  project. The UI uses system fonts only.
- **Required before launch:** a human editor, ideally including South African
  reviewers for SA Game Night, reads every entry and signs off.

## Human playtest checklist (per pack)

- [ ] Every entry read aloud by a reviewer; nothing offensive, mean or confusing
- [ ] SA Game Night checked by at least two South Africans from different regions/backgrounds
- [ ] At least 10 rounds per pack with 4–6 players
- [ ] Note words that were guessed by the bluffer immediately (too easy to fake)
- [ ] Note words nobody could clue without saying it (too hard)
- [ ] Note words players did not know (too obscure)
- [ ] Re-tag difficulty based on the notes, then bump `contentVersion`
- [ ] Re-run `pnpm content:validate` and review the flags
