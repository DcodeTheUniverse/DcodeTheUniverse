import type { CapacitorConfig } from '@capacitor/cli';

// Display name and application ID are centralised in src/app/brand.ts as well.
// The application ID is a PROPOSED value: confirm with the owner before the first Play upload,
// because it can never change after publication.
const config: CapacitorConfig = {
  appId: 'com.zenfracta.bluffclub',
  appName: 'Bluff Club',
  webDir: 'dist',
  android: {
    // Debuggable WebView only in debug builds (Capacitor default follows the build type);
    // stated explicitly so a release build never exposes it.
    webContentsDebuggingEnabled: false,
    allowMixedContent: false,
  },
  plugins: {},
};

export default config;
