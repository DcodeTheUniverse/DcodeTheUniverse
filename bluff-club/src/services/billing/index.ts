import { appConfig } from '../../app/config';
import type { NativeBridge } from '../native/native';
import type { KeyValueStore } from '../storage/storage';
import type { BillingAdapter } from './types';
import { createUnavailableBilling } from './unavailable';

export type { BillingAdapter } from './types';

/**
 * Adapter selection:
 * 1. Native Android + public RevenueCat key  → real Google Play billing.
 * 2. Non-production build with mock enabled  → clearly labelled demo store.
 * 3. Anything else                           → "purchases unavailable" (never fake success).
 *
 * The mock module is only reachable when the compile-time constant is true, so a
 * production bundle does not contain it at all.
 */
export async function createBillingAdapter(
  native: NativeBridge,
  store: KeyValueStore,
): Promise<BillingAdapter> {
  const apiKey = appConfig.revenueCatAndroidKey;
  if (native.platform === 'android' && native.isNative && apiKey) {
    const { createRevenueCatBilling } = await import('./revenuecat');
    return createRevenueCatBilling(apiKey, { debugLogs: import.meta.env.DEV });
  }
  if (__MOCK_BILLING_ENABLED__) {
    const { createMockBilling } = await import('./mock');
    return createMockBilling(store);
  }
  return createUnavailableBilling(native.isNative ? 'missing-config' : 'not-native');
}
