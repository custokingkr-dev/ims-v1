import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomePanel } from './HomePanel';
import api from '../../../services/api';
import type { WorkspaceData } from '../../../types/workspace';

vi.mock('../../../services/api');
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
vi.mock('../../../api/dashboardCommandCenterApi', () => ({ fetchCommandCenterMetrics: () => Promise.resolve(null) }));
vi.mock('../dashboard/components/BroadcastDrafts', () => ({ BroadcastDrafts: () => null }));
vi.mock('../dashboard/drawers/FeeDefaultersDrawer', () => ({ FeeDefaultersDrawer: () => null }));
vi.mock('../dashboard/drawers/ClassPhotographyDrawer', () => ({ ClassPhotographyDrawer: () => null }));
vi.mock('../dashboard/drawers/StudentReviewDrawer', () => ({ StudentReviewDrawer: () => null }));
vi.mock('../dashboard/drawers/LowAttendanceDrawer', () => ({ LowAttendanceDrawer: () => null }));
vi.mock('../dashboard/drawers/VendorDuesDrawer', () => ({ VendorDuesDrawer: () => null }));
vi.mock('../dashboard/drawers/ReorderSignalsDrawer', () => ({ ReorderSignalsDrawer: () => null }));

const workspace = {
  school: { name: 'Test School', meta: '2026-27' },
  dashboard: { students: 12, sections: 2, attendancePercent: 0, attendancePresent: 0, attendanceSubmittedSections: 0, attendanceState: 'NOT_STARTED', feeCollectedLakh: 0, feeTargetLakh: 0, feesConfigured: false, feeOverdueCount: 0, firefightingActive: 0, pendingApprovals: 0 },
  recentActivity: [], staff: [], annualPlan: { terms: [] }, orders: [], firefighting: { requests: [] },
} as WorkspaceData;
const action = { id: '12345', module: 'fees', urgency: 'HIGH', title: 'Review overdue accounts', reason: 'Two balances are overdue.', impact: 'Two accounts', currentState: 'OVERDUE', targetState: 'REVIEWED', ctaLabel: 'Send reminders', confidence: 99, sourceType: 'FEE_ACCOUNT', sourceId: 'fees-123', createdAt: '2026-09-25T12:00:00Z' };

describe('HomePanel source-backed suggestions', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url === '/reporting/command-center/actions' ? [action] : url === '/reporting/command-center/summary' ? { summary: 'Daily account review', recommendedNextStep: '' } : [] }));
  });
  afterEach(cleanup);
  const view = (setPanel = vi.fn()) => render(<HomePanel workspace={workspace} setPanel={setPanel} moduleAccess={{ erp: true, supplyOs: true }} />);

  it('opens review without accepting or claiming execution', async () => {
    const setPanel = vi.fn(); view(setPanel);
    const article = (await screen.findByRole('heading', { name: action.title })).closest('article')!;
    fireEvent.click(within(article).getByRole('button', { name: /Review fees/i }));
    expect(setPanel).toHaveBeenCalledWith('fees');
    expect(api.post).not.toHaveBeenCalled();
    expect(article).toBeInTheDocument();
    expect(screen.queryByText(/executed|dispatched|AI · RANKED/i)).not.toBeInTheDocument();
  });

  it('retains an open suggestion after a failed acknowledgement and confirms only after retry succeeds', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: { status: 'ACCEPTED' } });
    view();
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge suggestion' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Acknowledgement was not saved');
    expect(screen.getByRole('heading', { name: action.title })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge suggestion' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: action.title })).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent('Complete the work in its module');
  });

  it('shows the actual reason, source and recorded time when Why this is expanded', async () => {
    view();
    const why = await screen.findByRole('button', { name: 'Why this?' });
    expect(why).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(why);
    expect(why).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/fee account · fees-123/)).toBeVisible();
    expect(screen.getByText(new Date(action.createdAt).toLocaleString(), { exact: false })).toBeVisible();
    expect(screen.queryByText('99')).not.toBeInTheDocument();
    expect(screen.getByText(/does not complete its task/)).toBeVisible();
  });

  it('hides workspace-derived suggestions locally without posting fabricated IDs', async () => {
    vi.mocked(api.get).mockImplementation(url => url === '/reporting/command-center/actions' ? Promise.reject(new Error('offline')) : Promise.resolve({ data: url === '/reporting/command-center/summary' ? { summary: '', recommendedNextStep: '' } : [] }));
    render(<HomePanel workspace={{ ...workspace, dashboard: { ...workspace.dashboard, feeOverdueCount: 2 } }} setPanel={vi.fn()} moduleAccess={{ erp: true, supplyOs: true }} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Hide for this view' }));
    expect(api.post).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toHaveTextContent('hidden for this view');
  });
});
