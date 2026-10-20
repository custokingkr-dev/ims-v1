import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useAuth } from '../../../contexts/AuthContext';
import { errorMessage } from '../../../features/catalog/api';
import { NotebookTracker } from '../../../features/tracker/NotebookTracker';
import { trackerApi } from '../../../features/tracker/api';
import type { WorklistItem } from '../../../features/tracker/types';
import { getDisplayStatus } from '../../../shared/display/status';
import '../../../features/catalog/product-form.css';
import '../../../features/tracker/tracker.css';

/** Notebook orders waiting on the signed-in operator (or, with "All", every order they may see), soonest deadline first. */
export function NotebookWorklistPanel() {
  const { user } = useAuth();
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const [finished, setFinished] = useState(false);
  const [rows, setRows] = useState<WorklistItem[] | null>(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError('');
    try { setRows(await trackerApi.worklist({ scope, includeFinished: finished })); }
    catch (e) { setRows(null); setError(errorMessage(e, 'Unable to load notebook orders.')); }
  }, [scope, finished]);
  useEffect(() => { void load(); }, [load]);

  if (open) return <NotebookTracker orderId={open} onBack={() => { setOpen(null); void load(); }} />;
  return (
    <div className="ck-trk">
      <div className="ck-trk-row">
        <h2>Notebook orders</h2>
        <div className="ck-trk-actions">
          <fieldset className="ck-trk-filter">
            <legend>Show orders</legend>
            <label><input type="radio" name="nb-scope" checked={scope === 'mine'} onChange={() => setScope('mine')} /> Assigned to me</label>
            <label><input type="radio" name="nb-scope" checked={scope === 'all'} onChange={() => setScope('all')} /> All</label>
          </fieldset>
          <label><input type="checkbox" checked={finished} onChange={(e) => setFinished(e.target.checked)} /> Include completed</label>
          <button type="button" className="ck-btn ck-btn-ghost" onClick={() => void load()}><RefreshCw size={15} aria-hidden="true" /> Refresh</button>
        </div>
      </div>
      {error && <p className="ck-product-error" role="alert">{error}</p>}
      {!error && rows === null && <p className="ck-product-muted">Loading…</p>}
      {rows && (
        <div className="ck-table-wrap"><table className="ck-table">
          <thead><tr><th>Order</th><th>School</th><th className="ck-num">Qty</th><th>Current step</th><th>Due</th><th>Progress</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
          <tbody>
            {rows.length === 0 ? <tr><td colSpan={7} className="ck-product-muted">No notebook orders{scope === 'mine' ? ' assigned to you' : ''}.</td></tr>
              : rows.map((row) => (
                <tr key={row.orderId}>
                  <td className="tb">{row.orderId}</td>
                  <td>{row.schoolName ?? `School ${row.schoolId}`}</td>
                  <td className="ck-num">{row.orderedCount.toLocaleString('en-IN')}</td>
                  <td><span className="ck-status sb2">{getDisplayStatus(row.status)}</span>{row.assigneeUserId === user?.userId ? '' : row.assigneeUserId == null ? ' · unassigned' : ''}</td>
                  <td className={row.overdue ? 'ck-trk-late' : undefined}>{row.dueAt ? `${row.overdue ? 'Overdue · ' : ''}${new Date(row.dueAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}` : '—'}</td>
                  <td>{row.completed} of {row.total}</td>
                  <td><button type="button" className="ck-btn ck-btn-ghost" onClick={() => setOpen(row.orderId)}>Open</button></td>
                </tr>
              ))}
          </tbody>
        </table></div>
      )}
    </div>
  );
}
