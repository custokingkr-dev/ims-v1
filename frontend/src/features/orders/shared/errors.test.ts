import { describe, expect, it } from 'vitest';
import { NOT_UNDERSTOOD, WORKFLOW_UNAVAILABLE, httpStatus, parseList, workflowError } from './errors';

const axiosFailure = (status: number | undefined, data?: unknown) =>
  Object.assign(new Error(`Request failed with status code ${status}`), { isAxiosError: true, response: status === undefined ? undefined : { status, data } });

describe('workflowError', () => {
  it("keeps a refusal's own words", () => {
    expect(workflowError(axiosFailure(409, { code: 'GATE_UNMET', message: 'Upload a design file first.' }))).toBe('Upload a design file first.');
  });

  it('joins field errors when the refusal has no message', () => {
    expect(workflowError(axiosFailure(400, { fieldErrors: { phone: 'Enter a phone number.', name: 'Enter a name.' } }))).toBe('Enter a phone number. Enter a name.');
  });

  it('says a failing service or a lost connection plainly, never passing on gateway text', () => {
    expect(workflowError(axiosFailure(502, { message: 'Gateway upstream error' }))).toBe(WORKFLOW_UNAVAILABLE);
    expect(workflowError(Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }))).toBe(WORKFLOW_UNAVAILABLE);
  });

  it("keeps the screen's own message for a response it could not read, else the fallback", () => {
    expect(workflowError(new Error(NOT_UNDERSTOOD))).toBe(NOT_UNDERSTOOD);
    expect(workflowError({ response: { status: 404, data: {} } }, 'The order could not be found.')).toBe('The order could not be found.');
  });
});

describe('parseList and httpStatus', () => {
  it('accepts only a list', () => {
    expect(parseList([1, 2])).toEqual([1, 2]);
    expect(() => parseList({ items: [] })).toThrow(NOT_UNDERSTOOD);
    expect(() => parseList(null)).toThrow(NOT_UNDERSTOOD);
  });

  it('reads the status of a failed request, if it has one', () => {
    expect(httpStatus(axiosFailure(404))).toBe(404);
    expect(httpStatus(new Error('offline'))).toBeUndefined();
  });
});
