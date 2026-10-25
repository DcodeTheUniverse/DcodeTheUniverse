import { createRoot } from 'solid-js';
import { type AppController, createAppController } from '../../src/app/controller';
import { createMockBilling } from '../../src/services/billing/mock';
import type { BillingAdapter } from '../../src/services/billing/types';
import { createNoopNative, type NativeBridge } from '../../src/services/native/native';
import { createMemoryStore } from '../../src/services/storage/storage';
import { seededRandom } from './helpers';

/** Native bridge whose lifecycle events tests can fire by hand. */
export function fakeNative() {
  const listeners = {
    background: new Set<() => void>(),
    blur: new Set<() => void>(),
    back: new Set<() => void>(),
  };
  const privacyCalls: boolean[] = [];
  let minimized = 0;
  const native: NativeBridge = createNoopNative({
    platform: 'android',
    isNative: true,
    onBackground: (l) => {
      listeners.background.add(l);
      return () => listeners.background.delete(l);
    },
    onBlur: (l) => {
      listeners.blur.add(l);
      return () => listeners.blur.delete(l);
    },
    onBackButton: (l) => {
      listeners.back.add(l);
      return () => listeners.back.delete(l);
    },
    setPrivacyScreen: async (enabled) => {
      privacyCalls.push(enabled);
    },
    minimizeApp: async () => {
      minimized++;
    },
  });
  return {
    native,
    privacyCalls,
    minimized: () => minimized,
    background: () => {
      for (const l of listeners.background) l();
    },
    blur: () => {
      for (const l of listeners.blur) l();
    },
    back: () => {
      for (const l of listeners.back) l();
    },
  };
}

export interface Harness {
  app: AppController;
  store: ReturnType<typeof createMemoryStore>;
  fake: ReturnType<typeof fakeNative>;
  clock: { now: number };
  dispose: () => void;
}

export async function makeApp(
  opts: {
    store?: ReturnType<typeof createMemoryStore>;
    billing?: BillingAdapter;
    seed?: number;
  } = {},
): Promise<Harness> {
  const store = opts.store ?? createMemoryStore();
  const fake = fakeNative();
  const clock = { now: 1_700_000_000_000 };
  const billing = opts.billing ?? createMockBilling(store, { delayMs: 0 });
  let dispose = () => {};
  const app = createRoot((d) => {
    dispose = d;
    return createAppController({
      store,
      native: fake.native,
      billing,
      rng: seededRandom(opts.seed ?? 1),
      now: () => clock.now,
      sound: () => {},
    });
  });
  await app.init();
  await new Promise((r) => setTimeout(r, 0));
  return { app, store, fake, clock, dispose };
}
