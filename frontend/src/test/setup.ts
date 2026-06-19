/**
 * Global Vitest setup — runs once before every test file.
 *
 * Imports @testing-library/jest-dom so all test files get the extra matchers
 * (toBeInTheDocument, toHaveTextContent, toBeDisabled, etc.) without having
 * to import them individually.
 */
// Use the Vitest-specific entry point that calls `expect.extend()` with
// Vitest's expect rather than the Jest global (which doesn't exist here).
import '@testing-library/jest-dom/vitest';
