# Sources, versions and verification dates

Checked on **2026-10-25**. Re-check before each release; store policies change.

## Pinned dependencies (exact versions in `pnpm-lock.yaml`)

| Package | Version |
| --- | --- |
| solid-js | 1.9.17 |
| vite / vite-plugin-solid | 8.3.4 / 2.11.14 |
| tailwindcss / @tailwindcss/vite | 4.3.3 / 4.3.3 |
| typescript | 6.0.3 |
| vitest / jsdom | 5.0.3 / 29.1.1 (30.x needs Node ≥ 22.22.2) |
| @solidjs/testing-library / @testing-library/jest-dom / user-event | 0.8.10 / 7.0.1 / 14.6.1 |
| @playwright/test | 1.63.0 |
| @biomejs/biome | 2.5.15 |
| @capacitor/core, cli, android | 8.5.3 |
| @capacitor/app / preferences / haptics | 8.1.2 / 8.0.1 / 8.0.2 |
| @revenuecat/purchases-capacitor | 13.7.3 |

Native chain, checked by inspecting the published artifacts:
`@revenuecat/purchases-capacitor` 13.7.3 → `purchases-hybrid-common` 19.10.0 →
`purchases` (Android) 10.26.0 → Google Play Billing Library **8.3.0**. None of these
AARs contain `.so` native libraries, and neither does the built APK, so the 16 KB
page-size requirement is met trivially. Verify again if native plugins are added.

## Facts relied on (and where they came from)

| Topic | Fact | Source |
| --- | --- | --- |
| Capacitor 8 | Android Studio Otter 2025.2.1+, Node 22+, min/compile/target SDK 24/36, Gradle 8.14.3, AGP 8.13 | https://capacitorjs.com/docs/updating/8-0, https://capacitorjs.com/docs/android |
| Capacitor App | `backButton`, `pause`, `resume`, `appStateChange`, `minimizeApp()`. Adding a `backButton` listener disables the default behaviour | https://capacitorjs.com/docs/apis/app + package typings |
| Capacitor Preferences | `get/set/remove/keys/clear`; SharedPreferences on Android; not a database | https://capacitorjs.com/docs/apis/preferences |
| Safe areas | Capacitor 8 `SystemBars` injects `--safe-area-inset-*` CSS variables when `viewport-fit=cover` | `@capacitor/android` 8.5.3 source (`SystemBars.java`) |
| RevenueCat API | `configure`, `getProducts({productIdentifiers, type})` (defaults to SUBSCRIPTION on Android, so pass `NON_SUBSCRIPTION`), `purchaseStoreProduct`, `restorePurchases`, `getCustomerInfo`, `addCustomerInfoUpdateListener`, error codes (cancelled 1, pending 20, already purchased 6, network 10, offline 35) | package typings 13.7.3 + https://www.revenuecat.com/docs/getting-started/installation/capacitor |
| Non-consumables | Products must be marked **non-consumable in the RevenueCat dashboard**, or RevenueCat consumes them (re-buyable, and unrecoverable for anonymous users with Billing Library 8) | https://www.revenuecat.com/docs/getting-started/entitlements/android-products, https://www.revenuecat.com/docs/platform-resources/non-subscriptions, https://www.revenuecat.com/docs/getting-started/restoring-purchases |
| Caching | CustomerInfo cached; `cachedOrFetched` returns cache even if stale; returned offline indefinitely unless invalidated; refreshed after 5 min foreground / 25 h background | https://www.revenuecat.com/docs/test-and-launch/debugging/caching |
| Offline Entitlements | A separate RevenueCat feature that activates only when RevenueCat servers fail while posting a purchase. It **does not apply to one-time purchases** | https://www.revenuecat.com/docs/customers/customer-info |
| Anonymous IDs / restore | `$RCAnonymousID:` IDs are regenerated on reinstall. Restore behaviour setting: Project settings → General, default **Transfer to new App User ID** | https://www.revenuecat.com/docs/customers/identifying-customers, https://www.revenuecat.com/docs/projects/restore-behavior |
| Bank-app verification | Launch modes other than `standard`/`singleTop` can cancel purchases when backgrounded for bank verification | RevenueCat Capacitor installation page |
| FLAG_SECURE | Recommended for sensitive activities; `HIDE_OVERLAY_WINDOWS` also discussed. The page does **not** discuss launch modes | https://developer.android.com/security/fraud-prevention/activities |
| Target API | From 31 Aug 2026, new apps and updates must target Android 16 (API 36) or higher; extension possible to 1 Nov 2026 | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Billing Library | From 31 Aug 2026 new apps and updates must use Billing Library 8+ (extension to 1 Nov 2026) | https://developer.android.com/google/play/billing/deprecation-faq |
| Testing requirement | Personal developer accounts created after 13 Nov 2023 must run a closed test with ≥ 12 opted-in testers for ≥ 14 days before production access | https://support.google.com/googleplay/android-developer/answer/14151465 |
| User Data policy | Privacy policy, prominent disclosure and Data safety obligations | https://support.google.com/googleplay/android-developer/answer/10144311 |
| 16 KB pages | From 1 Feb 2027, updates must support 16 KB page sizes; pure Java/Kotlin apps already do | https://developer.android.com/guide/practices/page-sizes |

The original brief cited answer/14151465 as a billing page and answer/10144311 as a
testing page. On fetching, they are the testing requirement and the User Data policy
respectively. The table reflects what the pages actually say.
