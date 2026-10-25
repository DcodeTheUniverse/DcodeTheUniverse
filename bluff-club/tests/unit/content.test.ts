import { describe, expect, it } from 'vitest';
import { PACKS } from '../../src/content';
import { normalizePhrase, validatePacks } from '../../src/content/validation';

describe('bundled content', () => {
  it('passes automated validation', () => {
    expect(validatePacks(PACKS).errors).toEqual([]);
  });

  it('meets the launch counts', () => {
    const counts = Object.fromEntries(PACKS.map((p) => [p.id, p.entries.length]));
    expect(counts.general).toBeGreaterThanOrEqual(100);
    for (const id of ['food_fight', 'office_chaos', 'sa_game_night', 'wild_card']) {
      expect(counts[id]).toBeGreaterThanOrEqual(60);
    }
  });
});

describe('content validator', () => {
  const pack = (over: Record<string, unknown> = {}) => ({
    id: 'general',
    name: 'General',
    tier: 'free',
    contentVersion: 1,
    description: 'x',
    previews: [],
    entries: [{ id: 'g_1', packId: 'general', word: 'Cat', difficulty: 'easy' }],
    ...over,
  });

  it('reports schema, count, id, empty, reference, duplicate and length problems', () => {
    const { errors } = validatePacks([
      pack({
        entries: [
          { id: 'g_1', packId: 'general', word: 'Cat', difficulty: 'easy' },
          { id: 'g_1', packId: 'general', word: 'Dog', difficulty: 'easy' },
          { id: 'g_2', packId: 'general', word: '  ', difficulty: 'easy' },
          { id: 'g_3', packId: 'nope', word: 'Bird', difficulty: 'easy' },
          { id: 'g_4', packId: 'general', word: 'CAT!', difficulty: 'easy' },
          { id: 'g_5', packId: 'general', word: 'x'.repeat(40), difficulty: 'easy' },
          { id: 'g_6', packId: 'general', word: 'Fish', difficulty: 'impossible' },
        ],
      }),
    ]);
    const text = errors.join('\n');
    expect(text).toMatch(/needs at least 100/);
    expect(text).toMatch(/duplicate entry id "g_1"/);
    expect(text).toMatch(/empty word/);
    expect(text).toMatch(/packId "nope"/);
    expect(text).toMatch(/"CAT!" duplicates/);
    expect(text).toMatch(/longer than/);
    expect(text).toMatch(/invalid difficulty/);
    expect(text).toMatch(/missing required pack "food_fight"/);
  });

  it('requires three real previews for premium packs', () => {
    const { errors } = validatePacks([
      pack({ id: 'food_fight', tier: 'premium', previews: ['Cat', 'Nope'] }),
    ]);
    expect(errors.join('\n')).toMatch(/exactly 3 preview/);
    expect(errors.join('\n')).toMatch(/preview "Nope" is not an entry/);
  });

  it('flags near-duplicates for review without failing', () => {
    const r = validatePacks([
      pack({
        entries: [
          { id: 'g_1', packId: 'general', word: 'Coffee machine', difficulty: 'easy' },
          { id: 'g_2', packId: 'general', word: 'Coffee mug', difficulty: 'easy' },
        ],
      }),
    ]);
    expect(r.reviewFlags.length).toBe(1);
    expect(r.errors.join()).not.toMatch(/Coffee/);
  });

  it('normalizes accents, case and punctuation', () => {
    expect(normalizePhrase('Droëwors!')).toBe('droewors');
    expect(normalizePhrase('Out-of-office  reply')).toBe('out of office reply');
  });
});
