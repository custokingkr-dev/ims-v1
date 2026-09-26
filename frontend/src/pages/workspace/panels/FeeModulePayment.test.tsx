import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { prepareFeePayment, readPendingFeePayment } from '../../../services/feeApi';
import { FeeModulePanel } from './FeeModulePanel';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { userId: 9, role: 'ADMIN', branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true, canAny: () => true }) }));

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(api.get).mockImplementation(async (url) => {
    if (url === '/academic-years') return { data: [{ id: '2026-27', label: '2026–27', active: true }] };
    if (url === '/fees/structure') return { data: { bands: [], academicYearId: '2026-27', academicYear: '2026-27' } };
    if (url === '/fees/structure/health') return { data: { blockingIssues: 0, ready: true } };
    return { data: [] };
  });
});
afterEach(cleanup);

it('offers a recoverable pending payment after remount and distinguishes a saved payment from a failed refresh', async () => {
  const pending = prepareFeePayment('9:7', { studentId: 1, assignmentId: 'original-assignment', academicYearId: '2024-25', schoolId: 7, amount: 5000, mode: 'Cash', notes: '' }, 'Asha Student');
  vi.mocked(api.post).mockRejectedValueOnce(new Error('Connection lost'));
  const first = render(<FeeModulePanel workspace={null} onRefresh={vi.fn()} />);
  const retry = await screen.findByRole('button', { name: 'Retry payment confirmation' });
  fireEvent.click(retry);
  await screen.findByText('Connection lost');
  expect(readPendingFeePayment('9:7')?.request).toEqual(pending.request);
  first.unmount();

  vi.mocked(api.post).mockResolvedValueOnce({ data: { paymentId: 'p1', receiptNumber: 'RCPT-V2-100' } });
  render(<FeeModulePanel workspace={null} onRefresh={vi.fn().mockRejectedValue(new Error('Refresh failed'))} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Retry payment confirmation' }));
  await screen.findByText('Payment recorded for Student #1. Receipt RCPT-V2-100.');
  await screen.findByText('Payment is recorded. The ledger could not refresh; reload it to see the updated balance.');
  await waitFor(() => expect(readPendingFeePayment('9:7')).toBeNull());
  expect(api.post).toHaveBeenNthCalledWith(1, '/fees/payments', pending.request);
  expect(api.post).toHaveBeenNthCalledWith(2, '/fees/payments', pending.request);
});

it('keeps the fee workspace usable but requires ledger review before clearing corrupt recovery data', async () => {
  localStorage.setItem('ck_fee_payment_pending:9:7', '{broken');
  render(<FeeModulePanel workspace={null} onRefresh={vi.fn()} />);
  await screen.findByText(/The saved collection could not be read/);
  const clear = screen.getByRole('button', { name: 'Clear reviewed recovery entry' });
  expect(clear).toBeDisabled();
  expect(localStorage.getItem('ck_fee_payment_pending:9:7')).toBe('{broken');
  fireEvent.click(screen.getByRole('checkbox', { name: 'I have checked whether this collection is already recorded.' }));
  fireEvent.click(clear);
  expect(localStorage.getItem('ck_fee_payment_pending:9:7')).toBeNull();
  expect(api.post).not.toHaveBeenCalled();
});
