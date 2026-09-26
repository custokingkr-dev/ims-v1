import { describe, expect, it } from 'vitest';
import { getDisplayStatus, humaniseCode } from './status';

describe('getDisplayStatus', () => {
  it('keeps the curated wording for mapped statuses', () => {
    expect(getDisplayStatus('PROCESSING')).toBe('In Fulfilment');
    expect(getDisplayStatus('AWAITING_BURSAR')).toBe('Finance Review Pending');
  });

  // An unmapped status used to render as its raw constant, so a superadmin saw
  // "PENDING_APPROVAL" and "QUOTED" sitting beside "In Fulfilment" in the same column.
  it('humanises an unmapped status instead of leaking the constant', () => {
    expect(getDisplayStatus('PENDING_APPROVAL')).toBe('Pending approval');
    expect(getDisplayStatus('QUOTED')).toBe('Quoted');
    expect(getDisplayStatus('DESIGN_APPROVED_PROCESSING')).toBe('Design approved processing');
  });

  it('never renders an empty cell as blank', () => {
    expect(getDisplayStatus('')).toBe('—');
  });
});

it('humaniseCode turns any stored constant into words', () => {
  // SUPERADMIN reached the sidebar verbatim under the user's own name, because the footer
  // only replaced underscores and that constant has none.
  expect(humaniseCode('SUPERADMIN')).toBe('Superadmin');
  expect(humaniseCode('SCHOOL_ADMIN')).toBe('School admin');
  expect(humaniseCode('')).toBe('');
  expect(humaniseCode(null)).toBe('');
});
