# Billing setup (Google Play + RevenueCat)

> Nothing here has been created in any console by this project. Every step below is
> for the owner to perform. Real-billing behaviour is **unverified** until it has been
> tested on a device with Play licence testers.

## Product → entitlement mapping (source of truth: `src/services/billing/catalog.ts`)

| Google Play product ID (one-time, non-consumable) | Grants RevenueCat entitlement(s) | Price hypothesis (ZA) |
| --- | --- | --- |
| `bluffclub_food_fight` | `pack_food_fight` | R39 once-off |
| `bluffclub_office_chaos` | `pack_office_chaos` | R39 once-off |
| `bluffclub_sa_game_night` | `pack_sa_game_night` | R39 once-off |
| `bluffclub_wild_card` | `pack_wild_card` | R39 once-off |
| `bluffclub_party_bundle` | `pack_food_fight`, `pack_office_chaos`, `pack_sa_game_night`, `pack_wild_card` | R99 once-off |

- Prices are **commercial hypotheses**, set in Play Console. The app never hardcodes a
  checkout price; it shows `priceString` from Google Play product data.
- The bundle grants exactly those four entitlements. A future pack gets a new
  entitlement that must **not** be attached to `bluffclub_party_bundle`. The app
  ignores entitlements it does not know.
- v1 policy: the bundle is hidden once any included pack is owned. There is no
  prorated upgrade.

## Google Play Console

1. Create the app with the confirmed application ID (proposed: `com.zenfracta.bluffclub`).
   This **cannot change** after publication.
2. Upload a build that includes the Billing Library to any testing track. Products
   can only be created after that. The library is merged in via the RevenueCat plugin,
   and its manifest declares `com.android.vending.BILLING`.
3. **Monetize → Products → In-app products:** create the five product IDs above as
   one-time products. Write titles and descriptions that state the exact contents and
   that this is a once-off purchase. Set prices per country and activate them.
4. **Setup → License testing:** add tester Google accounts.
5. Create a Google Cloud service account with the Play Console permissions RevenueCat
   documents, and upload its JSON **to RevenueCat only**. Never put it in this repo
   or in a `VITE_` variable.

## RevenueCat dashboard

1. Create a project and add a Google Play app with the same package name. Upload the
   service-account credentials (server side, in RevenueCat).
2. **Products:** import or create the five products. **Mark each one as
   NON-CONSUMABLE.** If you skip this, RevenueCat consumes the purchase. It then
   becomes re-buyable, and with Billing Library 8 a consumed one-time purchase cannot
   be recovered for anonymous users.
3. **Entitlements:** create `pack_food_fight`, `pack_office_chaos`,
   `pack_sa_game_night` and `pack_wild_card`. Attach each single-pack product to its
   one entitlement, and attach the bundle product to all four.
4. **Project settings → General → Restore behavior:** review it. The default is
   *Transfer to new App User ID*, which RevenueCat recommends for apps using
   anonymous IDs only, like this one. With it, a restore after reinstall moves
   purchases to the new anonymous ID.
5. Copy the **public** Android SDK key (`goog_…`) into `.env.local` as
   `VITE_REVENUECAT_ANDROID_API_KEY`. It is public by design. Never use a secret
   (`sk_…`) key in the app; `release:check` fails on one.
6. Offerings and paywalls are not used. The app requests products by ID with
   `type: NON_SUBSCRIPTION`.

## How the app uses the SDK

| Need | Call |
| --- | --- |
| Start | `Purchases.isConfigured()` then `Purchases.configure({ apiKey })` (anonymous) |
| Prices | `Purchases.getProducts({ productIdentifiers, type: PRODUCT_CATEGORY.NON_SUBSCRIPTION })` |
| Buy | `Purchases.purchaseStoreProduct({ product })` → `customerInfo.entitlements.active` |
| Restore | `Purchases.restorePurchases()` |
| Refresh | `Purchases.getCustomerInfo()` (cached; works offline after a prior fetch) |
| Later changes | `addCustomerInfoUpdateListener` (pending approved, refunds, revocations) |

| SDK outcome | App behaviour |
| --- | --- |
| success | Unlock exactly the active entitlements returned |
| `userCancelled` / code 1 | "Purchase cancelled. Nothing was charged." Nothing unlocks |
| `PAYMENT_PENDING_ERROR` (20) | "Payment pending…". Unlocks later only via a CustomerInfo update |
| `PRODUCT_ALREADY_PURCHASED_ERROR` (6) | Refresh, then treat as restored |
| `NETWORK_ERROR` (10) / `OFFLINE_CONNECTION_ERROR` (35) | "You're offline". Existing ownership is kept |
| other | Generic error. The current game is not touched |

## Offline behaviour and its limits

- Free play never touches billing.
- Paid packs work offline when the SDK has a cached CustomerInfo from an earlier
  online check. That is RevenueCat's documented cache, **not** the separate
  "Offline Entitlements" feature, which does not cover one-time purchases.
- After a reinstall, ownership is unknown until the device goes online and the user
  restores. The anonymous ID changes on reinstall.
- New purchases always need connectivity and online verification.
- Bundled content ships inside the app. This is ordinary in-app purchase gating, **not
  tamper-proof DRM**. A modified or rooted device can reach locked content.

## Test plan (all NOT YET EXECUTED: needs Play Console and a device)

1. Licence tester buys each pack: it unlocks, survives an app restart, and plays in
   airplane mode.
2. Cancel in the Play sheet: nothing unlocks and the message is shown.
3. Slow/declined test card and pending payment: "pending" is shown; approve it later
   and the pack unlocks without restarting.
4. A purchase that triggers external bank-app verification returns to the app (this
   is why `singleTop` is used) and completes.
5. Buy the bundle: all four packs are owned and the bundle disappears. Own one pack
   first: the bundle is not offered.
6. Uninstall, reinstall, Restore: ownership returns. Repeat on a second device with
   the same account.
7. Refund via Play Console or a RevenueCat refund: access is removed after the next
   refresh or update.
8. Confirm in Play Console order history that nothing was consumed.

Mock billing (`pnpm dev --mode demo`) exercises the UI states only. **Passing mock
tests is not evidence that real billing works.**
