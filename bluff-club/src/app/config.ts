/**
 * Centralised branding and public configuration. Only PUBLIC values may live here or
 * in VITE_* variables: they are compiled into the app and readable by anyone.
 */
export const brand = {
  displayName: 'Bluff Club',
  tagline: 'Everyone knows. Someone’s pretending.',
  /** PROPOSED application ID — owner must confirm before the first Play upload. */
  applicationId: 'com.zenfracta.bluffclub',
  minPlayers: 3,
  maxPlayers: 10,
} as const;

const clean = (v: string | undefined) => (v ?? '').trim();

function safeHttpsUrl(v: string): string | null {
  try {
    const url = new URL(v);
    return url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

const email = clean(import.meta.env.VITE_SUPPORT_EMAIL);

export const appConfig = {
  version: __APP_VERSION__,
  buildMode: __BUILD_MODE__,
  /** RevenueCat PUBLIC Android SDK key ("goog_…"). Never a secret key. */
  revenueCatAndroidKey: clean(import.meta.env.VITE_REVENUECAT_ANDROID_API_KEY),
  supportEmail: /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? email : null,
  privacyPolicyUrl: safeHttpsUrl(clean(import.meta.env.VITE_PRIVACY_POLICY_URL)),
  mockBilling: __MOCK_BILLING_ENABLED__,
} as const;
