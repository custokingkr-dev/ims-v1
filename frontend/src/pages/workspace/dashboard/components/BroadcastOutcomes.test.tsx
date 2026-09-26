import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BroadcastOutcomes, readOutcomes, type BroadcastOutcome } from './BroadcastOutcomes';

const recipient = { studentId: 1, channel: 'EMAIL', status: 'ACCEPTED', reason: null, attempts: 1, nextAttemptAt: null, provider: 'msg91', dryRun: false, providerMessageId: 'provider-1' };
const accepted: BroadcastOutcome = { broadcastId: 'broadcast-1', mode: 'LIVE', status: 'AWAITING_DELIVERY', total: 1, delivered: 0, counts: { ACCEPTED: 1 }, recipients: [recipient] };

describe('Truthful broadcast delivery outcomes', () => {
  afterEach(cleanup);
  it('distinguishes provider acceptance from actual delivery', () => {
    render(<BroadcastOutcomes outcome={readOutcomes(accepted, accepted.broadcastId)} canRetry onRetry={vi.fn()} />);
    expect(screen.getByText('Confirmed deliveries: 0. Provider acceptance is not delivery confirmation.')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /Accepted by provider; delivery unconfirmed/ })).toBeInTheDocument();
    expect(screen.getByText('Provider reference: provider-1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });
  it('counts only confirmed delivered rows and keeps terminal failures visible', () => {
    const outcome = { ...accepted, status: 'COMPLETED_WITH_FAILURES', total: 3, delivered: 1,
      counts: { DELIVERED: 1, DELIVERY_FAILED: 1, REJECTED: 1 },
      recipients: ['DELIVERED', 'DELIVERY_FAILED', 'REJECTED'].map((status, i) => ({ ...recipient, studentId: i + 1, status })) };
    render(<BroadcastOutcomes outcome={readOutcomes(outcome, accepted.broadcastId)} canRetry onRetry={vi.fn()} />);
    expect(screen.getByText('Confirmed deliveries: 1. Provider acceptance is not delivery confirmation.')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /Delivery failed/ })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /Rejected by provider/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });
  it.each(['SUBMITTING', 'UNKNOWN', 'REPORT_CONFLICT'])('does not offer retry for live %s, even with another failed row', status => {
    const outcome = { ...accepted, status: 'NEEDS_RECONCILIATION', total: 2, counts: { [status]: 1, FAILED: 1 },
      recipients: [{ ...recipient, status }, { ...recipient, studentId: 2, status: 'FAILED' }] };
    render(<BroadcastOutcomes outcome={readOutcomes(outcome, accepted.broadcastId)} canRetry onRetry={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent('Do not resend this broadcast');
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });
  it('allows retry only for a failed dry-run check and makes no delivery claim', () => {
    const outcome: BroadcastOutcome = { ...accepted, mode: 'DRY_RUN', status: 'QUEUED', counts: { FAILED: 1 }, recipients: [{ ...recipient, status: 'FAILED', dryRun: true }] };
    render(<BroadcastOutcomes outcome={readOutcomes(outcome, accepted.broadcastId)} canRetry onRetry={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Retry failed checks' })).toBeEnabled();
    expect(screen.getByText('No recipient delivery is confirmed by these checks.')).toBeInTheDocument();
  });
  it('does not infer sending when the approved manifest has no dispatch mode', () => {
    const outcome = { ...accepted, mode: null, status: 'APPROVED', counts: { APPROVED: 1 }, recipients: [{ ...recipient, status: 'APPROVED', attempts: 0 }] };
    render(<BroadcastOutcomes outcome={readOutcomes(outcome, accepted.broadcastId)} canRetry onRetry={vi.fn()} />);
    expect(screen.getByText('Recipients are approved. Sending has not been queued.')).toBeInTheDocument();
    expect(screen.queryByText(/Confirmed deliveries/)).not.toBeInTheDocument();
  });
  it.each([
    { ...accepted, delivered: 1 },
    { ...accepted, total: 2 },
    { ...accepted, counts: { DELIVERED: 1 } },
    { ...accepted, mode: 'DRY_RUN' },
    { ...accepted, counts: { ACCEPTED: -1 } },
    { ...accepted, recipients: [{ ...recipient, dryRun: undefined }] },
    { ...accepted, recipients: [{ ...recipient, providerMessageId: 123 }] },
  ])('rejects malformed or contradictory outcome evidence %#', outcome => {
    expect(() => readOutcomes(outcome, accepted.broadcastId)).toThrow('Outcomes unavailable');
  });
});
