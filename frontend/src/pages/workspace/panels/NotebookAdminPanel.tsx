import { useCallback, useEffect, useState } from 'react';
import { errorMessage } from '../../../features/catalog/api';
import { trackerApi, type OperatorRow } from '../../../features/tracker/api';
import { slaLabel } from '../../../features/tracker/steps';
import type { DesignerRow, OperatorUser, SlaRow } from '../../../features/tracker/types';
import '../../../features/tracker/tracker.css';

interface SchoolOption { id: number; name: string }

function useAction() {
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const run = async (operation: () => Promise<unknown>, done: string) => {
    setError(''); setNotice('');
    try { await operation(); setNotice(done); return true; } catch (e) { setError(errorMessage(e)); return false; }
  };
  return { error, notice, run };
}

function Messages({ error, notice }: { error: string; notice: string }) {
  return <>{error && <p className="ck-product-error" role="alert">{error}</p>}{notice && <p className="ck-product-muted" role="status">{notice}</p>}</>;
}

function OperatorsTab({ schools }: { schools: SchoolOption[] }) {
  const [rows, setRows] = useState<OperatorRow[]>([]);
  const [users, setUsers] = useState<OperatorUser[]>([]);
  const [categories, setCategories] = useState<Array<{ code: string; label: string }>>([]);
  const [form, setForm] = useState({ userId: '', schoolId: '', category: '' });
  const [created, setCreated] = useState({ schoolId: '', category: '', fullName: '', email: '', temporaryPassword: '' });
  const { error, notice, run } = useAction();
  const load = useCallback(async () => { setRows(await trackerApi.operators()); }, []);
  useEffect(() => { load().catch(() => setRows([])); }, [load]);
  useEffect(() => { trackerApi.operatorUsers().then(setUsers).catch(() => setUsers([])); }, []);
  useEffect(() => { trackerApi.categories().then(setCategories).catch(() => setCategories([])); }, []);
  const categoryOptions = categories.map((c) => <option key={c.code} value={c.code}>{c.label}</option>);
  const name = (id: number) => schools.find((s) => s.id === id)?.name ?? `School ${id}`;
  return (
    <section>
      <h3>Operator assignments</h3>
      <p className="ck-product-muted">A school-wide operator handles every order of the school; an item-specific one only the item named here.</p>
      <div className="ck-table-wrap"><table className="ck-table">
        <thead><tr><th>Operator</th><th>School</th><th>Scope</th><th /></tr></thead>
        <tbody>{rows.length === 0 ? <tr><td colSpan={4} className="ck-product-muted">No operator assignments.</td></tr>
          : rows.map((r) => (
            <tr key={r.assignmentId}><td>{r.fullName}<div className="ck-product-muted">{r.email}</div></td><td>{name(r.schoolId)}</td>
              <td>{r.catalogCategory ?? 'School-wide'}</td>
              <td><button className="ck-btn ck-btn-ghost" onClick={() => void run(async () => { await trackerApi.revokeAssignment(r.userId, r.assignmentId); await load(); }, 'Assignment revoked.')}>Revoke</button></td></tr>
          ))}</tbody>
      </table></div>
      <h4>Scope an operator to one catalogue item at a school</h4>
      <div className="ck-product-fields">
        <label className="field">Operator
          <select value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })}>
            <option value="">Select operator…</option>{users.map((u) => <option key={u.userId} value={u.userId}>{u.fullName || u.email}{u.fullName && u.email ? ` · ${u.email}` : ''}</option>)}
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
      <button className="ck-btn ck-btn-g" disabled={!form.userId || !form.schoolId || !form.category}
        onClick={() => void run(async () => { await trackerApi.scopeOperator(Number(form.userId), Number(form.schoolId), form.category); await load(); }, 'Operator scoped.')}>Assign scope</button>
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
      <button className="ck-btn ck-btn-g" disabled={!created.schoolId || !created.category || !created.fullName.trim() || !created.email.trim() || !created.temporaryPassword}
        onClick={() => void run(async () => {
          await trackerApi.createScopedOperator(Number(created.schoolId), { fullName: created.fullName.trim(), email: created.email.trim(), temporaryPassword: created.temporaryPassword }, created.category);
          setCreated({ schoolId: '', category: '', fullName: '', email: '', temporaryPassword: '' });
          await load(); setUsers(await trackerApi.operatorUsers());
        }, 'Operator created for that item at that school.')}>Create operator</button>
      <Messages error={error} notice={notice} />
    </section>
  );
}

function DesignersTab() {
  const [rows, setRows] = useState<DesignerRow[]>([]);
  const [form, setForm] = useState({ name: '', phone: '' });
  const { error, notice, run } = useAction();
  const load = useCallback(async () => { setRows(await trackerApi.designers()); }, []);
  useEffect(() => { load().catch(() => setRows([])); }, [load]);
  return (
    <section>
      <h3>Designers</h3>
      <div className="ck-table-wrap"><table className="ck-table">
        <thead><tr><th>Name</th><th>Phone</th><th>Status</th><th /></tr></thead>
        <tbody>{rows.length === 0 ? <tr><td colSpan={4} className="ck-product-muted">No designers yet.</td></tr>
          : rows.map((d) => (
            <tr key={d.id}><td>{d.name}</td><td>{d.phone ?? '—'}</td><td>{d.active ? 'Active' : 'Inactive'}</td>
              <td><button className="ck-btn ck-btn-ghost" onClick={() => void run(async () => { await trackerApi.updateDesigner(d.id, { name: d.name, phone: d.phone ?? '', active: !d.active }); await load(); }, d.active ? 'Designer deactivated.' : 'Designer activated.')}>{d.active ? 'Deactivate' : 'Activate'}</button></td></tr>
          ))}</tbody>
      </table></div>
      <div className="ck-product-fields">
        <label className="field">Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Phone<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
      </div>
      <button className="ck-btn ck-btn-g" disabled={!form.name.trim() || !form.phone.trim()}
        onClick={() => void run(async () => { await trackerApi.createDesigner(form.name.trim(), form.phone.trim()); setForm({ name: '', phone: '' }); await load(); }, 'Designer added.')}>Add designer</button>
      <Messages error={error} notice={notice} />
    </section>
  );
}

function TimeSlotsTab({ schools }: { schools: SchoolOption[] }) {
  const [schoolId, setSchoolId] = useState('');
  const [rows, setRows] = useState<SlaRow[]>([]);
  const [edit, setEdit] = useState<Record<number, { value: string; unit: string }>>({});
  const { error, notice, run } = useAction();
  const load = useCallback(async (id: string) => { setRows(id ? await trackerApi.timeSlots(Number(id)) : []); }, []);
  useEffect(() => { load(schoolId).catch(() => setRows([])); }, [schoolId, load]);
  return (
    <section>
      <h3>Time slots per step</h3>
      <p className="ck-product-muted">Working days are Monday to Friday; hour slots pause over the weekend. Steps 8 and 10 have no timer.</p>
      <label className="field">School
        <select value={schoolId} onChange={(e) => setSchoolId(e.target.value)}>
          <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </label>
      {schoolId && (
        <div className="ck-table-wrap"><table className="ck-table">
          <thead><tr><th>Step</th><th>Current</th><th>Source</th><th>Change to</th><th /></tr></thead>
          <tbody>{rows.map((r) => {
            const draft = edit[r.step] ?? { value: r.sla ? String(r.sla.value) : '', unit: r.sla?.unit ?? 'HOURS' };
            return (
              <tr key={r.step}>
                <td>{r.step}. {r.title}</td>
                <td>{r.sla ? slaLabel({ sla: r.sla } as never) : 'No timer'}</td>
                <td>{r.sla?.source === 'SCHOOL' ? 'School override' : r.sla ? 'Default' : '—'}</td>
                <td className="ck-trk-actions">
                  <input aria-label={`Value for step ${r.step}`} inputMode="numeric" style={{ width: 70 }} value={draft.value} onChange={(e) => setEdit({ ...edit, [r.step]: { ...draft, value: e.target.value } })} />
                  <select aria-label={`Unit for step ${r.step}`} value={draft.unit} onChange={(e) => setEdit({ ...edit, [r.step]: { ...draft, unit: e.target.value } })}>
                    <option value="HOURS">hours</option><option value="WORKING_DAYS">working days</option>
                  </select>
                </td>
                <td className="ck-trk-actions">
                  <button className="ck-btn ck-btn-g" disabled={!/^\d+$/.test(draft.value) || Number(draft.value) < 1}
                    onClick={() => void run(async () => { await trackerApi.setTimeSlot(Number(schoolId), r.step, Number(draft.value), draft.unit); await load(schoolId); }, `Step ${r.step} time slot saved.`)}>Save</button>
                  {r.sla?.source === 'SCHOOL' && <button className="ck-btn ck-btn-ghost" onClick={() => void run(async () => { await trackerApi.clearTimeSlot(Number(schoolId), r.step); await load(schoolId); }, `Step ${r.step} back to default.`)}>Use default</button>}
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

/** Superadmin's set-up for the notebook tracker: who operates it, who designs, and how long each step may take. */
export function NotebookAdminPanel() {
  const [tab, setTab] = useState<'operators' | 'designers' | 'slots'>('operators');
  const [schools, setSchools] = useState<SchoolOption[]>([]);
  useEffect(() => {
    trackerApi.schools().then(setSchools).catch(() => setSchools([]));
  }, []);
  return (
    <div className="ck-trk">
      <h2>Notebook workflow set-up</h2>
      <div className="ck-trk-actions" role="tablist" aria-label="Set-up sections">
        {([['operators', 'Operators'], ['designers', 'Designers'], ['slots', 'Time slots']] as const).map(([key, label]) => (
          <button key={key} role="tab" aria-selected={tab === key} className={`ck-btn ${tab === key ? 'ck-btn-g' : 'ck-btn-ghost'}`} onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>
      {tab === 'operators' && <OperatorsTab schools={schools} />}
      {tab === 'designers' && <DesignersTab />}
      {tab === 'slots' && <TimeSlotsTab schools={schools} />}
    </div>
  );
}
