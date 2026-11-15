import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import api from '../../../../services/api';
import { fetchFeeDefaulters } from '../../../../api/dashboardCommandCenterApi';
import type { FeeDefaulterItem } from '../../../../types/dashboardCommandCenter';
import { FeeDefaultersDrawer } from './FeeDefaultersDrawer';

vi.mock('../../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../../api/dashboardCommandCenterApi');
vi.mock('../../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

const NOTE = "Reminders can't be sent yet: parent messaging isn't switched on.";

function defaulter(studentId: number, studentName: string): FeeDefaulterItem {
  return {
    studentId, studentName, admissionNo: `ADM-${studentId}`, className: '5', sectionName: 'A',
    parentName: 'Raj', parentPhone: '9000000001', dueAmount: 250000, dueDate: '2026-10-10', daysOverdue: 36,
    lastReminderSentAt: null, reminderStatus: 'NOT_SENT', paymentStatus: 'OVERDUE',
  };
}

// The fee-reminder endpoints answer 409 FEE_REMINDERS_UNAVAILABLE: parent messaging is not live, and the old
// send path reported "Sent undefined reminders" for a request the server ignored. The drawer keeps the list.
describe('FeeDefaultersDrawer reminders', () => {
  it('lists defaulters, explains that reminders cannot be sent and offers no send path', async () => {
    vi.mocked(fetchFeeDefaulters).mockResolvedValue({
      totalDefaulters: 1, totalOverdueAmount: 250000, oldestDueDays: 36, items: [defaulter(1, 'Asha')],
      page: 0, size: 20, totalElements: 1,
    });
    render(<FeeDefaultersDrawer open onClose={vi.fn()} />);

    expect(await screen.findByText('Asha')).toBeInTheDocument();
    expect(screen.getByText(NOTE)).toBeInTheDocument();
    const remind = screen.getByRole('button', { name: 'Remind' });
    expect(remind).toBeDisabled();
    expect(remind).toHaveAccessibleDescription(NOTE);
    fireEvent.click(remind);

    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send Bulk Reminder/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Send Fee Reminder')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Channel' })).not.toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('keeps filtering and paging the list', async () => {
    vi.mocked(fetchFeeDefaulters).mockImplementation(async ({ page = 0 } = {}) => ({
      totalDefaulters: 45, totalOverdueAmount: 9000000, oldestDueDays: 90,
      items: [defaulter(page + 1, `Student on page ${page + 1}`)], page, size: 20, totalElements: 45,
    }));
    render(<FeeDefaultersDrawer open onClose={vi.fn()} />);

    expect(await screen.findByText('Student on page 1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    expect(await screen.findByText('Student on page 2')).toBeInTheDocument();
    expect(screen.getByText('2 / 3')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('Class ID filter'), { target: { value: 'C5' } });
    await waitFor(() => expect(fetchFeeDefaulters).toHaveBeenLastCalledWith(
      expect.objectContaining({ classId: 'C5', page: 0, size: 20 })));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'FAILED' } });
    await waitFor(() => expect(fetchFeeDefaulters).toHaveBeenLastCalledWith(
      expect.objectContaining({ classId: 'C5', reminderStatus: 'FAILED', page: 0 })));
    expect(api.post).not.toHaveBeenCalled();
  });
});
