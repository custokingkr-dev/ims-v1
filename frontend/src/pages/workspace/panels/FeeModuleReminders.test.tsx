import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { FeeModulePanel } from './FeeModulePanel';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { userId: 9, role: 'ADMIN', branchId: 7 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true, canAny: () => true }) }));

const NOTE = "Reminders can't be sent yet: parent messaging isn't switched on.";

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(api.get).mockImplementation(async (url) => {
    if (url === '/academic-years') return { data: [{ id: '2026-27', label: '2026–27', active: true }] };
    if (url === '/classes') return { data: [{ id: 'c1', name: 'Class 5' }] };
    if (url === '/classes/c1/sections') return { data: [{ id: 's1', name: 'Section A' }] };
    if (url === '/fees/reports/collection') {
      return { data: { content: [{ studentId: 1, studentName: 'Asha Overdue', totalFee: 500000, paidPaise: 0, dueAmountPaise: 500000, status: 'OVERDUE' }] } };
    }
    if (url === '/fees/structure') return { data: { bands: [], academicYearId: '2026-27', academicYear: '2026-27' } };
    return { data: [] };
  });
});
afterEach(cleanup);

// The fee-reminder endpoints answer 409 FEE_REMINDERS_UNAVAILABLE because parent messaging is not live; the panel
// used to say "N overdue reminders queued." for reminders nothing ever sent.
it('keeps Remind overdue visible but disabled, says why, and never asks the server to send', async () => {
  render(<FeeModulePanel workspace={null} onRefresh={vi.fn()} />);

  const remind = await screen.findByRole('button', { name: /Remind overdue/ }, { timeout: 3000 });
  expect(remind).toBeDisabled();
  expect(remind).toHaveAccessibleDescription(NOTE);
  expect(screen.getByText(NOTE)).toBeVisible();

  await screen.findByRole('option', { name: 'Class 5' });
  fireEvent.change(screen.getByLabelText('Class'), { target: { value: 'c1' } });
  await screen.findByRole('option', { name: 'Section A' });
  fireEvent.change(screen.getByLabelText('Section'), { target: { value: 's1' } });
  expect(await screen.findByText('Asha Overdue')).toBeInTheDocument();

  // Overdue students on screen do not enable it.
  expect(screen.getByRole('button', { name: /Remind overdue/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: /Remind overdue/ }));
  expect(api.post).not.toHaveBeenCalled();
  expect(vi.mocked(api.get).mock.calls.map(([url]) => url)).not.toContain('/fees/reports/overdue');
  expect(screen.queryByText(/queued/)).not.toBeInTheDocument();
  // The panel's first render is heavy and this walks a class and section; a loaded parallel run outlasts 5 s.
}, 15000);
