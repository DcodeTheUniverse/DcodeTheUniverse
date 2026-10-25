/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, loadEnv } from 'vite';
import solid from 'vite-plugin-solid';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const mockRequested = env.VITE_ENABLE_MOCK_BILLING === 'true';
  const isTest = mode === 'test';
  if (mode === 'production' && mockRequested) {
    // Hard stop: a store build must never contain the demo store.
    throw new Error(
      'VITE_ENABLE_MOCK_BILLING=true is not allowed in a production build. Use `--mode demo` for demos.',
    );
  }
  const mockBilling = mode !== 'production' && (mockRequested || isTest);

  return {
    plugins: [solid(), tailwindcss()],
    define: {
      __MOCK_BILLING_ENABLED__: JSON.stringify(mockBilling),
      __APP_VERSION__: JSON.stringify(pkg.version),
      __BUILD_MODE__: JSON.stringify(mode),
    },
    build: {
      target: 'es2022',
      sourcemap: false,
    },
    server: { port: 5173, strictPort: false },
    resolve: { conditions: ['development', 'browser'] },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./tests/setup.ts'],
      include: ['tests/unit/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  };
});
