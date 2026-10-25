import type { EntitlementId, ProductId } from './catalog';

export type BillingKind = 'revenuecat' | 'mock' | 'unavailable';

export type UnavailableReason =
  | 'not-native' // a browser build: purchases only work in the Android app
  | 'missing-config' // no public RevenueCat key in this build
  | 'init-failed';

export type InitResult =
  | { readonly status: 'ready' }
  | {
      readonly status: 'unavailable';
      readonly reason: UnavailableReason;
      readonly message?: string;
    };

/** Store data straight from Google Play (via the SDK). Price text is already localized. */
export interface StoreProduct {
  readonly id: ProductId;
  readonly title: string;
  readonly description: string;
  readonly priceString: string;
  readonly currencyCode: string;
}

export type ProductsResult =
  | { readonly status: 'ok'; readonly products: readonly StoreProduct[] }
  | { readonly status: 'offline' | 'error' | 'unavailable'; readonly message: string };

export interface Ownership {
  readonly entitlements: ReadonlySet<EntitlementId>;
  /** When the SDK produced this answer (may be from its documented cache). */
  readonly checkedAt: number;
}

export type PurchaseResult =
  | { readonly status: 'success'; readonly ownership: Ownership }
  | { readonly status: 'cancelled' }
  | { readonly status: 'pending' }
  | { readonly status: 'already-owned'; readonly ownership: Ownership | null }
  | { readonly status: 'offline' }
  | { readonly status: 'not-available'; readonly message: string }
  | { readonly status: 'unavailable'; readonly message: string }
  | { readonly status: 'error'; readonly message: string };

export type RestoreResult =
  | { readonly status: 'restored'; readonly ownership: Ownership }
  | { readonly status: 'offline' }
  | { readonly status: 'unavailable'; readonly message: string }
  | { readonly status: 'error'; readonly message: string };

export type OwnershipResult =
  | { readonly status: 'ok'; readonly ownership: Ownership }
  | { readonly status: 'offline' | 'error' | 'unavailable'; readonly message: string };

/**
 * Billing boundary. UI code only sees these methods; SDK specifics stay inside the
 * adapters. Access is granted only from `Ownership` values produced by the adapter
 * after the store confirmed entitlements — never from a local flag.
 */
export interface BillingAdapter {
  readonly kind: BillingKind;
  /** True only for the development mock; drives the "Demo purchase — no charge" labels. */
  readonly isDemo: boolean;
  initialize(): Promise<InitResult>;
  getProducts(): Promise<ProductsResult>;
  purchase(productId: ProductId): Promise<PurchaseResult>;
  restore(): Promise<RestoreResult>;
  refreshOwnership(): Promise<OwnershipResult>;
  /** Fires on later changes: pending payment approved, refund/revocation, restores. */
  onOwnershipChange(listener: (ownership: Ownership) => void): () => void;
}
