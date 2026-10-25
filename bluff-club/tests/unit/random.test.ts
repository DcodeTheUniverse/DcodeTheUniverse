import { describe, expect, it } from 'vitest';
import {
  createCryptoRandomSource,
  pickOne,
  randomInt,
  rejectionLimit,
  shuffle,
} from '../../src/domain/random';
import { seededRandom, sequenceRandom } from './helpers';

describe('randomInt (unbiased bounded selection)', () => {
  it('rejects values at or above the largest multiple of n', () => {
    // 2^32 % 3 === 1, so the limit is 2^32 - 1 and 0xFFFFFFFF must be rejected.
    expect(rejectionLimit(3)).toBe(0xffffffff);
    const rng = sequenceRandom([0xffffffff, 5]);
    expect(randomInt(rng, 3)).toBe(5 % 3);
    expect(rng.calls).toBe(2);
  });

  it('accepts the last value below the limit', () => {
    const limit = rejectionLimit(10);
    const rng = sequenceRandom([limit - 1]);
    expect(randomInt(rng, 10)).toBe((limit - 1) % 10);
    expect(rng.calls).toBe(1);
  });

  it('never rejects for powers of two', () => {
    expect(rejectionLimit(8)).toBe(0x1_0000_0000);
    const rng = sequenceRandom([0xffffffff]);
    expect(randomInt(rng, 8)).toBe(7);
  });

  it('returns 0 for n = 1 and validates its bound', () => {
    expect(randomInt(sequenceRandom([123]), 1)).toBe(0);
    expect(() => randomInt(sequenceRandom([1]), 0)).toThrow(RangeError);
    expect(() => randomInt(sequenceRandom([1]), 2.5)).toThrow(RangeError);
  });

  it('rejects malformed random sources', () => {
    expect(() => randomInt(sequenceRandom([-1]), 3)).toThrow(RangeError);
    expect(() => randomInt(sequenceRandom([2 ** 32]), 3)).toThrow(RangeError);
  });

  it('is roughly uniform over many draws (sanity, seeded)', () => {
    const rng = seededRandom(42);
    const counts = new Array(10).fill(0) as number[];
    for (let i = 0; i < 50_000; i++) counts[randomInt(rng, 10)]! += 1;
    for (const c of counts) expect(Math.abs(c - 5000)).toBeLessThan(400);
  });
});

describe('shuffle', () => {
  it('is a permutation and does not mutate its input', () => {
    const input = [1, 2, 3, 4, 5, 6, 7];
    const out = shuffle(seededRandom(7), input);
    expect(out).toHaveLength(7);
    expect([...out].sort()).toEqual(input);
    expect(input).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('is deterministic for a deterministic source', () => {
    expect(shuffle(seededRandom(99), [1, 2, 3, 4])).toEqual(
      shuffle(seededRandom(99), [1, 2, 3, 4]),
    );
  });

  it('produces every permutation of 3 items with similar frequency', () => {
    const rng = seededRandom(3);
    const counts = new Map<string, number>();
    for (let i = 0; i < 60_000; i++) {
      const key = shuffle(rng, ['a', 'b', 'c']).join('');
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    expect(counts.size).toBe(6);
    for (const c of counts.values()) expect(Math.abs(c - 10_000)).toBeLessThan(600);
  });
});

describe('pickOne / crypto source', () => {
  it('throws on empty lists', () => {
    expect(() => pickOne(seededRandom(1), [])).toThrow();
  });

  it('uses crypto.getRandomValues and refills its buffer', () => {
    let fills = 0;
    const fake = {
      getRandomValues<T extends ArrayBufferView | null>(arr: T): T {
        fills++;
        const view = arr as unknown as Uint32Array;
        for (let i = 0; i < view.length; i++) view[i] = i;
        return arr;
      },
    };
    const rng = createCryptoRandomSource(fake);
    for (let i = 0; i < 65; i++) rng.nextUint32();
    expect(fills).toBe(2);
  });

  it('refuses to run without secure randomness', () => {
    expect(() => createCryptoRandomSource({} as unknown as Crypto)).toThrow();
  });
});
