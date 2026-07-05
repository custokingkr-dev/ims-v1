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

// jsdom does not implement Blob/File#arrayBuffer() (see jsdom/jsdom#2555).
// Polyfill it via FileReader (which jsdom does support) so components that
// call `file.arrayBuffer()` — e.g. SheetJS-based parsers — work under test.
if (typeof File !== 'undefined' && !File.prototype.arrayBuffer) {
  File.prototype.arrayBuffer = function arrayBuffer(this: File): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(this);
    });
  };
}
