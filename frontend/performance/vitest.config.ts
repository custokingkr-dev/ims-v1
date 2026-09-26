import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Explicit local baseline harness; excluded from the normal src test suite.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['performance/spreadsheet-baseline.test.tsx'],
    maxWorkers: 1,
    testTimeout: 60000,
  },
});
