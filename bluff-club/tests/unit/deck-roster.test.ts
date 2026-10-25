import { describe, expect, it } from 'vitest';
import { drawWord, reconcileDeck, reshuffle } from '../../src/domain/game/deck';
import {
  createPlayer,
  defaultRoster,
  graphemeLength,
  MAX_NAME_LENGTH,
  nextDefaultName,
  type Player,
  sameRosterIds,
  validateRoster,
} from '../../src/domain/game/roster';
import { seededRandom } from './helpers';

const ids = (n: number) => Array.from({ length: n }, (_, i) => `w${i}`);

describe('deck', () => {
  it('does not repeat a word until the deck is exhausted', () => {
    const rng = seededRandom(1);
    const entries = ids(12);
    let deck: ReturnType<typeof drawWord>['deck'] | undefined;
    const seen: string[] = [];
    for (let i = 0; i < 12; i++) {
      const r = drawWord(deck, 'p', 1, entries, rng);
      seen.push(r.wordId);
      deck = r.deck;
    }
    expect(new Set(seen).size).toBe(12);
    expect(deck?.remaining).toHaveLength(0);
    // Next draw reshuffles a fresh cycle.
    const next = drawWord(deck, 'p', 1, entries, rng);
    expect(next.deck.drawn).toEqual([next.wordId]);
    expect(next.deck.remaining).toHaveLength(11);
  });

  it('never repeats the previous word immediately after a reshuffle when avoidable', () => {
    for (let seed = 0; seed < 300; seed++) {
      const rng = seededRandom(seed);
      const order = reshuffle(['a', 'b', 'c'], 'a', rng);
      expect(order[0]).not.toBe('a');
      expect([...order].sort()).toEqual(['a', 'b', 'c']);
    }
  });

  it('repeats only when the pack has a single entry', () => {
    const rng = seededRandom(5);
    const first = drawWord(undefined, 'p', 1, ['only'], rng);
    const second = drawWord(first.deck, 'p', 1, ['only'], rng);
    expect(second.wordId).toBe('only');
  });

  it('crosses cycle boundaries without immediate repeats across many cycles', () => {
    const rng = seededRandom(11);
    const entries = ids(4);
    let deck: ReturnType<typeof drawWord>['deck'] | undefined;
    let last: string | null = null;
    for (let i = 0; i < 400; i++) {
      const r = drawWord(deck, 'p', 1, entries, rng);
      expect(r.wordId).not.toBe(last);
      last = r.wordId;
      deck = r.deck;
    }
  });

  it('reconciles content-version changes safely', () => {
    const rng = seededRandom(2);
    const stale = {
      packId: 'p',
      contentVersion: 1,
      remaining: ['w1', 'gone', 'w1', 'w2'],
      drawn: ['w0', 'also-gone'],
      lastWordId: 'gone',
    };
    const deck = reconcileDeck(stale, 'p', 2, ['w0', 'w1', 'w2', 'w3', 'w4'], rng);
    expect(deck.contentVersion).toBe(2);
    expect(deck.drawn).toEqual(['w0']);
    expect([...deck.remaining].sort()).toEqual(['w1', 'w2', 'w3', 'w4']);
    expect(deck.lastWordId).toBeNull();
  });

  it('ignores a deck that belongs to another pack', () => {
    const deck = reconcileDeck(
      { packId: 'other', contentVersion: 1, remaining: ['w1'], drawn: [], lastWordId: 'w1' },
      'p',
      1,
      ['w1', 'w2'],
      seededRandom(1),
    );
    expect(deck.packId).toBe('p');
    expect([...deck.remaining].sort()).toEqual(['w1', 'w2']);
  });
});

describe('roster validation', () => {
  const rng = seededRandom(4);
  const make = (...names: string[]): Player[] => names.map((n) => createPlayer(rng, n));

  it('accepts 3 and 10 players and rejects 2 and 11', () => {
    expect(validateRoster(make('A', 'B', 'C')).ok).toBe(true);
    expect(validateRoster(make(...'ABCDEFGHIJ'.split(''))).ok).toBe(true);
    expect(validateRoster(make('A', 'B')).rosterErrors).toContain('too-few');
    expect(validateRoster(make(...'ABCDEFGHIJK'.split(''))).rosterErrors).toContain('too-many');
  });

  it('rejects blank names after trimming', () => {
    const players = make('A', '   ', 'C');
    const v = validateRoster(players);
    expect(v.ok).toBe(false);
    expect(v.nameErrors[players[1]!.id]).toBe('blank');
  });

  it('rejects case-insensitive and whitespace-variant duplicates', () => {
    const players = make('Thandi', ' thandi ', 'ZOË', 'zoë');
    const v = validateRoster(players);
    expect(v.nameErrors[players[1]!.id]).toBe('duplicate');
    expect(v.nameErrors[players[3]!.id]).toBe('duplicate');
    expect(v.nameErrors[players[0]!.id]).toBeUndefined();
  });

  it('supports Unicode and counts graphemes for the length limit', () => {
    expect(graphemeLength('👩🏽‍🚀')).toBe(1);
    expect(validateRoster(make('Siyabonga', '李雷', '👩🏽‍🚀 Ace')).ok).toBe(true);
    const tooLong = make('x'.repeat(MAX_NAME_LENGTH + 1), 'B', 'C');
    expect(validateRoster(tooLong).nameErrors[tooLong[0]!.id]).toBe('too-long');
    expect(validateRoster(make('x'.repeat(MAX_NAME_LENGTH), 'B', 'C')).ok).toBe(true);
  });

  it('provides unused default names and stable IDs', () => {
    const roster = defaultRoster(rng, 3);
    expect(roster.map((p) => p.name)).toEqual(['Player 1', 'Player 2', 'Player 3']);
    expect(new Set(roster.map((p) => p.id)).size).toBe(3);
    expect(nextDefaultName([roster[0]!, roster[2]!])).toBe('Player 2');
  });

  it('treats renames as the same roster but membership changes as different', () => {
    const roster = make('A', 'B', 'C');
    const renamed = roster.map((p) => ({ ...p, name: `${p.name}!` }));
    expect(sameRosterIds(roster, renamed)).toBe(true);
    expect(sameRosterIds(roster, roster.slice(0, 2))).toBe(false);
  });
});
