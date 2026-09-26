import { defineConfig, devices } from '@playwright/test';

// CI already builds dist before this suite. Opt in explicitly so local test:e2e
// continues to work without a separate build, while CI exercises the shipped bundle.
const usePrebuilt = process.env.PLAYWRIGHT_USE_PREBUILT === '1';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: usePrebuilt
      ? 'npm run preview -- --host 127.0.0.1 --port 4173 --strictPort'
      : 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173/login',
    // Never reuse a dev server when the caller requested the production bundle.
    reuseExistingServer: !process.env.CI && !usePrebuilt,
    timeout: 120_000,
  },
  outputDir: 'test-results/playwright',
});
