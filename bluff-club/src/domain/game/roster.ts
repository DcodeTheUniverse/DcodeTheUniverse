import { type RandomSource, randomId } from '../random';

export const MIN_PLAYERS = 3;
export const MAX_PLAYERS = 10;
/** Measured in user-perceived characters (grapheme clusters), so emoji count as one. */
export const MAX_NAME_LENGTH = 16;

export type PlayerId = string;

export interface Player {
  readonly id: PlayerId;
  readonly name: string;
}

export type NameError = 'blank' | 'too-long' | 'duplicate';
export type RosterError = 'too-few' | 'too-many';

export interface RosterValidation {
  readonly ok: boolean;
  readonly rosterErrors: readonly RosterError[];
  readonly nameErrors: Readonly<Record<PlayerId, NameError>>;
}

/** Trims and collapses inner whitespace; keeps Unicode intact (NFC). */
export function cleanName(raw: string): string {
  return raw.normalize('NFC').replace(/\s+/gu, ' ').trim();
}

/** Comparison key for case-insensitive duplicate detection (Unicode-aware). */
export function nameKey(raw: string): string {
  return cleanName(raw).normalize('NFKC').toLocaleLowerCase('und');
}

export function graphemeLength(text: string): number {
  const Segmenter = (Intl as { Segmenter?: typeof Intl.Segmenter }).Segmenter;
  if (Segmenter) {
    let count = 0;
    for (const _ of new Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) count++;
    return count;
  }
  return Array.from(text).length;
}

export function validateName(raw: string): Exclude<NameError, 'duplicate'> | null {
  const name = cleanName(raw);
  if (name.length === 0) return 'blank';
  if (graphemeLength(name) > MAX_NAME_LENGTH) return 'too-long';
  return null;
}

export function validateRoster(players: readonly Player[]): RosterValidation {
  const rosterErrors: RosterError[] = [];
  if (players.length < MIN_PLAYERS) rosterErrors.push('too-few');
  if (players.length > MAX_PLAYERS) rosterErrors.push('too-many');

  const nameErrors: Record<PlayerId, NameError> = {};
  const seen = new Map<string, PlayerId>();
  for (const player of players) {
    const error = validateName(player.name);
    if (error) {
      nameErrors[player.id] = error;
      continue;
    }
    const key = nameKey(player.name);
    if (seen.has(key)) {
      nameErrors[player.id] = 'duplicate';
    } else {
      seen.set(key, player.id);
    }
  }
  return {
    ok: rosterErrors.length === 0 && Object.keys(nameErrors).length === 0,
    rosterErrors,
    nameErrors,
  };
}

export function createPlayer(rng: RandomSource, name: string): Player {
  return { id: randomId(rng, 'p'), name };
}

/** Lowest "Player N" label not already used (case-insensitively). */
export function nextDefaultName(players: readonly Player[]): string {
  const used = new Set(players.map((p) => nameKey(p.name)));
  for (let n = 1; ; n++) {
    const candidate = `Player ${n}`;
    if (!used.has(nameKey(candidate))) return candidate;
  }
}

export function defaultRoster(rng: RandomSource, count = 4): Player[] {
  const players: Player[] = [];
  for (let i = 0; i < count; i++) players.push(createPlayer(rng, nextDefaultName(players)));
  return players;
}

/** Same set of player IDs (renames keep the score session). */
export function sameRosterIds(a: readonly Player[], b: readonly Player[]): boolean {
  if (a.length !== b.length) return false;
  const ids = new Set(a.map((p) => p.id));
  return b.every((p) => ids.has(p.id));
}

export function finalizeRoster(players: readonly Player[]): Player[] {
  return players.map((p) => ({ id: p.id, name: cleanName(p.name) }));
}
