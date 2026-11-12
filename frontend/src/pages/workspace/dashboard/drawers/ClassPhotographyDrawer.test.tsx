import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import api from '../../../../services/api';
import { fetchClassPhotographyPaymentStatus } from '../../../../api/dashboardCommandCenterApi';
import { ClassPhotographyDrawer } from './ClassPhotographyDrawer';

vi.mock('../../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../../api/dashboardCommandCenterApi');
vi.mock('../../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

const status = {
  eventId: 'EVT-1', title: 'Class photography 2026', eventDate: '2026-11-20', totalBudget: 100000, schoolContribution: 20000,
  studentContributionTarget: 80000, collectedAmount: 30000, pendingAmount: 50000, page: 0, size: 20, totalElements: 2,
  students: [
    { studentId: 1, studentName: 'Asha', admissionNo: 'A1', className: '5', sectionName: 'A', parentPhone: '9000000001', expectedAmount: 50000, paidAmount: 0, pendingAmount: 50000, status: 'PENDING', lastReminderSentAt: null },
    { studentId: 2, studentName: 'Ravi', admissionNo: 'A2', className: '5', sectionName: 'A', parentPhone: '9000000002', expectedAmount: 30000, paidAmount: 30000, pendingAmount: 0, status: 'PAID', lastReminderSentAt: '2026-11-01T05:00:00Z' },
  ],
};

// The dashboard used to post reminders to /dashboard/events/{id}/payment-reminders, which no service
// has served since the microservice cutover, so every send failed. Nothing can send these reminders
// yet (docs/FRONTEND-AB-DECISIONS-2026-07-02.md), so the drawer says so instead of offering it.
describe('ClassPhotographyDrawer payment reminders', () => {
  it('shows the payment status and explains that reminders cannot be sent from here', async () => {
    vi.mocked(fetchClassPhotographyPaymentStatus).mockResolvedValue(status as never);
    render(<ClassPhotographyDrawer open onClose={vi.fn()} />);

    expect(await screen.findByText('Asha')).toBeInTheDocument();
    const send = screen.getByRole('button', { name: 'Send payment reminder' });
    expect(send).toBeDisabled();
    expect(send).toHaveAccessibleDescription(/isn't available yet/);
    expect(screen.queryByRole('button', { name: 'Remind' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });
});
