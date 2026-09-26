import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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


const liveDraft: BroadcastRecord = { ...draft, status: 'APPROVED', channels: ['EMAIL'], approvalMode: 'LIVE', dispatchMode: null };
const liveCapabilities = { ...capabilities, mode: 'LIVE', canSend: true, supportedChannels: ['EMAIL'], sendUnavailableReason: '' };
const liveOutcomes = { ...outcomes, mode: 'LIVE', approvalMode: 'LIVE', status: 'AWAITING_DELIVERY', counts: { ACCEPTED: 1 },
  recipients: [{ ...outcomes.recipients[0], channel: 'EMAIL', status: 'ACCEPTED', dryRun: false, provider: 'msg91', providerMessageId: 'provider-1' }] };

describe('Live broadcast confirmation and reconciliation', () => {
  beforeEach(() => { vi.resetAllMocks(); load([liveDraft], liveCapabilities); });
  afterEach(cleanup);

  async function review() {
    fireEvent.click(await screen.findByRole('button', { name: 'Review live send' }));
    return within(await screen.findByRole('dialog', { name: 'Confirm live sending' }));
  }

  it('reviews scoped capabilities and requires explicit consent before posting the exact live fingerprint once', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: preview }).mockResolvedValueOnce({ data: liveOutcomes });
    render(<BroadcastDrafts schoolId={10} />);
    const dialog = await review();
    expect(api.get).toHaveBeenLastCalledWith('/notifications/broadcasts/capabilities', { params: { schoolId: 10 } });
    expect(dialog.getByText(draft.message!)).toBeInTheDocument();
    expect(dialog.getByText('School 10. Channels: EMAIL.')).toBeInTheDocument();
    expect(dialog.getByText('1 eligible destinations; 1 excluded; 1 shared destinations skipped.')).toBeInTheDocument();
    expect(dialog.getByRole('button', { name: 'Send actual messages' })).toBeDisabled();
    expect(api.post).toHaveBeenCalledTimes(1);
    fireEvent.click(dialog.getByRole('checkbox', { name: /I reviewed the message and recipients/ }));
    const send = dialog.getByRole('button', { name: 'Send actual messages' });
    fireEvent.click(send); fireEvent.click(send);
    expect(await screen.findByText('Waiting for delivery reports')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(2);
    expect(api.post).toHaveBeenLastCalledWith('/notifications/broadcasts/draft-1/send', { mode: 'LIVE', previewFingerprint: preview.fingerprint });
    expect(screen.getByText('Confirmed deliveries: 0. Provider acceptance is not delivery confirmation.')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /Accepted by provider; delivery unconfirmed/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry failed|Send actual|Review live/ })).not.toBeInTheDocument();
  });

  it('cancels without sending and resets the acknowledgement on a new review', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: preview });
    render(<BroadcastDrafts schoolId={10} />);
    let dialog = await review();
    fireEvent.click(dialog.getByRole('checkbox'));
    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    dialog = await review();
    expect(dialog.getByRole('checkbox')).not.toBeChecked();
    expect(dialog.getByRole('button', { name: 'Send actual messages' })).toBeDisabled();
    expect(vi.mocked(api.post).mock.calls.every(([url]) => url.endsWith('/preview'))).toBe(true);
  });

  it('disables live sending when scoped school capability is unavailable', async () => {
    load([liveDraft], { ...liveCapabilities, canSend: false, canQueue: false, sendUnavailableReason: 'School is not enabled for live email.' });
    render(<BroadcastDrafts schoolId={10} />);
    expect(await screen.findByRole('button', { name: 'Review live send' })).toBeDisabled();
    expect(screen.getByText('School is not enabled for live email.')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('fetches the record school in a global view and fails closed for unsupported live channels', async () => {
    load([liveDraft], { ...liveCapabilities, canSend: false, canQueue: false });
    vi.mocked(api.post).mockResolvedValue({ data: preview });
    render(<BroadcastDrafts />);
    const button = await screen.findByRole('button', { name: 'Review live send' });
    expect(button).toBeEnabled();
    vi.mocked(api.get).mockResolvedValue({ data: { ...liveCapabilities, supportedChannels: ['SMS'] } });
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable for this school and channel');
    expect(api.get).toHaveBeenLastCalledWith('/notifications/broadcasts/capabilities', { params: { schoolId: 10 } });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it.each(['SUBMITTING', 'UNKNOWN', 'REPORT_CONFLICT'])('reconciles a lost queue response with %s without resending', async status => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: preview }).mockRejectedValueOnce(new Error('Response lost after commit'));
    render(<BroadcastDrafts schoolId={10} />);
    const dialog = await review();
    fireEvent.click(dialog.getByRole('checkbox'));
    fireEvent.click(dialog.getByRole('button', { name: 'Send actual messages' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Messages may already be processing');
    expect(screen.getByRole('button', { name: 'Review live send' })).toBeDisabled();
    vi.mocked(api.get).mockResolvedValue({ data: { ...liveOutcomes, status: 'NEEDS_RECONCILIATION', counts: { [status]: 1 }, recipients: [{ ...liveOutcomes.recipients[0], status }] } });
    fireEvent.click(screen.getByRole('button', { name: 'Refresh outcomes' }));
    expect(await screen.findByText('Delivery needs reconciliation')).toBeInTheDocument();
    expect(screen.getByText(/Delivery may have started. Do not resend/)).toBeInTheDocument();
    expect(api.get).toHaveBeenLastCalledWith('/notifications/broadcasts/draft-1/delivery-status');
    expect(api.post).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('button', { name: /Retry failed|Send actual|Review live/ })).not.toBeInTheDocument();
  });

  it('keeps a changed-review rejection uncertain until the same broadcast is reconciled', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: preview }).mockRejectedValueOnce({ response: { status: 409 } });
    render(<BroadcastDrafts schoolId={10} />);
    const dialog = await review();
    fireEvent.click(dialog.getByRole('checkbox'));
    fireEvent.click(dialog.getByRole('button', { name: 'Send actual messages' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('newly reviewed draft only after this result is resolved');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review live send' })).toBeDisabled();
    expect(api.post).toHaveBeenCalledTimes(2);
  });

  it('does not convert a dry-run approval into live sending or relabel historical dry-run outcomes', async () => {
    load([{ ...draft, status: 'APPROVED', approvalMode: 'DRY_RUN', dispatchMode: 'DRY_RUN' }], liveCapabilities);
    render(<BroadcastDrafts schoolId={10} />);
    expect(await screen.findByText(/This approval belongs to a dry run/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Queue dry run|Review live send/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh outcomes' }));
    expect(await screen.findByText('Dry run complete')).toBeInTheDocument();
    expect(screen.getByText('No recipient delivery is confirmed by these checks.')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('cannot queue a live approval as dry run after deployment mode changes', async () => {
    load([liveDraft]);
    render(<BroadcastDrafts schoolId={10} />);
    expect(await screen.findByText(/This live approval cannot be queued as a dry run/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Queue dry run|Review live send/ })).not.toBeInTheDocument();
  });
});
