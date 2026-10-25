import type { RandomSource } from '../../src/domain/random';

/** Deterministic source cycling through a fixed list of uint32 values. */
export function sequenceRandom(values: readonly number[]): RandomSource & { calls: number } {
  let i = 0;
  const source = {
    calls: 0,
    nextUint32() {
      source.calls++;
      const v = values[i % values.length] as number;
      i++;
      return v;
    },
  };
  return source;
}

/** Small deterministic PRNG (mulberry32) for property-style tests. Test-only. */
export function seededRandom(seed: number): RandomSource {
  let a = seed >>> 0;
  return {
    nextUint32() {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return (t ^ (t >>> 14)) >>> 0;
    },
  };
}
