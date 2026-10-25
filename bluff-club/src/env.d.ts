/// <reference types="vite/client" />

declare const __MOCK_BILLING_ENABLED__: boolean;
declare const __APP_VERSION__: string;
declare const __BUILD_MODE__: string;

interface ImportMetaEnv {
  readonly VITE_REVENUECAT_ANDROID_API_KEY?: string;
  readonly VITE_SUPPORT_EMAIL?: string;
  readonly VITE_PRIVACY_POLICY_URL?: string;
  readonly VITE_ENABLE_MOCK_BILLING?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
