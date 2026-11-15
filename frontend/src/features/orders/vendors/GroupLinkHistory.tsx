import { useEffect, useRef, useState } from 'react';
import { ExternalLink, RefreshCw } from 'lucide-react';
import { safeInviteHref } from '../shared/invite';
import { formatStamp } from '../shared/slaFormat';
import { setupApi, workflowError, type SchoolOption, type WhatsAppGroupLinkChange } from './api';

const SOURCE_LABELS: Record<WhatsAppGroupLinkChange['source'], string> = {
  SETUP: 'Workflow set-up',
  ORDER: 'Order',
  IMPORT: 'Spreadsheet import',
  VENDOR_DIRECTORY: 'Vendor directory',
};

const OUTCOME_LABELS: Record<WhatsAppGroupLinkChange['outcome'], string> = {
  ADDED: 'Added',
  CHANGED: 'Changed',
  REMOVED: 'Removed',
};

interface Names {
  schools: SchoolOption[];
  categories: Array<{ code: string; label: string }> | null;
}

/** Which group changed, in the words the rest of the Groups tab uses. */
export function describeGroup(change: WhatsAppGroupLinkChange, { schools, categories }: Names): string {
  const school = change.schoolId === null ? '' : schools.find((s) => s.id === change.schoolId)?.name ?? `School ${change.schoolId}`;
  switch (change.kind) {
    case 'SCHOOL_GROUP': return `School group · ${school}`;
    case 'INTERNAL_APPROVAL': return `School internal approval group · ${school}`;
    case 'ITEM': {
      const item = categories?.find((category) => category.code === change.categoryCode)?.label ?? change.categoryCode ?? 'Item';
      return `${item} group · ${school}`;
    }
    case 'GLOBAL_INTERNAL_APPROVAL': return 'Internal approval group (every school without its own)';
    case 'VENDOR': return `Vendor group · ${change.vendorName ?? `Vendor ${change.vendorId}`}`;
    default: return change.kind;
  }
}

/** A saved invite as a link that opens in a new tab, written without its scheme so the code is readable. */
function InviteLink({ url }: { url: string }) {
  const href = safeInviteHref(url);
  const text = url.replace(/^https:\/\//, '');
  return href
    ? <a href={href} target="_blank" rel="noopener noreferrer">{text} <ExternalLink size={13} aria-hidden="true" /></a>
    : <span>{text}</span>;
}

function ChangeCell({ change }: { change: WhatsAppGroupLinkChange }) {
  return (
    <span className="ck-setup-change">
      <strong>{OUTCOME_LABELS[change.outcome] ?? change.outcome}</strong>
      {change.before && (change.after
        ? <span>from <InviteLink url={change.before} /></span>
        : <span>was <InviteLink url={change.before} /></span>)}
      {change.after && <span>{change.before ? 'to ' : ''}<InviteLink url={change.after} /></span>}
    </span>
  );
}

/**
 * Workflow set-up → Groups → Change history (design D34): who added, changed or removed each WhatsApp group link, when,
 * from where, and the link before and after, newest first, 50 at a time. It covers saves from set-up, from an order's
 * School Group Chat card, from the spreadsheet import and from the vendor directory. The parent bumps `refresh` after
 * each save on the tab, so the newest change shows without a reload.
 */
export function GroupLinkHistory({ schools, categories, refresh }: Names & { refresh: number }) {
  const [schoolId, setSchoolId] = useState('');
  const [changes, setChanges] = useState<WhatsAppGroupLinkChange[] | null>(null);
  const [nextBefore, setNextBefore] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const [retry, setRetry] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    const request = ++latest.current;
    setChanges(null); setNextBefore(null); setError(''); setLoadingMore(false);
    setupApi.groupLinkChanges(schoolId ? Number(schoolId) : null, null).then(
      (page) => { if (request === latest.current) { setChanges(page.changes); setNextBefore(page.nextBefore); } },
      (e: unknown) => { if (request === latest.current) setError(workflowError(e, 'Unable to load the change history.')); },
    );
  }, [schoolId, refresh, retry]);

  const loadMore = async () => {
    if (nextBefore === null) return;
    const request = latest.current;
    setLoadingMore(true); setError('');
    try {
      const page = await setupApi.groupLinkChanges(schoolId ? Number(schoolId) : null, nextBefore);
      if (request !== latest.current) return;
      setChanges((current) => [...(current ?? []), ...page.changes]);
      setNextBefore(page.nextBefore);
    } catch (e) {
      if (request === latest.current) setError(workflowError(e, 'Unable to load older changes.'));
    } finally {
      if (request === latest.current) setLoadingMore(false);
    }
  };

  const chosen = schools.find((school) => String(school.id) === schoolId);
  return (
    <div className="ck-setup-block">
      <h3>Change history</h3>
      <p className="ck-setup-muted">Who added, changed or removed a WhatsApp group link, and what it was before, newest first. Saves from set-up, from an order&apos;s School Group Chat card, from the spreadsheet import and from the vendor directory are all listed.</p>
      <div className="ck-setup-fields">
        <label className="field">Show
          <select value={schoolId} onChange={(e) => setSchoolId(e.target.value)}>
            <option value="">Every group</option>
            {schools.map((school) => <option key={school.id} value={school.id}>{school.name}</option>)}
          </select>
        </label>
      </div>
      {error && changes === null && (
        <div className="ck-alert ck-alert-re">
          <span role="alert">{error}</span>
          <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setRetry((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> Retry</button>
        </div>
      )}
      {!error && changes === null && <p className="ck-setup-muted" role="status">Loading the change history…</p>}
      {changes !== null && changes.length === 0 && (
        <p className="ck-setup-muted" role="status">
          {chosen ? `No group link at ${chosen.name} has been changed yet.` : 'No group link has been changed yet.'}
        </p>
      )}
      {changes !== null && changes.length > 0 && (
        <div className="ck-table-wrap"><table className="ck-table ck-setup-history-table">
          <caption className="ck-sr-only">{chosen ? `Group link changes at ${chosen.name}` : 'Group link changes'}, newest first</caption>
          <thead><tr><th>When</th><th>Who</th><th>Group</th><th>Change</th><th>Where</th></tr></thead>
          <tbody>{changes.map((change) => (
            <tr key={change.id}>
              <td><time dateTime={change.changedAt}>{formatStamp(change.changedAt, null)}</time></td>
              <td>{change.changedByName ?? `User ${change.changedBy}`}</td>
              <td>{describeGroup(change, { schools, categories })}</td>
              <td><ChangeCell change={change} /></td>
              <td>{change.source === 'ORDER' && change.orderId ? `Order ${change.orderId}` : SOURCE_LABELS[change.source] ?? change.source}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {changes !== null && error && <p className="ck-setup-error" role="alert">{error}</p>}
      {changes !== null && nextBefore !== null && (
        <div className="ck-setup-actions">
          <button type="button" className="ck-btn ck-btn-ghost" disabled={loadingMore} onClick={() => void loadMore()}>
            {loadingMore ? 'Loading…' : 'Show older changes'}
          </button>
        </div>
      )}
    </div>
  );
}
