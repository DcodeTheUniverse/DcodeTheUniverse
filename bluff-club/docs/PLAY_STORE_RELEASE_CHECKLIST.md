# Play Store release checklist

Run `pnpm release:check` first. It verifies what a script can verify and lists the
rest. **Release blockers are marked ⛔.** Requirements were checked on 2026-10-25 (see
`SOURCES.md`); re-verify them on the day you release.

## A. Machine-checked (`pnpm release:check`)

- [ ] Mock billing disabled; demo-store code absent from the bundle
- [ ] ⛔ `VITE_REVENUECAT_ANDROID_API_KEY` set to the public `goog_…` key (currently **missing**)
- [ ] ⛔ `VITE_SUPPORT_EMAIL` set (currently **missing**)
- [ ] ⛔ `VITE_PRIVACY_POLICY_URL` set to a hosted https policy (currently **missing**)
- [ ] Content validation passes
- [ ] No dev-server URL, live-reload `server.url`, or WebView debugging in the release config
- [ ] targetSdk ≥ 36, launchMode singleTop/standard, only the INTERNET permission in the app manifest
- [ ] No keystores, `.env`, or service-account JSON tracked in git

## B. Owner attestations (no script can verify these)

- [ ] ⛔ Application ID confirmed (proposed `com.zenfracta.bluffclub`; permanent after the first upload)
- [ ] ⛔ "Bluff Club" name/trademark clearance done by the owner (unresolved)
- [ ] ⛔ Legal developer identity, contact address and Play Console account verification
- [ ] ⛔ Privacy policy reviewed (start from `PRIVACY_POLICY_DRAFT.md`) and hosted
- [ ] ⛔ Data safety form completed from `DATA_SAFETY_NOTES.md`, checked against RevenueCat's current SDK disclosures
- [ ] ⛔ Content rating (IARC questionnaire) and target audience/age set. Intended audience is teens and adults, but **no rating has been obtained**
- [ ] ⛔ Closed testing: personal accounts created after 13 Nov 2023 need ≥ 12 testers opted in for ≥ 14 days before applying for production
- [ ] ⛔ Products created and activated in Play Console; RevenueCat configured as in `BILLING_SETUP.md` (non-consumable!)
- [ ] ⛔ Human editorial review of all content (`CONTENT_GUIDE.md`)
- [ ] Prices decided per market (hypothesis: R39 per pack / R99 bundle in ZA)
- [ ] Store listing copy reviewed (`store/STORE_LISTING_DRAFT.md`) and screenshots re-captured on a device

## C. Signing and build

1. Create an **upload key** on a secure machine (never in this repo):
   ```bash
   keytool -genkeypair -v -keystore bluffclub-upload.jks -alias bluffclub-upload \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copy `android/keystore.properties.example` to `android/keystore.properties` (git-ignored) and fill it in.
3. Enrol in **Play App Signing** when creating the app. Google holds the app signing key.
4. Bump `versionCode` in `android/app/build.gradle` and `version` in `package.json`.
5. Build:
   ```bash
   pnpm release:check
   pnpm android:sync
   cd android && ./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab
   ```
6. Inspect it in Android Studio (Build → Analyze APK): no `lib/` native code beyond what
   you expect, the manifest is not debuggable, and the permissions are as expected.

A debug APK (`assembleDebug`) is signed with the local debug key. It is for testing
only and must never be uploaded or described as a production artifact.

## D. Device and console tests (⛔ all NOT EXECUTED)

Complete `ANDROID_TEST_CHECKLIST.md` and the billing test plan in `BILLING_SETUP.md` on
real devices with licence testers. Minimum: one low-end Android 7–9 device, one
Android 14–16 device, and one tablet.

## E. Policy checkpoints to re-verify at release time

- Target API level requirement (API 36 from 31 Aug 2026)
- Billing Library version requirement (8+; the app uses 8.3.0 via RevenueCat)
- 16 KB page size (from 1 Feb 2027 for updates; currently no native libs)
- App signing, account verification, testing requirement for new personal accounts
- Data safety, privacy policy, target audience and content rating
