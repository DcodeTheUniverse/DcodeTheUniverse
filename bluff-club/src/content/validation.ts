/**
 * Pure content validation shared by `pnpm content:validate` (Node) and the unit tests.
 * Keep this file free of Vite/browser-only imports so Node can run it directly.
 */

export const DIFFICULTIES = ['easy', 'medium', 'hard'] as const;
export type Difficulty = (typeof DIFFICULTIES)[number];

export interface WordEntry {
  readonly id: string;
  readonly packId: string;
  readonly word: string;
  readonly difficulty: Difficulty;
  readonly tags?: readonly string[];
}

export interface PackFile {
  readonly id: string;
  readonly name: string;
  readonly tier: 'free' | 'premium';
  readonly contentVersion: number;
  readonly description: string;
  readonly previews: readonly string[];
  readonly entries: readonly WordEntry[];
}

/** Required launch packs and their minimum entry counts. */
export const REQUIRED_PACKS: Readonly<Record<string, { tier: 'free' | 'premium'; min: number }>> = {
  general: { tier: 'free', min: 100 },
  food_fight: { tier: 'premium', min: 60 },
  office_chaos: { tier: 'premium', min: 60 },
  sa_game_night: { tier: 'premium', min: 60 },
  wild_card: { tier: 'premium', min: 60 },
};

export const MAX_WORD_LENGTH = 28;
export const MAX_DESCRIPTION_LENGTH = 120;

export interface ValidationReport {
  readonly errors: string[];
  /** Possible near-duplicates for a human editor; never a hard failure. */
  readonly reviewFlags: string[];
}

export function normalizePhrase(text: string): string {
  return text
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .toLocaleLowerCase('en')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

function stem(word: string): string {
  return word.replace(/(ies|es|s)$/u, '');
}

function tokens(text: string): string[] {
  return normalizePhrase(text)
    .split(' ')
    .filter((t) => t.length > 2 && !['the', 'and'].includes(t))
    .map(stem);
}

function levenshtein(a: string, b: string): number {
  const dp = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = dp[0] as number;
    dp[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = dp[j] as number;
      dp[j] = Math.min(
        (dp[j] as number) + 1,
        (dp[j - 1] as number) + 1,
        prev + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
      prev = tmp;
    }
  }
  return dp[b.length] as number;
}

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

export function validatePacks(raw: readonly unknown[]): ValidationReport {
  const errors: string[] = [];
  const reviewFlags: string[] = [];
  const packIds = new Set<string>();
  const entryIds = new Map<string, string>();
  const phrases = new Map<string, string>();
  const all: { packId: string; id: string; word: string }[] = [];

  raw.forEach((pack, index) => {
    const where = `pack[${index}]`;
    if (!isObj(pack)) {
      errors.push(`${where}: not an object`);
      return;
    }
    const id = typeof pack.id === 'string' ? pack.id : '';
    if (!/^[a-z][a-z0-9_]*$/.test(id))
      errors.push(`${where}: invalid pack id "${String(pack.id)}"`);
    if (packIds.has(id)) errors.push(`${where}: duplicate pack id "${id}"`);
    packIds.add(id);
    for (const field of ['name', 'description'] as const) {
      if (typeof pack[field] !== 'string' || !(pack[field] as string).trim()) {
        errors.push(`${id}: missing ${field}`);
      }
    }
    if (typeof pack.description === 'string' && pack.description.length > MAX_DESCRIPTION_LENGTH) {
      errors.push(`${id}: description longer than ${MAX_DESCRIPTION_LENGTH} characters`);
    }
    if (pack.tier !== 'free' && pack.tier !== 'premium') errors.push(`${id}: invalid tier`);
    if (!Number.isInteger(pack.contentVersion) || (pack.contentVersion as number) < 1) {
      errors.push(`${id}: contentVersion must be a positive integer`);
    }
    const required = REQUIRED_PACKS[id];
    if (required && pack.tier !== required.tier) errors.push(`${id}: must be ${required.tier}`);

    const entries = Array.isArray(pack.entries) ? pack.entries : [];
    if (!Array.isArray(pack.entries)) errors.push(`${id}: entries must be an array`);
    if (required && entries.length < required.min) {
      errors.push(`${id}: has ${entries.length} entries, needs at least ${required.min}`);
    }
    const words = new Set<string>();
    entries.forEach((entry: unknown, i: number) => {
      const at = `${id}.entries[${i}]`;
      if (!isObj(entry)) {
        errors.push(`${at}: not an object`);
        return;
      }
      const entryId = typeof entry.id === 'string' ? entry.id : '';
      if (!/^[a-z][a-z0-9_]*$/.test(entryId))
        errors.push(`${at}: invalid id "${String(entry.id)}"`);
      const owner = entryIds.get(entryId);
      if (owner) errors.push(`${at}: duplicate entry id "${entryId}" (also in ${owner})`);
      entryIds.set(entryId, id);
      if (entry.packId !== id) {
        errors.push(`${at}: packId "${String(entry.packId)}" does not match pack "${id}"`);
      }
      const word = typeof entry.word === 'string' ? entry.word : '';
      if (!word.trim()) {
        errors.push(`${at}: empty word`);
        return;
      }
      if (word !== word.trim() || /\s{2,}/.test(word))
        errors.push(`${at}: stray whitespace in "${word}"`);
      if (Array.from(word).length > MAX_WORD_LENGTH) {
        errors.push(`${at}: "${word}" is longer than ${MAX_WORD_LENGTH} characters`);
      }
      if (!DIFFICULTIES.includes(entry.difficulty as Difficulty)) {
        errors.push(`${at}: invalid difficulty "${String(entry.difficulty)}"`);
      }
      if (
        'tags' in entry &&
        (!Array.isArray(entry.tags) || entry.tags.some((t) => typeof t !== 'string' || !t.trim()))
      ) {
        errors.push(`${at}: tags must be non-empty strings`);
      }
      const key = normalizePhrase(word);
      const prior = phrases.get(key);
      if (prior) errors.push(`${at}: "${word}" duplicates ${prior}`);
      else phrases.set(key, `${id}/${entryId}`);
      words.add(key);
      all.push({ packId: id, id: entryId, word });
    });

    const previews = Array.isArray(pack.previews) ? pack.previews : [];
    if (pack.tier === 'premium') {
      if (previews.length !== 3)
        errors.push(`${id}: premium packs need exactly 3 preview examples`);
      for (const preview of previews) {
        if (typeof preview !== 'string' || !words.has(normalizePhrase(preview))) {
          errors.push(`${id}: preview "${String(preview)}" is not an entry in the pack`);
        }
      }
    }
  });

  for (const name of Object.keys(REQUIRED_PACKS)) {
    if (!packIds.has(name)) errors.push(`missing required pack "${name}"`);
  }

  // Near-duplicate heuristics, for editorial review only.
  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      const a = all[i] as (typeof all)[number];
      const b = all[j] as (typeof all)[number];
      const na = normalizePhrase(a.word).replace(/ /g, '');
      const nb = normalizePhrase(b.word).replace(/ /g, '');
      // Shared words only matter inside one pack (a clue could give the other away).
      const shared =
        a.packId === b.packId ? tokens(a.word).filter((t) => tokens(b.word).includes(t)) : [];
      const close = Math.min(na.length, nb.length) >= 5 && levenshtein(na, nb) <= 2;
      if (close || shared.length > 0) {
        reviewFlags.push(
          `"${a.word}" (${a.packId}) ~ "${b.word}" (${b.packId})${shared.length ? ` share "${shared.join(', ')}"` : ''}`,
        );
      }
    }
  }
  return { errors, reviewFlags };
}
