import { defineConfig, devices } from '@playwright/test';

// Most CI tests exercise the shipped bundle. The session/passkey suite imports
// the actual source transport to drive cross-tab races, so it keeps a source server.
const usePrebuilt = process.env.PLAYWRIGHT_USE_PREBUILT === '1';
const sourceServer = {
  command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
  url: 'http://127.0.0.1:4173/login',
  reuseExistingServer: !process.env.CI && !usePrebuilt,
  timeout: 120_000,
};

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
  projects: usePrebuilt ? [
    {
      name: 'production-bundle',
      testIgnore: '**/security-sessions-passkeys.spec.ts',
      use: { baseURL: 'http://127.0.0.1:4174' },
    },
    {
      name: 'source-security',
      testMatch: '**/security-sessions-passkeys.spec.ts',
    },
  ] : undefined,
  webServer: usePrebuilt ? [
    sourceServer,
    {
      command: 'npm run preview -- --host 127.0.0.1 --port 4174 --strictPort',
      url: 'http://127.0.0.1:4174/login',
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ] : sourceServer,
  outputDir: 'test-results/playwright',
});
