import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { prepareFeePayment, confirmFeePayment, readPendingFeePayment, readFeePaymentRecovery, getFeeCollectionRows, getFeeOverdueRows } from './feeApi';

vi.mock('./api', () => ({ default: { post: vi.fn(), get: vi.fn() } }));

const body = { studentId: 42, assignmentId: 'assignment-old', academicYearId: '2024-25', amount: 12500, mode: 'Cash', notes: '', schoolId: 7 };

beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); });

describe('fee collection recovery', () => {
  it('adapts canonical report envelopes to the existing row-array boundary', async () => {
    const rows = [{ studentId: 42, assignmentId: 'historical', academicYearId: '2024-25' }];
    vi.mocked(api.get).mockResolvedValue({ data: { content: rows } });
    const parameters = { classId: 'c1', sectionId: 's1', schoolId: 7, academicYearId: '2024-25' };
    await expect(getFeeCollectionRows(parameters)).resolves.toEqual({ data: rows });
    await expect(getFeeOverdueRows(parameters)).resolves.toEqual({ data: rows });
    expect(api.get).toHaveBeenNthCalledWith(1, '/fees/reports/collection', { params: parameters });
    expect(api.get).toHaveBeenNthCalledWith(2, '/fees/reports/overdue', { params: parameters });
  });
  it('persists the original request before sending and reuses its timestamp and key after a lost response', async () => {
    const attempt = prepareFeePayment('user-1:school-7', body, 'Test Student');
    vi.mocked(api.post).mockRejectedValueOnce(new Error('Connection lost'));
    await expect(confirmFeePayment('user-1:school-7', attempt)).rejects.toThrow('Connection lost');

    const restored = readPendingFeePayment('user-1:school-7')!;
    expect(restored.request).toEqual(attempt.request);
    expect(restored.studentName).toBe('Student #42');
    expect(localStorage.getItem('ck_fee_payment_pending:user-1:school-7')).not.toContain('Test Student');
    vi.mocked(api.post).mockResolvedValueOnce({ data: { paymentId: 'original-payment', receiptNumber: 'RCPT-V2-5' } });
    await confirmFeePayment('user-1:school-7', restored);
    expect(api.post).toHaveBeenNthCalledWith(1, '/fees/payments', attempt.request);
    expect(api.post).toHaveBeenNthCalledWith(2, '/fees/payments', attempt.request);
    expect(readPendingFeePayment('user-1:school-7')).toBeNull();
  });

  it('does not replace an uncertain collection or reuse it for another user or school', () => {
    const original = prepareFeePayment('user-1:school-7', body, 'Student');
    expect(() => prepareFeePayment('user-1:school-7', { ...body, amount: 13000 }, 'Student')).toThrow('Confirm the pending payment');
    expect(readPendingFeePayment('user-1:school-7')?.request).toEqual(original.request);
    expect(readPendingFeePayment('user-2:school-7')).toBeNull();
    expect(readPendingFeePayment('user-1:school-8')).toBeNull();
  });

  it('retains the key after validation rejection or conflict until the collection is reviewed', async () => {
    const first = prepareFeePayment('scope', body, 'Student');
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 400 } });
    await expect(confirmFeePayment('scope', first)).rejects.toBeDefined();
    expect(readPendingFeePayment('scope')?.request).toEqual(first.request);
    expect(() => prepareFeePayment('scope', { ...body, amount: 10000 }, 'Student')).toThrow('Confirm the pending payment');
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 409 } });
    await expect(confirmFeePayment('scope', first)).rejects.toBeDefined();
    expect(readPendingFeePayment('scope')?.request).toEqual(first.request);
  });

  it('does not send anything if the pending request cannot be stored durably', () => {
    const denied = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable'); });
    expect(() => prepareFeePayment('scope', body, 'Student')).toThrow('Allow browser storage');
    expect(api.post).not.toHaveBeenCalled();
    denied.mockRestore();
  });

  it('reuses the original key after a lost response followed by a permission rejection', async () => {
    const original = prepareFeePayment('scope', body, 'Student');
    vi.mocked(api.post).mockRejectedValueOnce(new Error('Lost response'))
      .mockRejectedValueOnce({ response: { status: 403 } })
      .mockResolvedValueOnce({ data: { paymentId: 'p1', receiptNumber: 'RCPT-V2-1' } });
    await expect(confirmFeePayment('scope', original)).rejects.toBeDefined();
    await expect(confirmFeePayment('scope', readPendingFeePayment('scope')!)).rejects.toBeDefined();
    expect(readPendingFeePayment('scope')?.request).toEqual(original.request);
    await confirmFeePayment('scope', readPendingFeePayment('scope')!);
    for (const call of vi.mocked(api.post).mock.calls) expect(call[1]).toEqual(original.request);
  });

  it('reports confirmed success even if clearing its saved recovery entry fails', async () => {
    const original = prepareFeePayment('scope', body, 'Student');
    vi.mocked(api.post).mockResolvedValueOnce({ data: { paymentId: 'p1', receiptNumber: 'RCPT-V2-1' } });
    const denied = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('Denied'); });
    try {
      const result = await confirmFeePayment('scope', original);
      expect(result.data.receiptNumber).toBe('RCPT-V2-1');
      expect(result.recoveryWarning).toContain('Payment is recorded');
      expect(readPendingFeePayment('scope')?.request).toEqual(original.request);
    } finally { denied.mockRestore(); }
  });

  it.each(['{broken', JSON.stringify({ request: { amount: 10 } })])('blocks malformed saved recovery without deleting it: %s', (saved) => {
    localStorage.setItem('ck_fee_payment_pending:scope', saved);
    expect(readFeePaymentRecovery('scope')).toEqual({ pending: null, error: expect.stringContaining('Check the student ledger') });
    expect(() => prepareFeePayment('scope', body, 'Student')).toThrow('Check the student ledger');
    expect(localStorage.getItem('ck_fee_payment_pending:scope')).toBe(saved);
    expect(api.post).not.toHaveBeenCalled();
  });
});
