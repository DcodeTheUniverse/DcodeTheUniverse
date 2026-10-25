import type { PackId } from '../../content';

/**
 * Central, typed product → entitlement mapping. These identifiers must match the
 * Google Play Console product IDs and the RevenueCat entitlement identifiers exactly
 * (see docs/BILLING_SETUP.md). Prices are NOT defined here: they always come from
 * Google Play product data at runtime, localized by the store.
 */
export const ENTITLEMENTS = [
  'pack_food_fight',
  'pack_office_chaos',
  'pack_sa_game_night',
  'pack_wild_card',
] as const;
export type EntitlementId = (typeof ENTITLEMENTS)[number];

export type PremiumPackId = Exclude<PackId, 'general'>;

export const PACK_ENTITLEMENT: Readonly<Record<PremiumPackId, EntitlementId>> = {
  food_fight: 'pack_food_fight',
  office_chaos: 'pack_office_chaos',
  sa_game_night: 'pack_sa_game_night',
  wild_card: 'pack_wild_card',
};

export const PRODUCT_IDS = [
  'bluffclub_food_fight',
  'bluffclub_office_chaos',
  'bluffclub_sa_game_night',
  'bluffclub_wild_card',
  'bluffclub_party_bundle',
] as const;
export type ProductId = (typeof PRODUCT_IDS)[number];

export interface ProductDefinition {
  readonly id: ProductId;
  readonly kind: 'pack' | 'bundle';
  /** Exactly the entitlements this product grants. A bundle never means "everything". */
  readonly entitlements: readonly EntitlementId[];
  readonly packIds: readonly PremiumPackId[];
}

export const PRODUCTS: Readonly<Record<ProductId, ProductDefinition>> = {
  bluffclub_food_fight: {
    id: 'bluffclub_food_fight',
    kind: 'pack',
    entitlements: ['pack_food_fight'],
    packIds: ['food_fight'],
  },
  bluffclub_office_chaos: {
    id: 'bluffclub_office_chaos',
    kind: 'pack',
    entitlements: ['pack_office_chaos'],
    packIds: ['office_chaos'],
  },
  bluffclub_sa_game_night: {
    id: 'bluffclub_sa_game_night',
    kind: 'pack',
    entitlements: ['pack_sa_game_night'],
    packIds: ['sa_game_night'],
  },
  bluffclub_wild_card: {
    id: 'bluffclub_wild_card',
    kind: 'pack',
    entitlements: ['pack_wild_card'],
    packIds: ['wild_card'],
  },
  // The Party Bundle is a fixed set: exactly the four launch premium packs.
  bluffclub_party_bundle: {
    id: 'bluffclub_party_bundle',
    kind: 'bundle',
    entitlements: ['pack_food_fight', 'pack_office_chaos', 'pack_sa_game_night', 'pack_wild_card'],
    packIds: ['food_fight', 'office_chaos', 'sa_game_night', 'wild_card'],
  },
};

export const BUNDLE_ID: ProductId = 'bluffclub_party_bundle';

export function productForPack(packId: PremiumPackId): ProductId {
  const product = PRODUCT_IDS.find((id) => {
    const p = PRODUCTS[id];
    return p.kind === 'pack' && p.packIds.includes(packId);
  });
  if (!product) throw new Error(`No product for pack ${packId}`);
  return product;
}

export function isEntitlementId(id: string): id is EntitlementId {
  return (ENTITLEMENTS as readonly string[]).includes(id);
}

export function isPremiumPack(packId: string): packId is PremiumPackId {
  return Object.hasOwn(PACK_ENTITLEMENT, packId);
}

/** Free packs are always unlocked; premium packs need their own entitlement. */
export function isPackUnlocked(packId: string, owned: ReadonlySet<EntitlementId>): boolean {
  if (!isPremiumPack(packId)) return packId === 'general';
  return owned.has(PACK_ENTITLEMENT[packId]);
}

export function isProductOwned(productId: ProductId, owned: ReadonlySet<EntitlementId>): boolean {
  return PRODUCTS[productId].entitlements.every((e) => owned.has(e));
}

/**
 * v1 policy: the bundle is offered only when none of its packs is owned yet.
 * There is no prorated upgrade; owners of some packs buy the rest individually.
 */
export function isBundleOffered(owned: ReadonlySet<EntitlementId>): boolean {
  return PRODUCTS[BUNDLE_ID].entitlements.every((e) => !owned.has(e));
}

/** Keeps only entitlement IDs this app version knows about. */
export function knownEntitlements(ids: Iterable<string>): Set<EntitlementId> {
  const out = new Set<EntitlementId>();
  for (const id of ids) if (isEntitlementId(id)) out.add(id);
  return out;
}
