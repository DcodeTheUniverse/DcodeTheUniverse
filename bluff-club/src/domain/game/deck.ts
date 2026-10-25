import { type RandomSource, randomInt, shuffle } from '../random';

/**
 * Per-pack shuffled deck. `remaining` is a Fisher–Yates shuffled queue of entry IDs
 * for the current cycle; `drawn` records what this cycle has already used. Keeping
 * both lets us reconcile safely when bundled content changes between app versions:
 * removed IDs disappear, newly added IDs join the current cycle at random positions,
 * and nothing already drawn this cycle is repeated.
 */
export interface DeckState {
  readonly packId: string;
  readonly contentVersion: number;
  readonly remaining: readonly string[];
  readonly drawn: readonly string[];
  readonly lastWordId: string | null;
}

export interface DrawResult {
  readonly wordId: string;
  readonly deck: DeckState;
}

export function emptyDeck(packId: string, contentVersion: number): DeckState {
  return { packId, contentVersion, remaining: [], drawn: [], lastWordId: null };
}

/** Make a (possibly stale or hand-edited) deck consistent with the current pack contents. */
export function reconcileDeck(
  deck: DeckState | undefined,
  packId: string,
  contentVersion: number,
  entryIds: readonly string[],
  rng: RandomSource,
): DeckState {
  const base = deck && deck.packId === packId ? deck : emptyDeck(packId, contentVersion);
  const valid = new Set(entryIds);
  const seen = new Set<string>();
  const dedupe = (ids: readonly string[]) =>
    ids.filter((id) => {
      if (!valid.has(id) || seen.has(id)) return false;
      seen.add(id);
      return true;
    });
  const drawn = dedupe(base.drawn);
  const remaining = dedupe(base.remaining);
  const added = entryIds.filter((id) => !seen.has(id));
  const lastWordId = base.lastWordId && valid.has(base.lastWordId) ? base.lastWordId : null;

  if (added.length === 0 && base.contentVersion === contentVersion) {
    return { packId, contentVersion, remaining, drawn, lastWordId };
  }
  // New or unknown entries join the not-yet-drawn part of the cycle, reshuffled together.
  return {
    packId,
    contentVersion,
    remaining: added.length > 0 ? shuffle(rng, [...remaining, ...added]) : remaining,
    drawn,
    lastWordId,
  };
}

/** Starts a fresh cycle, avoiding an immediate repeat of `lastWordId` when possible. */
export function reshuffle(
  entryIds: readonly string[],
  lastWordId: string | null,
  rng: RandomSource,
): string[] {
  const order = shuffle(rng, entryIds);
  if (order.length > 1 && lastWordId !== null && order[0] === lastWordId) {
    // Swap the repeated head with a uniformly chosen later position.
    const j = 1 + randomInt(rng, order.length - 1);
    order[0] = order[j] as string;
    order[j] = lastWordId;
  }
  return order;
}

export function drawWord(
  deck: DeckState | undefined,
  packId: string,
  contentVersion: number,
  entryIds: readonly string[],
  rng: RandomSource,
): DrawResult {
  if (entryIds.length === 0) throw new Error(`Pack ${packId} has no entries`);
  let state = reconcileDeck(deck, packId, contentVersion, entryIds, rng);
  if (state.remaining.length === 0) {
    state = { ...state, remaining: reshuffle(entryIds, state.lastWordId, rng), drawn: [] };
  }
  const [wordId, ...rest] = state.remaining as [string, ...string[]];
  return {
    wordId,
    deck: { ...state, remaining: rest, drawn: [...state.drawn, wordId], lastWordId: wordId },
  };
}
