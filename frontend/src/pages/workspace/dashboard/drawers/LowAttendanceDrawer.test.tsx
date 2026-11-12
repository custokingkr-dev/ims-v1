import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import api from '../../../../services/api';
import { fetchLowAttendanceSections, fetchLowAttendanceStudents } from '../../../../api/dashboardCommandCenterApi';
import { LowAttendanceDrawer } from './LowAttendanceDrawer';

vi.mock('../../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../../api/dashboardCommandCenterApi');
vi.mock('../../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

// POST /dashboard/attendance/meeting-invites has had no handler since the microservice cutover, so
// every invite failed. Until an endpoint exists (docs/FRONTEND-AB-DECISIONS-2026-07-02.md) the drawer
// says the action is unavailable instead of offering it.
describe('LowAttendanceDrawer meeting invites', () => {
  it('lists the students and explains that invites cannot be sent from here', async () => {
    vi.mocked(fetchLowAttendanceSections).mockResolvedValue({ date: '2026-11-12', thresholdPercent: 75, sections: [
      { sectionId: 'S1', sectionName: 'A', className: '5', presentCount: 20, totalEnrolled: 30, attendancePct: 66.7, studentsBelowThreshold: 1 },
    ] });
    vi.mocked(fetchLowAttendanceStudents).mockResolvedValue([
      { studentId: 1, studentName: 'Asha', admissionNo: 'A1', className: '5', sectionName: 'A', fatherName: 'Raj', fatherContact: '9000000001', attendancePercent: 60, lastInviteSentAt: null },
    ]);
    render(<LowAttendanceDrawer open onClose={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /5 – A/ }));
    expect(await screen.findByText('Asha')).toBeInTheDocument();
    const send = screen.getByRole('button', { name: 'Send meeting invites' });
    expect(send).toBeDisabled();
    expect(send).toHaveAccessibleDescription(/isn't available yet/);
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Invite/ })).not.toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });
});
