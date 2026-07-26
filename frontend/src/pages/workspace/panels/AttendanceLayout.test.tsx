import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { AttendanceAbsenteePanel } from './AttendanceAbsenteePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ user: { role: 'ADMIN' } }),
}));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({ can: (code: string) => code === 'attendance:manage' }),
}));

describe('Attendance control layouts', () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') return Promise.resolve({ data: [] });
      if (url === '/attendance/exceptions') {
        return Promise.resolve({
          data: {
            students: [],
            totalExceptions: 0,
            absentCount: 0,
            lateCount: 0,
            leaveCount: 0,
            queuedCount: 0,
          },
        });
      }
      if (url === '/attendance/report/summary') {
        return Promise.resolve({
          data: {
            from: '2026-07-01',
            to: '2026-07-26',
            overall: {
              presentCount: 0,
              lateCount: 0,
              leaveCount: 0,
              absentCount: 0,
              presentPercent: 0,
            },
            sections: [],
          },
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('keeps exception filters and notification actions in separate grid areas', () => {
    const { container } = render(<AttendanceAbsenteePanel />);
    const toolbar = container.querySelector('.ck-att-exception-toolbar');

    expect(toolbar).not.toBeNull();
    expect(toolbar?.querySelector(':scope > .ck-att-exception-fields')).not.toBeNull();
    expect(toolbar?.querySelector(':scope > .ck-att-exception-actions')).not.toBeNull();
  });

  it('keeps report filters and export actions in separate grid areas', () => {
    const { container } = render(<AttendanceReportsPanel />);
    const filters = container.querySelector('.ck-att-report-filters');

    expect(filters).not.toBeNull();
    expect(filters?.querySelector(':scope > .ck-att-report-filter-fields')).not.toBeNull();
    expect(filters?.querySelector(':scope > .ck-att-report-actions')).not.toBeNull();
  });
});
