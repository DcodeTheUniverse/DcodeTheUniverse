/**
 * pnpm release:check — fails on machine-detectable release blockers.
 *
 * It distinguishes three kinds of items:
 *   1. Static checks this script can verify (FAIL blocks the release).
 *   2. Owner attestations no script can verify (listed, never auto-passed).
 *   3. Real-device / console tests (listed as NOT EXECUTED; mocks never count).
 *
 * It builds the production bundle first so the scan covers exactly what would ship.
 */
import { execSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadEnv } from 'vite';

const root = new URL('..', import.meta.url).pathname;
const read = (p: string) => readFileSync(join(root, p), 'utf8');
const results: { ok: boolean; label: string; detail?: string }[] = [];
const check = (ok: boolean, label: string, detail?: string) =>
  results.push({ ok, label, ...(detail ? { detail } : {}) });

// ---------------------------------------------------------------- environment
const env = { ...loadEnv('production', root, 'VITE_'), ...process.env } as Record<
  string,
  string | undefined
>;
check(env.VITE_ENABLE_MOCK_BILLING !== 'true', 'Mock billing disabled for production');
const rcKey = (env.VITE_REVENUECAT_ANDROID_API_KEY ?? '').trim();
check(
  rcKey.startsWith('goog_'),
  'RevenueCat PUBLIC Android SDK key configured (goog_…)',
  rcKey ? undefined : 'VITE_REVENUECAT_ANDROID_API_KEY is empty',
);
check(!/^(sk_|sk-)/.test(rcKey), 'No RevenueCat secret key used as the client key');
check(
  /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(env.VITE_SUPPORT_EMAIL ?? ''),
  'Support email configured',
  'VITE_SUPPORT_EMAIL',
);
check(
  /^https:\/\/\S+$/.test(env.VITE_PRIVACY_POLICY_URL ?? ''),
  'Hosted privacy policy URL configured (https)',
  'VITE_PRIVACY_POLICY_URL',
);

// ---------------------------------------------------------------- content
try {
  execSync('node scripts/validate-content.ts', { cwd: root, stdio: 'pipe' });
  check(true, 'Bundled content passes automated validation');
} catch (error) {
  check(
    false,
    'Bundled content passes automated validation',
    String((error as { stdout?: Buffer }).stdout ?? ''),
  );
}

// ---------------------------------------------------------------- production bundle
let built = false;
try {
  execSync('node scripts/check-build-mode.ts && pnpm exec vite build --mode production', {
    cwd: root,
    stdio: 'pipe',
    env: { ...process.env },
  });
  built = true;
} catch (error) {
  check(
    false,
    'Production web build succeeds',
    String((error as { stderr?: Buffer }).stderr ?? '').slice(0, 400),
  );
}
if (built) {
  check(true, 'Production web build succeeds');
  const assets = readdirSync(join(root, 'dist/assets')).filter((f) => f.endsWith('.js'));
  const bundle = assets.map((f) => read(`dist/assets/${f}`)).join('\n');
  check(
    !bundle.includes('demo-store.purchases'),
    'Demo store code excluded from the production bundle',
  );
  check(!bundle.includes('Demo purchase'), 'No demo purchase labels in the production bundle');
  check(
    !/localhost:\d{2,5}/.test(read('dist/index.html').replace(/connect-src[^;]*;/, '')),
    'No dev-server URLs in index.html',
  );
  check(!/\bsk_[A-Za-z0-9]{10,}/.test(bundle), 'No secret-looking API keys in the bundle');
}

// ---------------------------------------------------------------- capacitor / android
const capConfig = read('capacitor.config.ts');
check(
  !/server\s*:\s*\{[^}]*url\s*:/s.test(capConfig),
  'Capacitor config has no live-reload server.url',
);
check(
  /webContentsDebuggingEnabled:\s*false/.test(capConfig),
  'WebView debugging disabled in Capacitor config',
);
const appId = /appId:\s*'([^']+)'/.exec(capConfig)?.[1];
const brandId = /applicationId:\s*'([^']+)'/.exec(read('src/app/config.ts'))?.[1];
const gradle = existsSync(join(root, 'android/app/build.gradle'))
  ? read('android/app/build.gradle')
  : '';
check(!!gradle, 'Android project present');
check(
  !!appId && appId === brandId && gradle.includes(`applicationId "${appId}"`),
  'Application ID consistent (capacitor, gradle, brand config)',
);
const vars = existsSync(join(root, 'android/variables.gradle'))
  ? read('android/variables.gradle')
  : '';
const target = Number(/targetSdkVersion\s*=\s*(\d+)/.exec(vars)?.[1] ?? 0);
check(
  target >= 36,
  `targetSdkVersion ≥ 36 (found ${target}; Play requirement verified 2026-10-25)`,
);
const manifest = existsSync(join(root, 'android/app/src/main/AndroidManifest.xml'))
  ? read('android/app/src/main/AndroidManifest.xml')
  : '';
check(
  /launchMode="(singleTop|standard)"/.test(manifest),
  'MainActivity launchMode is singleTop/standard (purchase bank-app verification)',
);
const perms = [...manifest.matchAll(/uses-permission android:name="([^"]+)"/g)].map((m) => m[1]);
check(
  perms.every((p) => p === 'android.permission.INTERNET'),
  'Only INTERNET declared in app manifest',
  perms.join(', '),
);
check(
  existsSync(
    join(root, 'android/app/src/main/java/com/zenfracta/bluffclub/PrivacyScreenPlugin.java'),
  ),
  'FLAG_SECURE privacy plugin present',
);

// ---------------------------------------------------------------- repository hygiene
let tracked: string[] = [];
try {
  tracked = execSync('git ls-files', { cwd: root, stdio: 'pipe' }).toString().split('\n');
} catch {
  /* not a git checkout */
}
check(
  !tracked.some((f) => /\.(jks|keystore|p12)$/.test(f) || f.endsWith('keystore.properties')),
  'No keystores or signing properties in git',
);
check(
  !tracked.some((f) => /(^|\/)\.env(\.local|\.production(\.local)?)?$/.test(f)),
  'No real .env files in git',
);
check(
  !tracked.some((f) => /google-services\.json$|service-account.*\.json$/.test(f)),
  'No Google service-account or services JSON in git',
);
const tokensTs = read('src/styles/tokens.ts');
const css = read('src/styles/app.css');
const tokenMismatch = [...tokensTs.matchAll(/'?([a-z0-9-]+)'?:\s*'(#[0-9a-f]{6})'/g)].filter(
  ([, name, hex]) => !css.toLowerCase().includes(`--color-${name}: ${hex}`),
);
check(
  tokenMismatch.length === 0,
  'Colour tokens in CSS match tested tokens.ts',
  tokenMismatch.map((m) => m[1]).join(', '),
);

// ---------------------------------------------------------------- report
const OWNER_ATTESTATIONS = [
  'Owner confirmed application ID com.zenfracta.bluffclub (permanent after first upload)',
  'Name/trademark clearance for "Bluff Club" checked by the owner',
  'Legal developer identity, address and Play Console account verification complete',
  'Privacy policy reviewed by the owner/counsel and hosted at the configured URL',
  'Play Console Data safety form completed from docs/DATA_SAFETY_NOTES.md and reviewed',
  'Content rating questionnaire (IARC) and target audience/age settings completed',
  'Four packs + bundle created as one-time products in Play Console and activated',
  'RevenueCat products configured as NON-CONSUMABLE; entitlements attached exactly per docs/BILLING_SETUP.md',
  'RevenueCat restore/transfer behaviour reviewed (default: Transfer to new App User ID)',
  'Prices set per market in Play Console (hypothesis: R39 per pack, R99 bundle in ZA)',
  'Closed test requirement for new personal accounts met if applicable (12 testers, 14 days)',
  'Human editorial review and playtest of all pack content',
  'Upload key created, stored outside git, Play App Signing enrolled',
];
const DEVICE_TESTS = [
  'First launch in airplane mode: free game playable',
  'Paid pack playable offline after a confirmed purchase (cached CustomerInfo)',
  'Native back gesture/button in every phase',
  'Background/foreground mid-reveal and mid-vote',
  'Rotation and tablet layout',
  'Process death and recovery (Developer options → Don’t keep activities)',
  'Recent-apps preview blanked; screenshots blocked during rounds',
  'Keyboard, TalkBack and system font size (incl. 200%)',
  'Purchase cancellation, pending purchase, external banking verification',
  'Reinstall + restore with the same Play account',
  'Refund/revocation removes access after refresh',
  'Real billing with Play licence testers and configured products',
];

const failed = results.filter((r) => !r.ok);
console.log('\nStatic release checks');
for (const r of results)
  console.log(`  ${r.ok ? '✓' : '✗'} ${r.label}${!r.ok && r.detail ? `  — ${r.detail}` : ''}`);
console.log('\nOwner attestations (cannot be verified by a script — confirm manually):');
for (const a of OWNER_ATTESTATIONS) console.log(`  ☐ ${a}`);
console.log(
  '\nReal-device and console tests (NOT EXECUTED by this script; browser/mocks do not count):',
);
for (const t of DEVICE_TESTS) console.log(`  ☐ ${t}`);
console.log('');
if (failed.length > 0) {
  console.error(`✗ release:check failed: ${failed.length} machine-detectable blocker(s).`);
  process.exit(1);
}
console.log('✓ No machine-detectable blockers. Release still requires every unchecked item above.');
