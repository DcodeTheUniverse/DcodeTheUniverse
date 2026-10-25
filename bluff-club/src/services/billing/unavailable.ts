import type { BillingAdapter, UnavailableReason } from './types';

const MESSAGES: Record<UnavailableReason, string> = {
  'not-native': 'Purchases are only available in the Bluff Club Android app from Google Play.',
  'missing-config': 'Purchases are not configured in this build.',
  'init-failed': 'The store could not be started. Free packs still work.',
};

/** Honest "no store" adapter: never fakes a successful payment. */
export function createUnavailableBilling(reason: UnavailableReason): BillingAdapter {
  const message = MESSAGES[reason];
  return {
    kind: 'unavailable',
    isDemo: false,
    initialize: async () => ({ status: 'unavailable', reason, message }),
    getProducts: async () => ({ status: 'unavailable', message }),
    purchase: async () => ({ status: 'unavailable', message }),
    restore: async () => ({ status: 'unavailable', message }),
    refreshOwnership: async () => ({ status: 'unavailable', message }),
    onOwnershipChange: () => () => {},
  };
}

export function unavailableMessage(reason: UnavailableReason): string {
  return MESSAGES[reason];
}
