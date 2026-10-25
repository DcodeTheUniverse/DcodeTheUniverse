/**
 * Injected randomness. Production uses the Web Crypto CSPRNG; tests inject a
 * deterministic source. There is intentionally no seedable production source.
 */
export interface RandomSource {
  /** Returns a uniformly distributed unsigned 32-bit integer. */
  nextUint32(): number;
}

const UINT32_RANGE = 0x1_0000_0000;

export function createCryptoRandomSource(
  cryptoImpl: Pick<Crypto, 'getRandomValues'> = globalThis.crypto,
): RandomSource {
  if (!cryptoImpl || typeof cryptoImpl.getRandomValues !== 'function') {
    throw new Error('Secure randomness (crypto.getRandomValues) is unavailable.');
  }
  const buffer = new Uint32Array(64);
  let index = buffer.length;
  return {
    nextUint32() {
      if (index >= buffer.length) {
        cryptoImpl.getRandomValues(buffer);
        index = 0;
      }
      // biome-ignore lint/style/noNonNullAssertion: index is always within the buffer
      return buffer[index++]!;
    },
  };
}

/**
 * Unbiased integer in [0, maxExclusive) using rejection sampling: values in the
 * incomplete final "bucket" of the 2^32 range are discarded so every outcome has
 * exactly the same number of preimages.
 */
export function randomInt(rng: RandomSource, maxExclusive: number): number {
  if (!Number.isInteger(maxExclusive) || maxExclusive <= 0 || maxExclusive > UINT32_RANGE) {
    throw new RangeError(`maxExclusive must be an integer in 1..2^32, got ${maxExclusive}`);
  }
  const limit = rejectionLimit(maxExclusive);
  for (;;) {
    const value = rng.nextUint32();
    if (!Number.isInteger(value) || value < 0 || value >= UINT32_RANGE) {
      throw new RangeError('RandomSource returned a value outside the uint32 range');
    }
    if (value < limit) return value % maxExclusive;
  }
}

/** Largest multiple of n that fits in the uint32 range; draws at or above it are rejected. */
export function rejectionLimit(n: number): number {
  return UINT32_RANGE - (UINT32_RANGE % n);
}

export function pickOne<T>(rng: RandomSource, items: readonly T[]): T {
  if (items.length === 0) throw new RangeError('Cannot pick from an empty list');
  return items[randomInt(rng, items.length)] as T;
}

/** Fisher–Yates (Durstenfeld) shuffle returning a new array. */
export function shuffle<T>(rng: RandomSource, items: readonly T[]): T[] {
  const result = items.slice();
  for (let i = result.length - 1; i > 0; i--) {
    const j = randomInt(rng, i + 1);
    const tmp = result[i] as T;
    result[i] = result[j] as T;
    result[j] = tmp;
  }
  return result;
}

/** Non-secret opaque identifier (player IDs, round IDs). */
export function randomId(rng: RandomSource, prefix: string): string {
  let out = '';
  for (let i = 0; i < 3; i++) out += rng.nextUint32().toString(36).padStart(7, '0');
  return `${prefix}_${out}`;
}
