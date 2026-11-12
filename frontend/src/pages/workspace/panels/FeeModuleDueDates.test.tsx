import { cleanup, render, screen } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { FeeModulePanel } from './FeeModulePanel';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { userId: 9, role: 'ADMIN', branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true, canAny: () => true }) }));

const draftBand = {
  id: 'band-1', name: 'Primary', classFrom: 1, classTo: 5, discount: 0, annualTotal: 0, activeSchedules: ['Annual'], items: [],
  installments: [], assignmentCount: 0, academicYearId: '2026-27', academicYear: '2026-27', status: 'DRAFT', revision: 1,
  gracePeriodDays: 7, lateFeeType: 'NONE', lateFeeAmount: 0, lateFeeIntervalDays: 0,
};

// The school works in IST, where the first five and a half hours of every day are still the previous day in UTC.
beforeAll(() => { vi.stubEnv('TZ', 'Asia/Kolkata'); });
afterAll(() => { vi.unstubAllEnvs(); });

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.mocked(api.get).mockImplementation(async (url) => {
    if (url === '/academic-years') return { data: [{ id: '2026-27', label: '2026–27', active: true }] };
    if (url === '/fees/structure') return { data: { bands: [draftBand], academicYearId: '2026-27', academicYear: '2026-27' } };
    return { data: [] };
  });
});
afterEach(() => { vi.useRealTimers(); cleanup(); });

it('defaults installment due dates to the 10th of the local month, not of the UTC one', async () => {
  // 01:30 IST on 13 November, which is still 12 November in UTC.
  vi.setSystemTime(new Date('2026-11-12T20:00:00Z'));
  render(<FeeModulePanel workspace={null} onRefresh={vi.fn()} initialView="configuration" />);
  // The panel's first render is heavy; under a loaded parallel run it outlasts the default 1 s wait.
  expect(await screen.findByLabelText('Term 1 due date', {}, { timeout: 3000 })).toHaveValue('2026-11-10');
  expect(screen.getByLabelText('Term 2 due date')).toHaveValue('2027-02-10');
  expect(screen.getByLabelText('Term 3 due date')).toHaveValue('2027-05-10');
});

it('keeps each default due date in its own month when today is the 31st', async () => {
  // 01:30 IST on 31 August: moving the 31st forward three months overflowed into December.
  vi.setSystemTime(new Date('2026-08-30T20:00:00Z'));
  render(<FeeModulePanel workspace={null} onRefresh={vi.fn()} initialView="configuration" />);
  expect(await screen.findByLabelText('Term 1 due date', {}, { timeout: 3000 })).toHaveValue('2026-08-10');
  expect(screen.getByLabelText('Term 2 due date')).toHaveValue('2026-11-10');
  expect(screen.getByLabelText('Term 3 due date')).toHaveValue('2027-02-10');
});
