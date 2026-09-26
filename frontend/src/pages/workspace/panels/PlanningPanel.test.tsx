import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningPanel } from './PlanningPanel';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import type { WorkspaceData } from '../config';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext');
const item = { id: 'saved-item', term: 'Term 1', category: 'Stationery', description: 'Exercise books', quantity: '12', estimatedAmount: 120, status: 'PLANNED' };
const review = { schoolId: 10, academicYearId: 'ay_2026_27', yearLabel: '2026-27', items: [item], fingerprint: 'exact-reviewed-content', confirmation: null };
const confirmation = { confirmed: true, id: 'confirmation-1', schoolId: 10, academicYearId: review.academicYearId, fingerprint: review.fingerprint, revision: 1, confirmedAt: '2026-09-26T10:00:00Z', itemCount: 1, notificationStatus: 'NOT_SENT' };
const workspace = { school: { name: 'School A' } } as WorkspaceData;
function auth(role = 'SCHOOLADMIN', permissions = ['plan:read', 'plan:manage']) {
  vi.mocked(useAuth).mockReturnValue({ user: { role, branchId: 10, permissions } } as ReturnType<typeof useAuth>);
}
function renderPanel(onRefresh = vi.fn().mockResolvedValue(undefined)) {
  render(<PlanningPanel workspace={workspace} onRefresh={onRefresh} />); return onRefresh;
}

describe('Annual plan persisted confirmation', () => {
  beforeEach(() => { vi.resetAllMocks(); auth(); vi.mocked(api.get).mockResolvedValue({ data: review }); });
  afterEach(cleanup);

  it('confirms exactly the reviewed school/year fingerprint without claiming notification', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: confirmation });
    const refresh = renderPanel();
    await screen.findByRole('table');
    expect(screen.getByRole('caption')).toHaveTextContent('2026-27');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm current plan' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Plan confirmation saved as revision 1. No staff or provider notification has been sent.');
    expect(api.post).toHaveBeenCalledWith('/catalog/annual-plan/confirm', { fingerprint: review.fingerprint }, { params: { schoolId: 10 } });
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('reconciles a lost confirmation response before allowing another attempt', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('response lost after commit'));
    renderPanel(); await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm current plan' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Refresh the plan to check for a saved confirmation or changed items');
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    vi.mocked(api.get).mockResolvedValue({ data: { ...review, confirmation } });
    fireEvent.click(screen.getByRole('button', { name: 'Refresh plan' }));
    expect(await screen.findByText(/Current plan confirmed as revision 1/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('rejects an old placeholder success and a confirmation from another school', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ok: true, confirmed: true, notified: 'Staff notified' } })
      .mockResolvedValueOnce({ data: { ...confirmation, schoolId: 20 } });
    renderPanel(); await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm current plan' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be confirmed');
    expect(screen.queryByText(/Plan confirmation saved/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh plan' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Confirm current plan' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be confirmed');
    expect(screen.queryByText(/Plan confirmation saved/)).not.toBeInTheDocument();
  });

  it('fails closed for malformed review and empty plan, then recovers', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: {} }).mockResolvedValueOnce({ data: { ...review, items: [] } }).mockResolvedValue({ data: review });
    renderPanel();
    expect(await screen.findByRole('alert')).toHaveTextContent('current plan could not be loaded');
    expect(screen.getByRole('button', { name: 'Add item' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh plan' }));
    await screen.findByText(/No items saved for this academic year/);
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh plan' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeEnabled());
    expect(api.post).not.toHaveBeenCalled();
  });

  it('keeps the saved confirmation when workspace refresh fails', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: confirmation });
    renderPanel(vi.fn().mockRejectedValue(new Error('workspace offline'))); await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm current plan' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Plan confirmation is saved as revision 1. The workspace could not refresh');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('requires explicit platform school scope and reuses an uncertain item reference on retry', async () => {
    auth('SUPERADMIN');
    vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url === '/schools' ? [{ id: 10, name: 'School A' }, { id: 20, name: 'School B' }] : review }));
    vi.mocked(api.post).mockRejectedValueOnce(new Error('response lost')).mockImplementation((_url, body) => Promise.resolve({ data: { ...(body as Record<string, unknown>), schoolId: 10 } }));
    renderPanel();
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    await screen.findByRole('option', { name: 'School A' });
    fireEvent.change(screen.getByLabelText('School'), { target: { value: '10' } });
    await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    fireEvent.change(screen.getByLabelText('Category (required)'), { target: { value: 'Uniforms' } });
    fireEvent.change(screen.getByLabelText('Estimated amount (required)'), { target: { value: '250' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save item' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('same item reference');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByLabelText('School')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Confirm current plan' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save item' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const calls = vi.mocked(api.post).mock.calls;
    const first = calls[0][1] as { id: string }, retry = calls[1][1] as { id: string };
    expect(first.id).toEqual(retry.id);
    expect(first.id).toMatch(/^[a-z0-9-]+$/);
    expect(calls[1][2]).toEqual({ params: { schoolId: 10 } });
    expect(screen.getByLabelText('School')).toBeEnabled();
  });

  it('shows read-only users the actual plan without write controls', async () => {
    auth('SCHOOLADMIN', ['plan:read']); renderPanel(); await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Add item' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm current plan' })).not.toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });
});
