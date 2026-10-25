import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BUNDLE_ID,
  ENTITLEMENTS,
  isBundleOffered,
  isPackUnlocked,
  isProductOwned,
  knownEntitlements,
  PRODUCT_IDS,
  PRODUCTS,
  productForPack,
} from '../../src/services/billing/catalog';
import { createMockBilling } from '../../src/services/billing/mock';
import { createMemoryStore } from '../../src/services/storage/storage';

// --- RevenueCat SDK mocked at its module boundary (no network, no native code) ---
const sdk = vi.hoisted(() => ({
  isConfigured: vi.fn(),
  configure: vi.fn(),
  setLogLevel: vi.fn(),
  getProducts: vi.fn(),
  purchaseStoreProduct: vi.fn(),
  restorePurchases: vi.fn(),
  getCustomerInfo: vi.fn(),
  addCustomerInfoUpdateListener: vi.fn(),
  removeCustomerInfoUpdateListener: vi.fn(),
}));
vi.mock('@revenuecat/purchases-capacitor', () => ({
  Purchases: sdk,
  LOG_LEVEL: { DEBUG: 'DEBUG', ERROR: 'ERROR' },
  PRODUCT_CATEGORY: { NON_SUBSCRIPTION: 'NON_SUBSCRIPTION', SUBSCRIPTION: 'SUBSCRIPTION' },
  PURCHASES_ERROR_CODE: {
    PURCHASE_CANCELLED_ERROR: '1',
    STORE_PROBLEM_ERROR: '2',
    PRODUCT_NOT_AVAILABLE_FOR_PURCHASE_ERROR: '5',
    PRODUCT_ALREADY_PURCHASED_ERROR: '6',
    NETWORK_ERROR: '10',
    PAYMENT_PENDING_ERROR: '20',
    CONFIGURATION_ERROR: '23',
    OFFLINE_CONNECTION_ERROR: '35',
  },
}));

const { createRevenueCatBilling } = await import('../../src/services/billing/revenuecat');

const info = (active: string[]) => ({
  customerInfo: {
    entitlements: { active: Object.fromEntries(active.map((a) => [a, { isActive: true }])) },
  },
});
const product = (id: string) => ({
  identifier: id,
  title: id,
  description: 'd',
  priceString: 'R39.00',
  currencyCode: 'ZAR',
  productCategory: 'NON_SUBSCRIPTION',
});

describe('catalog', () => {
  it('maps every product to explicit entitlements; the bundle is exactly the four launch packs', () => {
    expect(PRODUCTS.bluffclub_party_bundle.entitlements).toEqual([...ENTITLEMENTS]);
    expect(PRODUCTS.bluffclub_party_bundle.entitlements).toHaveLength(4);
    for (const id of PRODUCT_IDS.filter((p) => p !== BUNDLE_ID)) {
      expect(PRODUCTS[id].entitlements).toHaveLength(1);
    }
    expect(productForPack('sa_game_night')).toBe('bluffclub_sa_game_night');
  });

  it('ignores unknown (e.g. future) entitlements rather than treating the bundle as "everything"', () => {
    const owned = knownEntitlements(['pack_food_fight', 'pack_future_pack', 'premium']);
    expect([...owned]).toEqual(['pack_food_fight']);
    expect(isPackUnlocked('future_pack', new Set(ENTITLEMENTS))).toBe(false);
  });

  it('keeps General free and gates premium packs individually', () => {
    expect(isPackUnlocked('general', new Set())).toBe(true);
    expect(isPackUnlocked('wild_card', new Set())).toBe(false);
    expect(isPackUnlocked('wild_card', new Set(['pack_wild_card']))).toBe(true);
  });

  it('offers the bundle only when none of its packs is owned', () => {
    expect(isBundleOffered(new Set())).toBe(true);
    expect(isBundleOffered(new Set(['pack_office_chaos']))).toBe(false);
    expect(isProductOwned(BUNDLE_ID, new Set(ENTITLEMENTS))).toBe(true);
  });
});

describe('mock billing (development only)', () => {
  it('labels itself as a demo and persists only to its own key', async () => {
    const store = createMemoryStore();
    const mock = createMockBilling(store, { delayMs: 0 });
    expect(mock.isDemo).toBe(true);
    await mock.initialize();
    const r = await mock.purchase('bluffclub_wild_card');
    expect(r.status).toBe('success');
    expect([...store.data.keys()]).toEqual(['demo-store.purchases']);
    expect((await mock.purchase('bluffclub_wild_card')).status).toBe('already-owned');
  });
});

describe('RevenueCat adapter', () => {
  beforeEach(() => {
    for (const fn of Object.values(sdk)) fn.mockReset();
    sdk.isConfigured.mockResolvedValue({ isConfigured: false });
    sdk.configure.mockResolvedValue(undefined);
    sdk.setLogLevel.mockResolvedValue(undefined);
    sdk.getProducts.mockResolvedValue({ products: PRODUCT_IDS.map(product) });
  });

  async function ready() {
    const billing = createRevenueCatBilling('goog_public_key', { debugLogs: false, now: () => 1 });
    expect(await billing.initialize()).toEqual({ status: 'ready' });
    return billing;
  }

  it('configures with the public key only (anonymous user) and requests non-subscription products', async () => {
    const billing = await ready();
    expect(sdk.configure).toHaveBeenCalledWith({ apiKey: 'goog_public_key' });
    const result = await billing.getProducts();
    expect(sdk.getProducts).toHaveBeenCalledWith({
      productIdentifiers: [...PRODUCT_IDS],
      type: 'NON_SUBSCRIPTION',
    });
    expect(result.status === 'ok' && result.products[0]?.priceString).toBe('R39.00');
  });

  it('grants ownership only from confirmed active entitlements', async () => {
    const billing = await ready();
    sdk.purchaseStoreProduct.mockResolvedValue(info(['pack_office_chaos', 'unrelated']));
    const r = await billing.purchase('bluffclub_office_chaos');
    expect(r.status).toBe('success');
    if (r.status === 'success')
      expect([...r.ownership.entitlements]).toEqual(['pack_office_chaos']);
  });

  it.each([
    [{ code: '1', userCancelled: true }, 'cancelled'],
    [{ code: '20' }, 'pending'],
    [{ code: '10' }, 'offline'],
    [{ code: '35' }, 'offline'],
    [{ code: '5' }, 'not-available'],
    [{ code: '2' }, 'error'],
  ])('maps SDK error %o to %s without unlocking anything', async (error, status) => {
    const billing = await ready();
    sdk.purchaseStoreProduct.mockRejectedValue(error);
    const r = await billing.purchase('bluffclub_food_fight');
    expect(r.status).toBe(status);
    expect('ownership' in r).toBe(false);
  });

  it('treats "already purchased" as a restore of confirmed ownership', async () => {
    const billing = await ready();
    sdk.purchaseStoreProduct.mockRejectedValue({ code: '6' });
    sdk.getCustomerInfo.mockResolvedValue(info(['pack_food_fight']));
    const r = await billing.purchase('bluffclub_food_fight');
    expect(r.status).toBe('already-owned');
  });

  it('restores and refreshes from CustomerInfo; offline refresh is reported, not treated as revocation', async () => {
    const billing = await ready();
    sdk.restorePurchases.mockResolvedValue(info(['pack_food_fight', 'pack_wild_card']));
    const restored = await billing.restore();
    expect(restored.status === 'restored' && restored.ownership.entitlements.size).toBe(2);
    sdk.getCustomerInfo.mockRejectedValue({ code: '10' });
    expect((await billing.refreshOwnership()).status).toBe('offline');
  });

  it('forwards later customer-info updates (pending approved, refunds) as ownership changes', async () => {
    const billing = await ready();
    let push: (i: unknown) => void = () => {};
    sdk.addCustomerInfoUpdateListener.mockImplementation(async (cb: (i: unknown) => void) => {
      push = cb;
      return 'cb-1';
    });
    const seen: number[] = [];
    const off = billing.onOwnershipChange((o) => seen.push(o.entitlements.size));
    await Promise.resolve();
    push(info(['pack_food_fight']).customerInfo);
    push(info([]).customerInfo);
    expect(seen).toEqual([1, 0]);
    off();
    expect(sdk.removeCustomerInfoUpdateListener).toHaveBeenCalledWith({ listenerToRemove: 'cb-1' });
  });

  it('reports initialisation failure instead of throwing', async () => {
    sdk.isConfigured.mockRejectedValue(new Error('no plugin'));
    const billing = createRevenueCatBilling('goog_x', { debugLogs: false });
    expect((await billing.initialize()).status).toBe('unavailable');
    expect((await billing.purchase('bluffclub_food_fight')).status).toBe('unavailable');
  });
});
