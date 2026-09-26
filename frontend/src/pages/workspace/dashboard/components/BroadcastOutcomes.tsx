import { useState } from 'react';
import type { BroadcastOutcomes as BroadcastOutcome } from '../../../../generated/broadcastClient';
import { policyReason } from './BroadcastDrafts';
export type { BroadcastOutcome };

export function readOutcomes(value: unknown, id: string): BroadcastOutcome {
  const record = value as BroadcastOutcome | null;
  if (!record || record.broadcastId !== id || typeof record.status !== 'string'
    || !(record.mode === null || typeof record.mode === 'string') || !record.counts || typeof record.counts !== 'object'
    || !Object.values(record.counts).every(count => Number.isInteger(count) && count >= 0)
    || !Array.isArray(record.recipients) || !record.recipients.every(row => row && Number.isInteger(row.studentId)
      && typeof row.channel === 'string' && typeof row.status === 'string' && Number.isInteger(row.attempts)
      && (row.reason === null || typeof row.reason === 'string'))) throw new Error('Outcomes unavailable');
  return record;
}

const outcomeLabels: Record<string, string> = {
  APPROVED: 'Approved', QUEUED: 'Waiting for check', DRY_RUN: 'Dry-run check passed',
  FAILED: 'Retry pending', DEAD_LETTER: 'Retry limit reached', SUPPRESSED: 'Excluded by policy', DUPLICATE: 'Shared destination skipped',
};

export function BroadcastOutcomes({ outcome, canRetry, onRetry }: { outcome: BroadcastOutcome; canRetry: boolean; onRetry: () => void }) {
  const [page, setPage] = useState(0);
  const currentPage = Math.min(page, Math.max(0, Math.ceil(outcome.recipients.length / 25) - 1));
  const rows = outcome.recipients.slice(currentPage * 25, (currentPage + 1) * 25);
  return <details open className="ck-command-bc-note">
    <summary>Recipient outcomes ({outcome.recipients.length})</summary>
    <p>No recipient delivery is confirmed by these checks.</p>
    <p>{Object.entries(outcome.counts).map(([status, count]) => `${outcomeLabels[status] ?? status}: ${count}`).join(' | ')}</p>
    {outcome.counts.FAILED > 0 && <button className="ck-btn ck-btn-ghost" disabled={!canRetry} onClick={onRetry}>Retry failed checks</button>}
    <div style={{ overflowX: 'auto' }}><table className="ck-table">
      <caption>Approved recipient checks; contact destinations are hidden</caption>
      <thead><tr><th scope="col">Student</th><th scope="col">Channel</th><th scope="col">Outcome</th><th scope="col">Attempts</th></tr></thead>
      <tbody>{rows.map(row => <tr key={`${row.studentId}:${row.channel}`}>
        <th scope="row">{row.studentId}</th><td>{row.channel}</td><td>{outcomeLabels[row.status] ?? 'Status unavailable'}{row.reason && <div>{policyReason(row.reason)}</div>}</td><td>{row.attempts}</td>
      </tr>)}</tbody>
    </table></div>
    {outcome.recipients.length > 25 && <div className="ck-command-bc-actions">
      <button className="ck-btn ck-btn-ghost" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Previous recipients</button>
      <span>Page {currentPage + 1} of {Math.ceil(outcome.recipients.length / 25)}</span>
      <button className="ck-btn ck-btn-ghost" disabled={(currentPage + 1) * 25 >= outcome.recipients.length} onClick={() => setPage(currentPage + 1)}>Next recipients</button>
    </div>}
  </details>;
}
