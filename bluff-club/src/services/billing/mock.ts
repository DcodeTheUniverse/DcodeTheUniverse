/**
 * DEVELOPMENT/TEST ONLY. Loaded via dynamic import behind the compile-time constant
 * __MOCK_BILLING_ENABLED__, which vite.config.ts forces to false (and refuses to
 * override) for production builds; `pnpm release:check` also scans the output.
 * Every surface that uses this adapter is labelled "Demo purchase — no charge".
 */
import type { KeyValueStore } from '../storage/storage';
import {
  type EntitlementId,
  isProductOwned,
  PRODUCT_IDS,
  PRODUCTS,
  type ProductId,
} from './catalog';
import type { BillingAdapter, Ownership, PurchaseResult, StoreProduct } from './types';

export const DEMO_LABEL = 'Demo purchase — no charge';

export type MockOutcome = 'success' | 'cancel' | 'pending' | 'error' | 'offline';

export interface MockBillingControls {
  nextOutcome(): MockOutcome;
  setNextOutcome(outcome: MockOutcome): void;
  hasPending(): boolean;
  completePending(): Promise<void>;
  refund(productId: ProductId): Promise<void>;
  refundAll(): Promise<void>;
  setProductsFail(fail: boolean): void;
}

export interface MockBillingAdapter extends BillingAdapter {
  readonly controls: MockBillingControls;
}

const STORE_KEY = 'demo-store.purchases';
const DEMO_PRICES: Record<ProductId, string> = {
  bluffclub_food_fight: 'R 39,00',
  bluffclub_office_chaos: 'R 39,00',
  bluffclub_sa_game_night: 'R 39,00',
  bluffclub_wild_card: 'R 39,00',
  bluffclub_party_bundle: 'R 99,00',
};

export function createMockBilling(
  store: KeyValueStore,
  options: { now?: () => number; delayMs?: number } = {},
): MockBillingAdapter {
  const now = options.now ?? Date.now;
  const delay = (ms = options.delayMs ?? 350) => new Promise((r) => setTimeout(r, ms));
  let purchased = new Set<ProductId>();
  let pending: ProductId | null = null;
  let outcome: MockOutcome = 'success';
  let productsFail = false;
  const listeners = new Set<(o: Ownership) => void>();

  const ownership = (): Ownership => {
    const entitlements = new Set<EntitlementId>();
    for (const id of purchased) for (const e of PRODUCTS[id].entitlements) entitlements.add(e);
    return { entitlements, checkedAt: now() };
  };
  const persist = () => store.set(STORE_KEY, JSON.stringify([...purchased]));
  const emit = () => {
    const o = ownership();
    for (const l of listeners) l(o);
  };

  const adapter: MockBillingAdapter = {
    kind: 'mock',
    isDemo: true,
    async initialize() {
      try {
        const raw = await store.get(STORE_KEY);
        const ids = raw ? (JSON.parse(raw) as unknown) : [];
        purchased = new Set(
          Array.isArray(ids) ? ids.filter((id): id is ProductId => PRODUCT_IDS.includes(id)) : [],
        );
      } catch {
        purchased = new Set();
      }
      return { status: 'ready' };
    },
    async getProducts() {
      await delay();
      if (productsFail) return { status: 'error', message: 'Demo store: product lookup failed.' };
      const products: StoreProduct[] = PRODUCT_IDS.map((id) => ({
        id,
        title: id,
        description: DEMO_LABEL,
        priceString: DEMO_PRICES[id],
        currencyCode: 'ZAR',
      }));
      return { status: 'ok', products };
    },
    async purchase(productId): Promise<PurchaseResult> {
      await delay();
      const current = ownership();
      if (isProductOwned(productId, current.entitlements)) {
        return { status: 'already-owned', ownership: current };
      }
      // The chosen outcome stays selected until changed (simple to drive from tests/UI).
      switch (outcome) {
        case 'cancel':
          return { status: 'cancelled' };
        case 'pending':
          pending = productId;
          return { status: 'pending' };
        case 'error':
          return { status: 'error', message: 'Demo store: simulated purchase error.' };
        case 'offline':
          return { status: 'offline' };
        case 'success':
          purchased.add(productId);
          await persist();
          emit();
          return { status: 'success', ownership: ownership() };
      }
    },
    async restore() {
      await delay();
      return { status: 'restored', ownership: ownership() };
    },
    async refreshOwnership() {
      return { status: 'ok', ownership: ownership() };
    },
    onOwnershipChange(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    controls: {
      nextOutcome: () => outcome,
      setNextOutcome(next) {
        outcome = next;
      },
      hasPending: () => pending !== null,
      async completePending() {
        if (!pending) return;
        purchased.add(pending);
        pending = null;
        await persist();
        emit();
      },
      async refund(productId) {
        purchased.delete(productId);
        await persist();
        emit();
      },
      async refundAll() {
        purchased.clear();
        await persist();
        emit();
      },
      setProductsFail(fail) {
        productsFail = fail;
      },
    },
  };
  return adapter;
}
