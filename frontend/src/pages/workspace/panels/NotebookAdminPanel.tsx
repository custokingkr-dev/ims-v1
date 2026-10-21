import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { RefreshCw } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { trackerApi, trackerError, type OperatorRow } from '../../../features/tracker/api';
import { slaLabel } from '../../../features/tracker/steps';
import '../../../features/catalog/product-form.css';
import '../../../features/tracker/tracker.css';

interface SchoolOption { id: number; name: string }

function useAction() {
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const run = async (operation: () => Promise<unknown>, done: string) => {
    setError(''); setNotice('');
    try { await operation(); setNotice(done); return true; } catch (e) { setError(trackerError(e)); return false; }
  };
  return { error, notice, run };
}

/**
 * A list the set-up reads from a service: null until it arrives, and an error (never an empty list) when it cannot load,
 * so "nothing here" is only ever said when the service said so. `load` must be stable between renders.
 */
function useList<T>(load: () => Promise<T[]>, failure: string) {
  const [rows, setRows] = useState<T[] | null>(null);
  const [error, setError] = useState('');
  const latest = useRef(0);
  const reload = useCallback(async () => {
    const request = ++latest.current;
    try { const next = await load(); if (request === latest.current) { setRows(next); setError(''); } }
    catch (e) { if (request === latest.current) setError(trackerError(e, failure)); }
  }, [load, failure]);
  useEffect(() => { setRows(null); setError(''); void reload(); }, [reload]);
  return { rows, error, reload };
}

function Messages({ error, notice }: { error: string; notice: string }) {
  return <>{error && <p className="ck-product-error" role="alert">{error}</p>}{notice && <p className="ck-product-muted" role="status">{notice}</p>}</>;
}

function LoadFailure({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="ck-alert ck-alert-re">
      <span role="alert">{message}</span>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={onRetry}><RefreshCw size={15} aria-hidden="true" /> Retry</button>
    </div>
  );
}

function OperatorsTab({ schools }: { schools: SchoolOption[] }) {
  const operators = useList(trackerApi.operators, 'Unable to load the operator assignments.');
  const users = useList(trackerApi.operatorUsers, 'Unable to load the operators.');
  const categories = useList(trackerApi.categories, 'Unable to load the catalogue items.');
  const [form, setForm] = useState({ userId: '', schoolId: '', category: '' });
  const [created, setCreated] = useState({ schoolId: '', category: '', fullName: '', email: '', temporaryPassword: '' });
  const [revoking, setRevoking] = useState<OperatorRow | null>(null);
  const [revokeBusy, setRevokeBusy] = useState(false);
  const { error, notice, run } = useAction();
  const categoryOptions = (categories.rows ?? []).map((c) => <option key={c.code} value={c.code}>{c.label}</option>);
  const name = (id: number) => schools.find((s) => s.id === id)?.name ?? `School ${id}`;
  const scope = (row: OperatorRow) => row.catalogCategory ? (categories.rows?.find((c) => c.code === row.catalogCategory)?.label ?? row.catalogCategory) : 'School-wide';
  const revoke = async (row: OperatorRow) => {
    setRevokeBusy(true);
    await run(async () => { await trackerApi.revokeAssignment(row.userId, row.assignmentId); await operators.reload(); }, 'Assignment revoked.');
    setRevokeBusy(false); setRevoking(null);
  };
  return (
    <section className="ck-trk-setup">
      <div>
        <h3>Operator assignments</h3>
        <p className="ck-product-muted">A school-wide operator handles every order of the school; an item-specific one only the item named here.</p>
        {operators.error ? <LoadFailure message={operators.error} onRetry={() => void operators.reload()} /> : (
          <div className="ck-table-wrap"><table className="ck-table">
            <thead><tr><th>Operator</th><th>School</th><th>Scope</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
            <tbody>{operators.rows === null ? <tr><td colSpan={4} className="ck-product-muted">Loading operator assignments…</td></tr>
              : operators.rows.length === 0 ? <tr><td colSpan={4} className="ck-product-muted">No operator assignments.</td></tr>
                : operators.rows.map((r) => (
                  <tr key={r.assignmentId}><td>{r.fullName}<div className="ck-product-muted">{r.email}</div></td><td>{name(r.schoolId)}</td>
                    <td>{scope(r)}</td>
                    <td><button type="button" className="ck-btn ck-btn-ghost" aria-label={`Revoke ${r.fullName} at ${name(r.schoolId)}`} onClick={() => setRevoking(r)}>Revoke</button></td></tr>
                ))}</tbody>
          </table></div>
        )}
        {users.error && <LoadFailure message={users.error} onRetry={() => void users.reload()} />}
        {categories.error && <LoadFailure message={categories.error} onRetry={() => void categories.reload()} />}
      </div>
      <div>
        <h4>Scope an operator to one catalogue item at a school</h4>
        <div className="ck-product-fields">
          <label className="field">Operator
            <select value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })}>
              <option value="">Select operator…</option>{(users.rows ?? []).map((u) => <option key={u.userId} value={u.userId}>{u.fullName || u.email}{u.fullName && u.email ? ` · ${u.email}` : ''}</option>)}
            </select>
          </label>
          <label className="field">School to scope
            <select value={form.schoolId} onChange={(e) => setForm({ ...form, schoolId: e.target.value })}>
              <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <label className="field">Catalogue item
            <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              <option value="">Select item…</option>{categoryOptions}
            </select>
          </label>
        </div>
        <button type="button" className="ck-btn ck-btn-g" disabled={!form.userId || !form.schoolId || !form.category}
          onClick={() => void run(async () => { await trackerApi.scopeOperator(Number(form.userId), Number(form.schoolId), form.category); await operators.reload(); }, 'Operator scoped.')}>Assign scope</button>
      </div>
      <div>
        <h4>Create a new item-specific operator</h4>
        <div className="ck-product-fields">
          <label className="field">School
            <select aria-label="School for new operator" value={created.schoolId} onChange={(e) => setCreated({ ...created, schoolId: e.target.value })}>
              <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <label className="field">Catalogue item for new operator
            <select value={created.category} onChange={(e) => setCreated({ ...created, category: e.target.value })}>
              <option value="">Select item…</option>{categoryOptions}
            </select>
          </label>
          <label className="field">Full name<input value={created.fullName} onChange={(e) => setCreated({ ...created, fullName: e.target.value })} /></label>
          <label className="field">Email<input type="email" value={created.email} onChange={(e) => setCreated({ ...created, email: e.target.value })} /></label>
          <label className="field">Temporary password<input type="password" autoComplete="new-password" value={created.temporaryPassword} onChange={(e) => setCreated({ ...created, temporaryPassword: e.target.value })} /></label>
        </div>
        <button type="button" className="ck-btn ck-btn-g" disabled={!created.schoolId || !created.category || !created.fullName.trim() || !created.email.trim() || !created.temporaryPassword}
          onClick={() => void run(async () => {
            await trackerApi.createScopedOperator(Number(created.schoolId), { fullName: created.fullName.trim(), email: created.email.trim(), temporaryPassword: created.temporaryPassword }, created.category);
            setCreated({ schoolId: '', category: '', fullName: '', email: '', temporaryPassword: '' });
            await operators.reload(); await users.reload();
          }, 'Operator created for that item at that school.')}>Create operator</button>
      </div>
      <Messages error={error} notice={notice} />
      {revoking && (
        <Modal title="Revoke operator assignment" disabled={revokeBusy} onClose={() => setRevoking(null)} footer={<>
          <button type="button" className="ck-btn ck-btn-ghost" disabled={revokeBusy} onClick={() => setRevoking(null)}>Cancel</button>
          <button type="button" className="ck-btn ck-btn-re" disabled={revokeBusy} onClick={() => void revoke(revoking)}>Revoke assignment</button>
        </>}>
          <p><strong>{revoking.fullName}</strong> ({revoking.email}) will no longer handle {revoking.catalogCategory ? `${scope(revoking)} orders` : 'orders'} at {name(revoking.schoolId)}.</p>
        </Modal>
      )}
    </section>
  );
}

function DesignersTab() {
  const designers = useList(trackerApi.designers, 'Unable to load the designers.');
  const [form, setForm] = useState({ name: '', phone: '' });
  const { error, notice, run } = useAction();
  return (
    <section>
      <h3>Designers</h3>
      {designers.error ? <LoadFailure message={designers.error} onRetry={() => void designers.reload()} /> : (
        <div className="ck-table-wrap"><table className="ck-table">
          <thead><tr><th>Name</th><th>Phone</th><th>Status</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
          <tbody>{designers.rows === null ? <tr><td colSpan={4} className="ck-product-muted">Loading designers…</td></tr>
            : designers.rows.length === 0 ? <tr><td colSpan={4} className="ck-product-muted">No designers yet.</td></tr>
              : designers.rows.map((d) => (
                <tr key={d.id}><td>{d.name}</td><td>{d.phone ?? '—'}</td><td>{d.active ? 'Active' : 'Inactive'}</td>
                  <td><button type="button" className="ck-btn ck-btn-ghost" onClick={() => void run(async () => { await trackerApi.updateDesigner(d.id, { name: d.name, phone: d.phone ?? '', active: !d.active }); await designers.reload(); }, d.active ? 'Designer deactivated.' : 'Designer activated.')}>{d.active ? 'Deactivate' : 'Activate'}</button></td></tr>
              ))}</tbody>
        </table></div>
      )}
      <div className="ck-product-fields">
        <label className="field">Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Phone<input type="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
      </div>
      <button type="button" className="ck-btn ck-btn-g" disabled={!form.name.trim() || !form.phone.trim()}
        onClick={() => void run(async () => { await trackerApi.createDesigner(form.name.trim(), form.phone.trim()); setForm({ name: '', phone: '' }); await designers.reload(); }, 'Designer added.')}>Add designer</button>
      <Messages error={error} notice={notice} />
    </section>
  );
}

function TimeSlotsTab({ schools }: { schools: SchoolOption[] }) {
  const [schoolId, setSchoolId] = useState('');
  const loadSlots = useCallback(() => (schoolId ? trackerApi.timeSlots(Number(schoolId)) : Promise.resolve([])), [schoolId]);
  const slots = useList(loadSlots, 'Unable to load the time slots.');
  const [edit, setEdit] = useState<Record<number, { value: string; unit: string }>>({});
  const { error, notice, run } = useAction();
  return (
    <section>
      <h3>Time slots per step</h3>
      <p className="ck-product-muted">Working days are Monday to Friday; hour slots pause over the weekend. Steps 8 and 10 have no timer.</p>
      <label className="field">School
        <select value={schoolId} onChange={(e) => setSchoolId(e.target.value)}>
          <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </label>
      {schoolId && slots.error && <LoadFailure message={slots.error} onRetry={() => void slots.reload()} />}
      {schoolId && !slots.error && slots.rows === null && <p className="ck-product-muted" role="status">Loading time slots…</p>}
      {schoolId && !slots.error && slots.rows && (
        <div className="ck-table-wrap"><table className="ck-table">
          <thead><tr><th>Step</th><th>Current</th><th>Source</th><th>Change to</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
          <tbody>{slots.rows.map((r) => {
            const draft = edit[r.step] ?? { value: r.sla ? String(r.sla.value) : '', unit: r.sla?.unit ?? 'HOURS' };
            return (
              <tr key={r.step}>
                <td>{r.step}. {r.title}</td>
                <td>{r.sla ? slaLabel({ sla: r.sla } as never) : 'No timer'}</td>
                <td>{r.sla?.source === 'SCHOOL' ? 'School override' : r.sla ? 'Default' : '—'}</td>
                <td>
                  {/* Value, unit and Save share one line, wrapping only when the cell is too narrow. */}
                  <div className="ck-trk-slot-edit">
                    <input className="ck-trk-slot-value" aria-label={`Value for step ${r.step}`} inputMode="numeric" value={draft.value} onChange={(e) => setEdit({ ...edit, [r.step]: { ...draft, value: e.target.value } })} />
                    <select className="ck-trk-slot-unit" aria-label={`Unit for step ${r.step}`} value={draft.unit} onChange={(e) => setEdit({ ...edit, [r.step]: { ...draft, unit: e.target.value } })}>
                      <option value="HOURS">hours</option><option value="WORKING_DAYS">working days</option>
                    </select>
                    <button type="button" className="ck-btn ck-btn-g" disabled={!/^\d+$/.test(draft.value) || Number(draft.value) < 1}
                      onClick={() => void run(async () => { await trackerApi.setTimeSlot(Number(schoolId), r.step, Number(draft.value), draft.unit); await slots.reload(); }, `Step ${r.step} time slot saved.`)}>Save</button>
                  </div>
                </td>
                <td>
                  {r.sla?.source === 'SCHOOL' && <button type="button" className="ck-btn ck-btn-ghost" onClick={() => void run(async () => { await trackerApi.clearTimeSlot(Number(schoolId), r.step); await slots.reload(); }, `Step ${r.step} back to default.`)}>Use default</button>}
                </td>
              </tr>
            );
          })}</tbody>
        </table></div>
      )}
      <Messages error={error} notice={notice} />
    </section>
  );
}

const TABS = [['operators', 'Operators'], ['designers', 'Designers'], ['slots', 'Time slots']] as const;
type Tab = typeof TABS[number][0];

/** Superadmin's set-up for the notebook tracker: who operates it, who designs, and how long each step may take. */
export function NotebookAdminPanel() {
  const [tab, setTab] = useState<Tab>('operators');
  const schools = useList(trackerApi.schools, 'Unable to load the schools.');
  const base = useId();
  const tabs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({});
  const tabId = (key: Tab) => `${base}-tab-${key}`;
  const panelId = `${base}-panel`;
  // Arrow keys move between tabs and wrap; Home and End jump to the first and last (WAI-ARIA tabs, automatic activation).
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = TABS.length - 1;
    const moves: Partial<Record<string, number>> = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last };
    const next = moves[event.key];
    if (next === undefined) return;
    event.preventDefault();
    const key = TABS[next][0];
    setTab(key); tabs.current[key]?.focus();
  };
  return (
    <div className="ck-trk">
      <h2>Notebook workflow set-up</h2>
      {schools.error && <LoadFailure message={schools.error} onRetry={() => void schools.reload()} />}
      <div className="ck-trk-actions" role="tablist" aria-label="Set-up sections">
        {TABS.map(([key, label], index) => (
          <button key={key} ref={(node) => { tabs.current[key] = node; }} id={tabId(key)} type="button" role="tab" aria-selected={tab === key}
            aria-controls={tab === key ? panelId : undefined} tabIndex={tab === key ? 0 : -1}
            className={`ck-btn ${tab === key ? 'ck-btn-g' : 'ck-btn-ghost'}`} onClick={() => setTab(key)} onKeyDown={(e) => onKeyDown(e, index)}>{label}</button>
        ))}
      </div>
      <div role="tabpanel" id={panelId} aria-labelledby={tabId(tab)} tabIndex={0}>
        {tab === 'operators' && <OperatorsTab schools={schools.rows ?? []} />}
        {tab === 'designers' && <DesignersTab />}
        {tab === 'slots' && <TimeSlotsTab schools={schools.rows ?? []} />}
      </div>
    </div>
  );
}
