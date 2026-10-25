/**
 * Real Google Play billing through RevenueCat's maintained Capacitor SDK
 * (@revenuecat/purchases-capacitor). Only loaded on native Android with a public
 * SDK key. Every call below is a documented method from the SDK's typings.
 *
 * Ownership comes from CustomerInfo.entitlements.active. The SDK keeps its own
 * cached CustomerInfo and returns it while offline (RevenueCat "Caching" docs), so
 * previously confirmed purchases keep working offline without any app-side flag.
 */
import {
  type CustomerInfo,
  LOG_LEVEL,
  PRODUCT_CATEGORY,
  PURCHASES_ERROR_CODE,
  Purchases,
  type PurchasesStoreProduct,
} from '@revenuecat/purchases-capacitor';
import { knownEntitlements, PRODUCT_IDS, type ProductId } from './catalog';
import type {
  BillingAdapter,
  Ownership,
  OwnershipResult,
  ProductsResult,
  PurchaseResult,
  RestoreResult,
} from './types';

interface ErrorLike {
  code?: string;
  message?: string;
  userCancelled?: boolean | null;
}

const asError = (e: unknown): ErrorLike =>
  typeof e === 'object' && e !== null ? (e as ErrorLike) : {};

function isOfflineError(e: ErrorLike): boolean {
  return (
    e.code === PURCHASES_ERROR_CODE.NETWORK_ERROR ||
    e.code === PURCHASES_ERROR_CODE.OFFLINE_CONNECTION_ERROR
  );
}

/** Generic, non-identifying message; SDK messages are not shown verbatim to players. */
function describe(e: ErrorLike): string {
  switch (e.code) {
    case PURCHASES_ERROR_CODE.STORE_PROBLEM_ERROR:
      return 'Google Play had a problem. Please try again later.';
    case PURCHASES_ERROR_CODE.CONFIGURATION_ERROR:
      return 'The store is not configured correctly for this build.';
    case PURCHASES_ERROR_CODE.PRODUCT_NOT_AVAILABLE_FOR_PURCHASE_ERROR:
      return 'This item is not available to buy right now.';
    default:
      return 'Something went wrong with the purchase. You have not been charged by Bluff Club.';
  }
}

export function ownershipFrom(info: CustomerInfo, now: number): Ownership {
  return { entitlements: knownEntitlements(Object.keys(info.entitlements.active)), checkedAt: now };
}

export function createRevenueCatBilling(
  apiKey: string,
  options: { debugLogs: boolean; now?: () => number },
): BillingAdapter {
  const now = options.now ?? Date.now;
  const products = new Map<ProductId, PurchasesStoreProduct>();
  let configured = false;

  const ensure = () => {
    if (!configured) throw Object.assign(new Error('not configured'), { code: 'not-configured' });
  };

  const fetchProducts = async (): Promise<ProductsResult> => {
    ensure();
    try {
      // Google Play one-time products must be requested as NON_SUBSCRIPTION:
      // the SDK defaults to SUBSCRIPTION on Android.
      const { products: list } = await Purchases.getProducts({
        productIdentifiers: [...PRODUCT_IDS],
        type: PRODUCT_CATEGORY.NON_SUBSCRIPTION,
      });
      products.clear();
      for (const p of list) {
        if ((PRODUCT_IDS as readonly string[]).includes(p.identifier))
          products.set(p.identifier as ProductId, p);
      }
      return {
        status: 'ok',
        products: [...products.values()].map((p) => ({
          id: p.identifier as ProductId,
          title: p.title,
          description: p.description,
          priceString: p.priceString,
          currencyCode: p.currencyCode,
        })),
      };
    } catch (error) {
      const e = asError(error);
      if (isOfflineError(e))
        return { status: 'offline', message: 'Connect to the internet to see prices.' };
      return { status: 'error', message: describe(e) };
    }
  };

  return {
    kind: 'revenuecat',
    isDemo: false,

    async initialize() {
      try {
        const { isConfigured } = await Purchases.isConfigured();
        if (!isConfigured) {
          // Anonymous App User ID (no accounts). Restores rely on the Play account and
          // the project's transfer behaviour; see docs/BILLING_SETUP.md.
          await Purchases.configure({ apiKey });
        }
        await Purchases.setLogLevel({
          level: options.debugLogs ? LOG_LEVEL.DEBUG : LOG_LEVEL.ERROR,
        });
        configured = true;
        return { status: 'ready' };
      } catch {
        return {
          status: 'unavailable',
          reason: 'init-failed',
          message: 'The store could not be started.',
        };
      }
    },

    getProducts: fetchProducts,

    async purchase(productId): Promise<PurchaseResult> {
      try {
        ensure();
      } catch {
        return { status: 'unavailable', message: 'The store is not ready.' };
      }
      let product = products.get(productId);
      if (!product) {
        const result = await fetchProducts();
        if (result.status === 'offline') return { status: 'offline' };
        product = products.get(productId);
      }
      if (!product)
        return { status: 'not-available', message: 'This item is not available right now.' };
      try {
        const { customerInfo } = await Purchases.purchaseStoreProduct({ product });
        // Unlock only what the store's entitlements confirm.
        return { status: 'success', ownership: ownershipFrom(customerInfo, now()) };
      } catch (error) {
        const e = asError(error);
        if (e.userCancelled || e.code === PURCHASES_ERROR_CODE.PURCHASE_CANCELLED_ERROR) {
          return { status: 'cancelled' };
        }
        if (e.code === PURCHASES_ERROR_CODE.PAYMENT_PENDING_ERROR) return { status: 'pending' };
        if (e.code === PURCHASES_ERROR_CODE.PRODUCT_ALREADY_PURCHASED_ERROR) {
          const refreshed = await this.refreshOwnership();
          return {
            status: 'already-owned',
            ownership: refreshed.status === 'ok' ? refreshed.ownership : null,
          };
        }
        if (isOfflineError(e)) return { status: 'offline' };
        if (e.code === PURCHASES_ERROR_CODE.PRODUCT_NOT_AVAILABLE_FOR_PURCHASE_ERROR) {
          return { status: 'not-available', message: describe(e) };
        }
        return { status: 'error', message: describe(e) };
      }
    },

    async restore(): Promise<RestoreResult> {
      try {
        ensure();
        const { customerInfo } = await Purchases.restorePurchases();
        return { status: 'restored', ownership: ownershipFrom(customerInfo, now()) };
      } catch (error) {
        const e = asError(error);
        if (e.code === 'not-configured')
          return { status: 'unavailable', message: 'The store is not ready.' };
        if (isOfflineError(e)) return { status: 'offline' };
        return { status: 'error', message: describe(e) };
      }
    },

    async refreshOwnership(): Promise<OwnershipResult> {
      try {
        ensure();
        // Returns the SDK's cached CustomerInfo when offline (documented behaviour).
        const { customerInfo } = await Purchases.getCustomerInfo();
        return { status: 'ok', ownership: ownershipFrom(customerInfo, now()) };
      } catch (error) {
        const e = asError(error);
        if (e.code === 'not-configured')
          return { status: 'unavailable', message: 'The store is not ready.' };
        if (isOfflineError(e))
          return { status: 'offline', message: 'Offline: could not check purchases.' };
        return { status: 'error', message: describe(e) };
      }
    },

    onOwnershipChange(listener) {
      let id: string | null = null;
      let removed = false;
      Purchases.addCustomerInfoUpdateListener((info) => listener(ownershipFrom(info, now())))
        .then((callbackId) => {
          id = callbackId;
          if (removed)
            void Purchases.removeCustomerInfoUpdateListener({ listenerToRemove: callbackId });
        })
        .catch(() => {});
      return () => {
        removed = true;
        if (id) void Purchases.removeCustomerInfoUpdateListener({ listenerToRemove: id });
      };
    },
  };
}
