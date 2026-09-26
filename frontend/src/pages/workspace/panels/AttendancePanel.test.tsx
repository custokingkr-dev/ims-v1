import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { AttendancePanel } from './AttendancePanel';
import api from '../../../services/api';
import { WorkspaceDraftProvider } from '../WorkspaceDrafts';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ user: { role: 'ADMIN', permissions: ['attendance:read', 'attendance:manage'] } }),
}));
vi.mock('../../../hooks/usePermissions', () => ({
  usePermissions: () => ({
    can: (code: string) => ['attendance:read', 'attendance:manage'].includes(code),
    canAny: (codes: string[]) => codes.some((code) => ['attendance:read', 'attendance:manage'].includes(code)),
    canAll: (codes: string[]) => codes.every((code) => ['attendance:read', 'attendance:manage'].includes(code)),
  }),
}));

afterEach(cleanup);

const summary = {
  date: '2024-03-04', dateLabel: '04 Mar 2024', overallPercent: 66.7, allSubmitted: false,
  nonWorkingDay: false,
  sections: [
    { sectionId: 's1', classId: 'c1', sectionName: 'Class 1-A', totalStudents: 2, presentCount: 0,
      lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 0, teacherName: 'Ms Rao',
      status: 'Pending', locked: false },
  ],
};

const register = {
  date: '2024-03-04', classId: 'c1', sectionId: 's1', sectionName: 'Class 1-A', locked: false,
  totalStudents: 2, presentCount: 0, lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 0,
  students: [
    { studentId: 1, fullName: 'A One', admissionNo: 'ADM1', rollNo: '1', photoUrl: null, status: null, remarks: '' },
    { studentId: 2, fullName: 'B Two', admissionNo: 'ADM2', rollNo: '2', photoUrl: null, status: null, remarks: '' },
  ],
};

describe('AttendancePanel', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/attendance/daily-summary') return Promise.resolve({ data: summary });
      if (url === '/attendance/section-register') return Promise.resolve({ data: register });
      return Promise.resolve({ data: {} });
    });
    vi.mocked(api.put).mockResolvedValue({ data: register });
  });

  it('selecting a rail section loads and shows its roster', async () => {
    render(<AttendancePanel onRefresh={vi.fn().mockResolvedValue(undefined)} />);
    expect(screen.queryByText('Write access')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Class 1-A')).toBeTruthy());
    fireEvent.click(screen.getAllByText('Class 1-A')[0]);
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/attendance/section-register', {
        params: { date: expect.any(String), classId: 'c1', sectionId: 's1' },
      })
    );
    // Roster now shows the students.
    await waitFor(() => expect(screen.getByText('A One')).toBeTruthy());
  });

  it('PUTs 4-value statuses on Save', async () => {
    render(<AttendancePanel onRefresh={vi.fn().mockResolvedValue(undefined)} />);
    await waitFor(() => screen.getAllByText('Class 1-A'));
    fireEvent.click(screen.getAllByText('Class 1-A')[0]);
    await waitFor(() => screen.getByText('A One'));

    // Mark student 1 LATE via that row's L pill.
    const lateButtons = screen.getAllByRole('button', { name: 'LATE' });
    fireEvent.click(lateButtons[0]);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(api.put).toHaveBeenCalled());
    const putBody = vi.mocked(api.put).mock.calls[0][1] as { records: Array<{ studentId: number; status: string }> };
    expect(putBody.records).toContainEqual(expect.objectContaining({ studentId: 1, status: 'LATE' }));
  });

  it('retains unsaved marks when leaving and returning to the attendance panel', async () => {
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(<WorkspaceDraftProvider><AttendancePanel onRefresh={onRefresh} /></WorkspaceDraftProvider>);
    await screen.findByText('A One');
    fireEvent.click(screen.getAllByRole('button', { name: 'LATE' })[0]);
    rerender(<WorkspaceDraftProvider><p>Another panel</p></WorkspaceDraftProvider>);
    rerender(<WorkspaceDraftProvider><AttendancePanel onRefresh={onRefresh} /></WorkspaceDraftProvider>);
    await screen.findByText('A One');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.put).toHaveBeenCalled());
    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({ records: [{ studentId: 1, status: 'LATE' }] });
  });

  it('allows cancelling a date change without losing unsaved marks', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<AttendancePanel onRefresh={vi.fn()} />);
    await screen.findByText('A One');
    fireEvent.click(screen.getAllByRole('button', { name: 'LATE' })[0]);
    const date = screen.getByLabelText('Attendance date') as HTMLInputElement;
    const previousDate = date.value;
    fireEvent.change(date, { target: { value: '2026-01-01' } });
    expect(confirm).toHaveBeenCalledOnce();
    expect(date).toHaveValue(previousDate);
    expect(screen.getByText(/Unsaved attendance stays available/)).toBeInTheDocument();
    confirm.mockRestore();
  });
});
