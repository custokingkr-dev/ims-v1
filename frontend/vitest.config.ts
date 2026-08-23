import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    // jsdom gives us a browser-like environment for React component tests.
    environment: 'jsdom',
    // Import @testing-library/jest-dom matchers globally (toBeInTheDocument, etc.)
    setupFiles: ['./src/test/setup.ts'],
    // Glob patterns for test files — *.test.ts / *.test.tsx everywhere in src/
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // Coverage via v8 (built into Node; no extra binary required)
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/test/**',
        'src/**/*.d.ts',
        'src/vite-env.d.ts',
        'src/main.tsx',      // entry point — nothing to unit-test
      ],
      thresholds: {
        // Floors track the measured suite baseline and prevent silent regression while
        // browser coverage protects the auth/workspace shell behavior separately.
        statements: 30,
        branches: 60,
        functions: 40,
        lines: 30,
      },
    },
    // Silence noisy console output during test runs
    silent: false,
  },
});
