import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../../services/api';
import { BroadcastDrafts, type BroadcastRecord } from './BroadcastDrafts';

vi.mock('../../../../services/api');
const draft: BroadcastRecord = { id: 'draft-1', title: 'School notice', message: 'Original message', audienceType: 'ALL_PARENTS', channels: ['SMS'], module: 'fees', status: 'DRAFT', schoolId: 10, communicationCategory: 'SCHOOL_NOTICE', createdAt: '2026-09-26T09:00:00Z', sentAt: null, scheduledAt: null };
const capabilities = { canCreateDraft: true, canApprove: true, canPreview: true, canSend: false, canQueue: true, mode: 'DRY_RUN', sendUnavailableReason: 'Live delivery is unavailable: provider duplicate suppression is unverified.', queueUnavailableReason: '', supportedAudiences: ['ALL_PARENTS'], supportedChannels: ['SMS', 'EMAIL', 'WHATSAPP'], supportedCategories: ['SCHOOL_NOTICE'] };
const preview = { broadcastId: draft.id, total: 3, eligible: 1, suppressed: 1, duplicate: 1, reasons: { CONTACT_NOT_VERIFIED: 1 }, fingerprint: 'reviewed-current-policy', explanation: 'Consent and contact binding are checked again before each attempt.' };
const outcomes = { broadcastId: draft.id, status: 'DRY_RUN_COMPLETE', mode: 'DRY_RUN', total: 1, delivered: 0, counts: { DRY_RUN: 1 }, recipients: [{ studentId: 1, channel: 'SMS', status: 'DRY_RUN', reason: null, attempts: 1, nextAttemptAt: null, provider: 'logging', dryRun: true }] };
function load(records = [draft], overrides = {}) {
  vi.mocked(api.get).mockImplementation(url => Promise.resolve({ data: url.endsWith('/capabilities') ? { ...capabilities, ...overrides } : url === '/schools' ? [{ id: 10, name: 'Test school' }] : url.endsWith('/delivery-status') ? outcomes : records }));
}
function fill() {
  fireEvent.change(screen.getByLabelText('Title (required)'), { target: { value: draft.title } });
  fireEvent.change(screen.getByLabelText('Requested channel (required)'), { target: { value: 'SMS' } });
  fireEvent.change(screen.getByLabelText('Message (required)'), { target: { value: draft.message } });
}
describe('Policy-backed broadcast review and dry run', () => {
  beforeEach(() => { vi.resetAllMocks(); load(); });
  afterEach(cleanup);
  it('requires reviewed eligibility and posts the exact preview fingerprint before approval', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: preview }).mockResolvedValueOnce({ data: { ...draft, status: 'APPROVED' } });
    render(<BroadcastDrafts schoolId={10} />);
    expect(await screen.findByRole('button', { name: 'Approve recipients' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview recipients' }));
    expect(await screen.findByText('1 eligible destinations; 1 excluded; 1 shared destinations skipped.')).toBeInTheDocument();
    expect(screen.getByText('Contact not verified: 1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Approve recipients' }));
    expect(await screen.findByText('Recipients approved')).toBeInTheDocument();
    expect(api.post).toHaveBeenLastCalledWith('/notifications/broadcasts/draft-1/approve', { previewFingerprint: preview.fingerprint });
    expect(screen.getByRole('status')).toHaveTextContent('No messages have been sent');
  });
  it('does not approve zero eligible recipients', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { ...preview, eligible: 0, suppressed: 3, duplicate: 0 } });
    render(<BroadcastDrafts schoolId={10} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Preview recipients' }));
    await screen.findByText(/No eligible recipients/);
    expect(screen.getByRole('button', { name: 'Approve recipients' })).toBeDisabled();
    expect(api.post).toHaveBeenCalledTimes(1);
  });
  it('reconciles uncertain approval rather than assuming a rollback', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: preview }).mockRejectedValueOnce(new Error('response lost'));
    render(<BroadcastDrafts schoolId={10} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Preview recipients' }));
    await screen.findByText(/1 eligible destinations/);
    fireEvent.click(screen.getByRole('button', { name: 'Approve recipients' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Approval could not be confirmed');
    expect(screen.getByRole('button', { name: 'Approve recipients' })).toBeDisabled();
    load([{ ...draft, status: 'APPROVED' }]);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh drafts' }));
    expect(await screen.findByText('Recipients approved')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(2);
  });
  it('keeps processing off according to capabilities and labels checks without claiming delivery', async () => {
    load([{ ...draft, status: 'APPROVED' }], { canQueue: false, mode: 'OFF', queueUnavailableReason: 'Broadcast processing is off.' });
    render(<BroadcastDrafts schoolId={10} />);
    expect(await screen.findByRole('button', { name: 'Queue dry run' })).toBeDisabled();
    expect(screen.getByText('Broadcast processing is off.')).toBeInTheDocument();
    load([{ ...draft, status: 'APPROVED' }]);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh drafts' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Queue dry run' })).toBeEnabled());
    vi.mocked(api.post).mockResolvedValue({ data: { ...outcomes, status: 'QUEUED', counts: { QUEUED: 1 }, recipients: [{ ...outcomes.recipients[0], status: 'QUEUED', attempts: 0 }] } });
    fireEvent.click(screen.getByRole('button', { name: 'Queue dry run' }));
    expect(await screen.findByText('Dry run queued')).toBeInTheDocument();
    expect(screen.getByText('No recipient delivery is confirmed by these checks.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh outcomes' }));
    expect(await screen.findByText('Dry run complete')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Dry-run check passed' })).toBeInTheDocument();
  });
  it('fails closed for missing or malformed capabilities and recovers', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    render(<BroadcastDrafts />);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
    expect(screen.queryByRole('button', { name: 'Create draft' })).not.toBeInTheDocument();
    load([], { canCreateDraft: false, canApprove: false });
    fireEvent.click(screen.getByRole('button', { name: 'Retry broadcasts' }));
    expect(await screen.findByText('No broadcast drafts to review.')).toBeInTheDocument();
  });
  it('requires a real school and only offers supported channels for a school notice', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: draft });
    render(<BroadcastDrafts />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }));
    fill();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(screen.queryByRole('option', { name: 'Push notification' })).not.toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText('School (required)'), { target: { value: '10' } });
    await screen.findByRole('option', { name: 'Test school' });
    fireEvent.change(screen.getByLabelText('School (required)'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(api.post).toHaveBeenCalledWith('/notifications/broadcasts', expect.objectContaining({ schoolId: 10, communicationCategory: 'SCHOOL_NOTICE', audienceType: 'ALL_PARENTS', channels: ['SMS'] }));
  });
  it('reconciles an uncertain save without duplicating the draft', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('response lost after commit'));
    render(<BroadcastDrafts schoolId={10} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create draft' })); fill();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await screen.findByRole('button', { name: 'Check saved drafts' });
    expect(screen.getByLabelText('Title (required)')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Check saved drafts' }));
    fireEvent.click(await screen.findByRole('button', { name: `Open saved draft: ${draft.title}` }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
  });
  it('preserves the form and explains school list failure', async () => {
    vi.mocked(api.get).mockImplementation(url => url === '/schools' ? Promise.reject(new Error('offline')) : Promise.resolve({ data: url.endsWith('/capabilities') ? capabilities : [] }));
    render(<BroadcastDrafts />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }));
    fill();
    expect(await screen.findByRole('alert')).toHaveTextContent('Schools could not be loaded');
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(screen.getByLabelText('Message (required)')).toHaveValue(draft.message);
    load(); fireEvent.click(screen.getByRole('button', { name: 'Retry schools' }));
    expect(await screen.findByRole('option', { name: 'Test school' })).toBeInTheDocument();
  });
});
