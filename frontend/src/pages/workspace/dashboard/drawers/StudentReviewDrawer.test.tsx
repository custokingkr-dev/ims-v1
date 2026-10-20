import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StudentReviewDrawer } from './StudentReviewDrawer';
import { fetchCampaignItems, fetchIdCardReviewStatus } from '../../../../api/dashboardCommandCenterApi';

vi.mock('../../../../api/dashboardCommandCenterApi');
vi.mock('../../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
const auth = vi.hoisted(() => ({ user: { branchId: 10 } as { branchId: number } | null }));
vi.mock('../../../../contexts/AuthContext', () => ({ useAuth: () => auth }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe('student review recovery', () => {
  it('surfaces a failed item fetch and retries without losing the campaign', async () => {
    vi.mocked(fetchIdCardReviewStatus).mockResolvedValue({ campaignId: 'campaign-1', totalStudents: 2, completed: 0, pending: 2, completionPercent: 0 } as never);
    vi.mocked(fetchCampaignItems).mockRejectedValueOnce(new Error('Network unavailable')).mockResolvedValueOnce({ content: [], totalElements: 0 } as never);
    render(<StudentReviewDrawer open onClose={vi.fn()} />);
    await waitFor(() => expect(fetchIdCardReviewStatus).toHaveBeenCalledWith(10));
    expect(await screen.findByRole('alert')).toHaveTextContent('Student review items could not be loaded');
    expect(screen.getByRole('dialog', { name: 'Student Lifecycle Review' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(fetchCampaignItems).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
  it('requires a school context before sending review status requests', async () => {
    auth.user = null;
    render(<StudentReviewDrawer open onClose={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Open student reviews from a school account.');
    expect(fetchIdCardReviewStatus).not.toHaveBeenCalled();
    auth.user = { branchId: 10 };
  });
});
