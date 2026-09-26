import { describe, expect, it } from 'vitest';
import { userFacingError } from './errorMessage';

const FALLBACK = 'Unable to prepare the student export.';

describe('userFacingError', () => {
  it('never shows the reader a thrown exception', () => {
    // This exact string reached the Student data export panel, in red, as its error state.
    const bug = new TypeError("Cannot read properties of undefined (reading 'length')");
    expect(userFacingError(bug, FALLBACK)).toBe(FALLBACK);
  });

  it('prefers what the server said, because that was written for a reader', () => {
    const rejected = { response: { data: { message: 'No active schools are available for export.' } } };
    expect(userFacingError(rejected, FALLBACK)).toBe('No active schools are available for export.');
  });

  it('falls back when the server said nothing usable', () => {
    expect(userFacingError({ response: { data: { message: '   ' } } }, FALLBACK)).toBe(FALLBACK);
    expect(userFacingError({ response: { data: {} } }, FALLBACK)).toBe(FALLBACK);
    expect(userFacingError(null, FALLBACK)).toBe(FALLBACK);
    expect(userFacingError({ response: { data: { message: 42 } } }, FALLBACK)).toBe(FALLBACK);
  });
});
