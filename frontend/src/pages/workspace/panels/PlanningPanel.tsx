import { useEffect, useRef, useState } from 'react';
import { ModuleShell } from '../ui';
import { Modal } from '../../../components/Modal';
import type { WorkspaceData, PanelKey } from '../../workspace/config';
import { formatMoney } from '../utils';
import { useAuth } from '../../../contexts/AuthContext';
import api from '../../../services/api';
import { catalogClient } from '../../../services/catalogApi';

interface Props { workspace: WorkspaceData; onRefresh: () => Promise<void>; setPanel?: (key: PanelKey) => void; }
interface PlanItem { id: string; term: string | null; category: string | null; description: string | null; quantity: string | null; estimatedAmount: number; status: string | null; }
interface Confirmation { confirmed: true; id: string; schoolId: number; academicYearId: string; fingerprint: string; revision: number; confirmedAt: string; itemCount: number; notificationStatus: 'NOT_SENT'; }
interface PlanReview { schoolId: number; academicYearId: string; yearLabel: string; items: PlanItem[]; fingerprint: string; confirmation: Confirmation | null; }
const EMPTY_FORM = { category: '', description: '', estimatedAmount: '' };
function readConfirmation(value: unknown, fingerprint: string, schoolId: number, academicYearId: string): Confirmation {
  const record = value as Confirmation | null;
  if (!record || record.confirmed !== true || typeof record.id !== 'string' || record.fingerprint !== fingerprint
    || record.schoolId !== schoolId || record.academicYearId !== academicYearId || !Number.isInteger(record.itemCount) || record.itemCount < 1
    || !Number.isInteger(record.revision) || record.revision < 1 || typeof record.confirmedAt !== 'string' || !Number.isFinite(Date.parse(record.confirmedAt))
    || record.notificationStatus !== 'NOT_SENT') throw new Error('Confirmation unavailable');
  return record;
}
function readReview(value: unknown, schoolId: number): PlanReview {
  const record = value as PlanReview | null;
  if (!record || record.schoolId !== schoolId || typeof record.academicYearId !== 'string' || typeof record.yearLabel !== 'string'
    || typeof record.fingerprint !== 'string' || !record.fingerprint || !Array.isArray(record.items)
    || !record.items.every(item => item && typeof item.id === 'string' && Number.isFinite(item.estimatedAmount)
      && [item.term, item.category, item.description, item.quantity, item.status].every(value => value === null || typeof value === 'string'))) throw new Error('Plan review unavailable');
  if (record.confirmation) readConfirmation(record.confirmation, record.fingerprint, schoolId, record.academicYearId);
  return record;
}

export function PlanningPanel({ workspace, onRefresh, setPanel }: Props) {
  const { user } = useAuth();
  const platform = user?.role === 'SUPERADMIN';
  const canManage = platform || (user?.permissions ?? []).includes('plan:manage');
  const [schoolId, setSchoolId] = useState(platform ? 0 : user?.branchId ?? 0);
  const [schools, setSchools] = useState<Array<{ id: number; name: string }>>([]);
  const [review, setReview] = useState<PlanReview | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [reload, setReload] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [unconfirmed, setUnconfirmed] = useState(false);
  const savingRef = useRef(false);
  const newItemId = useRef<string | null>(null);

  useEffect(() => {
    if (!platform) return;
    let active = true;
    api.get('/schools').then(result => {
      if (!Array.isArray(result.data) || !result.data.every((school: { id?: unknown; name?: unknown }) => Number.isInteger(school.id) && typeof school.name === 'string')) throw new Error('School list unavailable');
      if (active) setSchools(result.data);
    }).catch(() => { if (active) setLoadError('Schools could not be loaded. Refresh the plan to retry.'); });
    return () => { active = false; };
  }, [platform, reload]);
  useEffect(() => {
    if (!schoolId) { setReview(null); return; }
    let active = true; setLoading(true); setLoadError(''); setReview(null);
    catalogClient.reviewAnnualPlan({ schoolId }).then(result => {
      const next = readReview(result, schoolId);
      if (active) {
        setReview(next); setUnconfirmed(false);
        const savedItem = next.items.find(item => item.id === newItemId.current);
        if (savedItem) {
          newItemId.current = null; setForm(EMPTY_FORM); setFormOpen(false);
          setNotice('The item is saved in this plan. Review its details before confirming the plan.');
        }
      }
    }).catch(() => { if (active) setLoadError('The current plan could not be loaded. Refresh before adding or confirming items.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [schoolId, reload]);

  async function createPlanItem() {
    if (savingRef.current || !canManage || !review) return;
    const amount = Number(form.estimatedAmount);
    if (!form.category.trim() || !form.estimatedAmount.trim() || !Number.isSafeInteger(amount) || amount < 0) {
      setError('Enter a category and a whole-number estimated amount of zero or more.'); return;
    }
    savingRef.current = true; setSaving(true); setError(''); setNotice('');
    if (!newItemId.current) newItemId.current = crypto.randomUUID();
    try {
      const result = await api.post('/catalog/annual-plan/items', { id: newItemId.current, category: form.category.trim(), description: form.description.trim(), estimatedAmount: amount }, { params: { schoolId } });
      if (result.data?.id !== newItemId.current || result.data?.schoolId !== schoolId) throw new Error('Saved item unavailable');
      newItemId.current = null; setForm(EMPTY_FORM); setFormOpen(false); setReload(value => value + 1);
      setNotice('Plan item saved. Confirm the updated plan when it is ready.');
    } catch {
      setError('Item saving could not be confirmed. Your details are still here. Retrying this form uses the same item reference to prevent a duplicate.');
    } finally { savingRef.current = false; setSaving(false); }
  }
  async function confirmPlan() {
    if (savingRef.current || newItemId.current !== null || !canManage || !review || !review.items.length || unconfirmed || review.confirmation) return;
    savingRef.current = true; setConfirming(true); setNotice(''); setError('');
    try {
      const result = await catalogClient.confirmAnnualPlan({ schoolId }, { fingerprint: review.fingerprint });
      const confirmation = readConfirmation(result, review.fingerprint, schoolId, review.academicYearId);
      setReview(current => current ? { ...current, confirmation } : current);
      setNotice(`Plan confirmation saved as revision ${confirmation.revision}. No staff or provider notification has been sent.`);
      try { await onRefresh(); }
      catch { setNotice(`Plan confirmation is saved as revision ${confirmation.revision}. The workspace could not refresh; refresh the plan to review it. No notification has been sent.`); }
    } catch {
      setUnconfirmed(true);
      setError('Plan confirmation could not be confirmed. Refresh the plan to check for a saved confirmation or changed items before retrying.');
    } finally { savingRef.current = false; setConfirming(false); }
  }

  const items = review?.items ?? [];
  const busy = saving || confirming;
  return <ModuleShell title="Annual plan" subtitle={review ? `Review supply requirements for academic year ${review.yearLabel}` : 'Review the current school plan before confirming it.'} actions={<>
    {setPanel && <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('catalog')}>Catalog</button>}
    <button className="ck-btn ck-btn-ghost" disabled={busy || loading} onClick={() => { setError(''); setReload(value => value + 1); }}>Refresh plan</button>
    {canManage && <button className="ck-btn ck-btn-ghost" disabled={!review || busy || loading} onClick={() => { setError(''); setFormOpen(true); }}>Add item</button>}
    {canManage && <button className="ck-btn ck-btn-g" disabled={busy || loading || !items.length || Boolean(review?.confirmation) || unconfirmed || newItemId.current !== null} onClick={() => void confirmPlan()}>{confirming ? 'Recording confirmation...' : 'Confirm current plan'}</button>}
  </>}>
    {platform ? <div className="ck-field"><label htmlFor="plan-school">School</label><select id="plan-school" value={schoolId || ''} disabled={busy || formOpen || newItemId.current !== null} onChange={event => { setSchoolId(Number(event.target.value)); setNotice(''); setError(''); }}>
      <option value="">Select school</option>{schools.map(school => <option key={school.id} value={school.id}>{school.name}</option>)}
    </select></div> : <p>{workspace.school?.name}</p>}
    {newItemId.current !== null && !formOpen && <p>An item save needs review. Refresh this plan to check it, or reopen Add item to retry the same item reference before changing schools.</p>}
    {!schoolId && <p>{platform ? 'Select a school to review its current annual plan.' : 'Your account has no school scope. Contact your administrator.'}</p>}
    {loading && <p role="status">Loading the current annual plan...</p>}
    {loadError && <p role="alert">{loadError}</p>}
    {error && !formOpen && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {review && <>
      <div className="ap-subheader"><div className="ap-hstats">
        <div className="ap-hstat"><div className="ap-hstat-v">{items.length}</div><div className="ap-hstat-l">Saved items</div></div>
        <div className="ap-hstat"><div className="ap-hstat-v">Rs {formatMoney(items.reduce((sum, item) => sum + item.estimatedAmount, 0))}</div><div className="ap-hstat-l">Estimated total</div></div>
      </div></div>
      <p>{review.confirmation ? `Current plan confirmed as revision ${review.confirmation.revision}.` : 'This version of the plan is not confirmed.'} Confirmation records the reviewed items. It does not place orders or send notifications.</p>
      {items.length ? <div className="ck-table-wrap"><table className="ck-table"><caption>Saved items for academic year {review.yearLabel}</caption>
        <thead><tr><th scope="col">Term</th><th scope="col">Category</th><th scope="col">Quantity</th><th scope="col">Item status</th><th scope="col">Estimated amount</th></tr></thead>
        <tbody>{items.map(item => <tr key={item.id}><td>{item.term || '-'}</td><td><strong>{item.category || 'Uncategorized'}</strong>{item.description && <div>{item.description}</div>}</td><td>{item.quantity || '-'}</td><td>{item.status || 'Not specified'}</td><td>Rs {formatMoney(item.estimatedAmount)}</td></tr>)}</tbody>
      </table></div> : <p>No items saved for this academic year. Add a supply requirement before confirming the plan.</p>}
    </>}
    {setPanel && <div style={{ marginTop: 24 }}><p>Use the catalog to create supply orders separately.</p><button className="ck-btn ck-btn-ghost" onClick={() => setPanel('orders')}>View orders</button></div>}
    {formOpen && <Modal title="Add annual plan item" subtitle={`Save a requirement for academic year ${review?.yearLabel ?? ''}`} onClose={() => { if (!saving) setFormOpen(false); }} footer={<>
      <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={() => setFormOpen(false)}>Cancel</button>
      <button className="ck-btn ck-btn-g" disabled={saving} onClick={() => void createPlanItem()}>{saving ? 'Saving...' : 'Save item'}</button>
    </>}>
      {error && <p role="alert">{error}</p>}
      <fieldset disabled={saving} className="ck-form-grid ck-fg-1" style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
        <div className="ck-field"><label htmlFor="plan-category">Category (required)</label><input id="plan-category" maxLength={255} value={form.category} onChange={event => setForm(current => ({ ...current, category: event.target.value }))} /></div>
        <div className="ck-field"><label htmlFor="plan-description">Description</label><textarea id="plan-description" maxLength={255} rows={3} value={form.description} onChange={event => setForm(current => ({ ...current, description: event.target.value }))} /></div>
        <div className="ck-field"><label htmlFor="plan-amount">Estimated amount (required)</label><input id="plan-amount" type="number" min={0} step={1} value={form.estimatedAmount} onChange={event => setForm(current => ({ ...current, estimatedAmount: event.target.value }))} /></div>
      </fieldset>
    </Modal>}
  </ModuleShell>;
}
