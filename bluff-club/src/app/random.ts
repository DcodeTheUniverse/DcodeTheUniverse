import { createCryptoRandomSource, type RandomSource } from '../domain/random';

/** Production randomness: Web Crypto only. No seeds, no debug controls. */
export function createRandomSource(): RandomSource {
  return createCryptoRandomSource(globalThis.crypto);
}
