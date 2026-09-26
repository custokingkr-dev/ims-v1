import { useState } from 'react';
import type { BroadcastOutcomes as BroadcastOutcome } from '../../../../generated/broadcastClient';
import { policyReason } from './BroadcastDrafts';
export type { BroadcastOutcome };

export function readOutcomes(value: unknown, id: string): BroadcastOutcome {
  const record = value as BroadcastOutcome | null;
  if (!record || record.broadcastId !== id || typeof record.status !== 'string'
    || ![null, 'DRY_RUN', 'LIVE'].includes(record.mode) || !record.counts || typeof record.counts !== 'object' || Array.isArray(record.counts)
    || ![record.total, record.delivered].every(count => Number.isInteger(count) && count >= 0)
    || !Object.values(record.counts).every(count => Number.isInteger(count) && count >= 0)
    || !Array.isArray(record.recipients) || !record.recipients.every(row => row && Number.isInteger(row.studentId)
      && typeof row.channel === 'string' && typeof row.status === 'string' && Number.isInteger(row.attempts) && row.attempts >= 0
      && typeof row.dryRun === 'boolean' && (row.reason === null || typeof row.reason === 'string')
      && (row.providerMessageId == null || typeof row.providerMessageId === 'string'))) throw new Error('Outcomes unavailable');
  const counts: Record<string, number> = {};
  record.recipients.forEach(row => { counts[row.status] = (counts[row.status] ?? 0) + 1; });
  if (record.total !== record.recipients.length || record.delivered !== (counts.DELIVERED ?? 0)
    || new Set([...Object.keys(counts), ...Object.keys(record.counts)]).size !== Object.keys(record.counts).length
    || Object.entries(record.counts).some(([status, count]) => count !== (counts[status] ?? 0))
    || (record.mode === 'DRY_RUN' && (record.delivered !== 0 || record.recipients.some(row => !row.dryRun)))) throw new Error('Outcomes unavailable');
  return record;
}

const outcomeLabels: Record<string, string> = {
  APPROVED: 'Approved', QUEUED: 'Waiting for check', DRY_RUN: 'Dry-run check passed',
  FAILED: 'Retry pending', DEAD_LETTER: 'Retry limit reached', SUPPRESSED: 'Excluded by policy', DUPLICATE: 'Shared destination skipped',
  SUBMITTING: 'Submission unconfirmed', ACCEPTED: 'Accepted by provider; delivery unconfirmed', UNKNOWN: 'Outcome unknown',
  DELIVERED: 'Delivered', DELIVERY_FAILED: 'Delivery failed', REJECTED: 'Rejected by provider', REPORT_CONFLICT: 'Conflicting delivery reports',
};

export function BroadcastOutcomes({ outcome, canRetry, onRetry }: { outcome: BroadcastOutcome; canRetry: boolean; onRetry: () => void }) {
  const [page, setPage] = useState(0);
  const currentPage = Math.min(page, Math.max(0, Math.ceil(outcome.recipients.length / 25) - 1));
  const rows = outcome.recipients.slice(currentPage * 25, (currentPage + 1) * 25);
  const dryRun = outcome.mode === 'DRY_RUN';
  const uncertain = outcome.status === 'NEEDS_RECONCILIATION' || ['SUBMITTING', 'UNKNOWN', 'REPORT_CONFLICT'].some(status => outcome.counts[status] > 0);
  return <details open className="ck-command-bc-note">
    <summary>Recipient outcomes ({outcome.recipients.length})</summary>
    {dryRun ? <p>No recipient delivery is confirmed by these checks.</p> : outcome.mode === 'LIVE' ? <>
      <p>Confirmed deliveries: {outcome.delivered}. Provider acceptance is not delivery confirmation.</p>
      {uncertain && <p role="status">Delivery may have started. Do not resend this broadcast. Refresh outcomes while the provider status is reconciled.</p>}
    </> : <p>Recipients are approved. Sending has not been queued.</p>}
    <p>{Object.entries(outcome.counts).map(([status, count]) => `${outcomeLabels[status] ?? status}: ${count}`).join(' | ')}</p>
    {dryRun && outcome.counts.FAILED > 0 && <button className="ck-btn ck-btn-ghost" disabled={!canRetry} onClick={onRetry}>Retry failed checks</button>}
    <div style={{ overflowX: 'auto' }}><table className="ck-table">
      <caption>{dryRun ? 'Approved recipient checks' : 'Recipient delivery outcomes'}; contact destinations are hidden</caption>
      <thead><tr><th scope="col">Student</th><th scope="col">Channel</th><th scope="col">Outcome</th><th scope="col">Attempts</th></tr></thead>
      <tbody>{rows.map(row => <tr key={`${row.studentId}:${row.channel}`}>
        <th scope="row">{row.studentId}</th><td>{row.channel}</td><td>{row.status === 'QUEUED' && outcome.mode === 'LIVE' ? 'Waiting to send' : outcomeLabels[row.status] ?? 'Status unavailable'}{row.reason && <div>{policyReason(row.reason)}</div>}{row.providerMessageId && <div style={{ overflowWrap: 'anywhere' }}>Provider reference: {row.providerMessageId}</div>}</td><td>{row.attempts}</td>
      </tr>)}</tbody>
    </table></div>
    {outcome.recipients.length > 25 && <div className="ck-command-bc-actions">
      <button className="ck-btn ck-btn-ghost" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Previous recipients</button>
      <span>Page {currentPage + 1} of {Math.ceil(outcome.recipients.length / 25)}</span>
      <button className="ck-btn ck-btn-ghost" disabled={(currentPage + 1) * 25 >= outcome.recipients.length} onClick={() => setPage(currentPage + 1)}>Next recipients</button>
    </div>}
  </details>;
}
