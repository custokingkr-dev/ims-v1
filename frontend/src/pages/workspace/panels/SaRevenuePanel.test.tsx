import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { SaRevenuePanel } from './SaRevenuePanel';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn() } }));
afterEach(() => { vi.clearAllMocks(); cleanup(); });

// /sa/invoices/stats is a deprecated compatibility alias; the canonical route is billing's own.
it('reads invoice statistics from the canonical billing route', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: { sentThisMonth: 4, paid: 3, pending: 1, totalInvoiced: 125000, periodStart: '2026-11-01', periodEndExclusive: '2026-12-01', reportingTimeZone: 'Asia/Kolkata' } });
  render(<SaRevenuePanel />);
  expect(await screen.findByText('Issued this month')).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledWith('/billing/sa/invoices/stats');
  expect(api.get).not.toHaveBeenCalledWith('/sa/invoices/stats');
});
