import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ user: { role: 'ADMIN' } }),
}));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: (code: string) => code === 'attendance:manage' }),
}));

const student = (overrides: Record<string, unknown>) => ({
  studentId: 1,
  fullName: 'Asha Rao',
  admissionNo: 'ADM1',
  rollNo: '1',
  classSection: 'Class 1-A',
  parentContact: '919999999999',
  hasContact: true,
  alreadyQueued: false,
  notificationStatus: null,
  status: 'ABSENT',
  remarks: '',
  ...overrides,
});

describe('AttendanceAbsenteePanel delivery status', () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') return Promise.resolve({ data: [] });
      if (url === '/attendance/exceptions') {
        return Promise.resolve({
          data: {
            students: [
              student({ studentId: 1, fullName: 'Asha Rao', alreadyQueued: false, notificationStatus: null }),
              student({ studentId: 2, fullName: 'Bala Iyer', alreadyQueued: true, notificationStatus: 'QUEUED' }),
              student({ studentId: 3, fullName: 'Charu Nair', alreadyQueued: true, notificationStatus: 'SENT' }),
              student({ studentId: 4, fullName: 'Dev Menon', alreadyQueued: true, notificationStatus: 'SENT_DRY_RUN' }),
              student({ studentId: 5, fullName: 'Esha Pillai', alreadyQueued: true, notificationStatus: 'FAILED' }),
              student({ studentId: 6, fullName: 'Farah Khan', alreadyQueued: true, notificationStatus: 'DEAD_LETTER' }),
              student({ studentId: 7, fullName: 'Gita Das', alreadyQueued: true, notificationStatus: 'SUPPRESSED' }),
            ],
            totalExceptions: 7,
            absentCount: 7,
            lateCount: 0,
            leaveCount: 0,
            queuedCount: 6,
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('shows the per-row delivery status so staff can see what actually happened', async () => {
    render(<AttendanceAbsenteePanel />);

    await waitFor(() => expect(screen.getByText('Asha Rao')).toBeTruthy());
    expect(screen.getByText('Ready')).toBeTruthy();
    expect(screen.getByText('Queued')).toBeTruthy();
    expect(screen.getByText('Sent')).toBeTruthy();
    expect(screen.getByText('Sent (dry run)')).toBeTruthy();
    expect(screen.getByText('Retrying')).toBeTruthy();
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getByText('Suppressed')).toBeTruthy();
  });

  it('says queued, not delivered, after notify succeeds', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { date: '2026-09-10', queued: 1, skippedNoContact: 0, skippedAlreadyQueued: 6 },
    });
    render(<AttendanceAbsenteePanel />);
    await waitFor(() => expect(screen.getByText('Asha Rao')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /Notify eligible/ }));

    await waitFor(() => expect(vi.mocked(api.post)).toHaveBeenCalledWith('/attendance/absentees/notify', expect.anything()));
    const toast = await screen.findByText(/Queued 1 for delivery/);
    expect(toast.textContent).toMatch(/background worker/i);
    expect(toast.textContent).not.toMatch(/\bsent\b/i);
    expect(toast.textContent).toMatch(/skipped 6/);
  });
});
