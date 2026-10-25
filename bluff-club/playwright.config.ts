import { defineConfig, devices } from '@playwright/test';

// The demo project drives the dev server in `demo` mode (mock store enabled).
// The prod project drives a real production build to prove the mock store is excluded.
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  retries: 0,
  reporter: [['list']],
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'demo',
      testIgnore: /prod\..*\.spec\.ts/,
      use: { ...devices['Pixel 7'], baseURL: 'http://localhost:5199' },
    },
    {
      name: 'prod',
      testMatch: /prod\..*\.spec\.ts/,
      use: { ...devices['Pixel 7'], baseURL: 'http://localhost:4199' },
    },
  ],
  webServer: [
    {
      command: 'pnpm exec vite --mode demo --port 5199 --strictPort',
      url: 'http://localhost:5199',
      reuseExistingServer: !process.env.CI,
    },
    {
      command:
        'pnpm exec vite build --outDir dist-e2e-prod && pnpm exec vite preview --outDir dist-e2e-prod --port 4199 --strictPort',
      url: 'http://localhost:4199',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
