import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { ExternalLink, RefreshCw } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { INVALID_INVITE_MESSAGE, normaliseInvite, safeInviteHref } from '../../../features/orders/shared/invite';
import {
  WORKFLOW_DEFINITIONS, setupApi, workflowError, type InternalApprovalGroupView, type OperatorRow, type SchoolGroupTarget, type SchoolOption,
  type SlaUnit, type WorkflowDefinitionId,
} from '../../../features/orders/vendors/api';
import { VendorDirectory } from '../../../features/orders/vendors/VendorDirectory';
import '../../../features/orders/vendors/setup.css';

function useAction() {
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const run = async (operation: () => Promise<unknown>, done: string) => {
    setError(''); setNotice('');
    try { await operation(); setNotice(done); return true; } catch (e) { setError(workflowError(e)); return false; }
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
    catch (e) { if (request === latest.current) setError(workflowError(e, failure)); }
  }, [load, failure]);
  useEffect(() => { setRows(null); setError(''); void reload(); }, [reload]);
  return { rows, error, reload };
}

function Messages({ error, notice }: { error: string; notice: string }) {
  return <>{error && <p className="ck-setup-error" role="alert">{error}</p>}{notice && <p className="ck-setup-notice" role="status">{notice}</p>}</>;
}

function LoadFailure({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="ck-alert ck-alert-re">
      <span role="alert">{message}</span>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={onRetry}><RefreshCw size={15} aria-hidden="true" /> Retry</button>
    </div>
  );
}

/** "4 hours · 24/7" or "2 working days · Mon–Fri" (design D23); hour slots run around the clock. */
function slaText(sla: { value: number; unit: SlaUnit } | null | undefined): string {
  if (!sla) return 'No timer';
  if (sla.unit === 'WORKING_DAYS') return `${sla.value} working ${sla.value === 1 ? 'day' : 'days'} · Mon–Fri`;
  return `${sla.value} ${sla.value === 1 ? 'hour' : 'hours'} · 24/7`;
}

const validSlaValue = (value: string) => /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 9999;

interface SlaDraft { value: string; unit: SlaUnit }

/** Value, unit and Save on one line, wrapping only when the cell is too narrow; every control is labelled by its step. */
function SlaEditor({ step, label, draft, onChange, onSave }: {
  step: number; label: string; draft: SlaDraft; onChange: (draft: SlaDraft) => void; onSave: () => void;
}) {
  return (
    <div className="ck-setup-slot">
      <input className="ck-setup-slot-value" aria-label={`${label} value for step ${step}`} inputMode="numeric" value={draft.value}
        onChange={(e) => onChange({ ...draft, value: e.target.value })} />
      <select className="ck-setup-slot-unit" aria-label={`${label} unit for step ${step}`} value={draft.unit}
        onChange={(e) => onChange({ ...draft, unit: e.target.value as SlaUnit })}>
        <option value="HOURS">hours</option><option value="WORKING_DAYS">working days</option>
      </select>
      <button type="button" className="ck-btn ck-btn-g ck-btn-sm" aria-label={`Save ${label.toLowerCase()} for step ${step}`}
        disabled={!validSlaValue(draft.value)} onClick={onSave}>Save</button>
    </div>
  );
}

function OperatorsTab({ schools }: { schools: SchoolOption[] }) {
  const operators = useList(setupApi.operators, 'Unable to load the operator assignments.');
  const users = useList(setupApi.operatorUsers, 'Unable to load the operators.');
  const categories = useList(setupApi.categories, 'Unable to load the catalogue items.');
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
    await run(async () => { await setupApi.revokeAssignment(row.userId, row.assignmentId); await operators.reload(); }, 'Assignment revoked.');
    setRevokeBusy(false); setRevoking(null);
  };
  return (
    <section className="ck-setup-section">
      <div className="ck-setup-block">
        <h3>Operator assignments</h3>
        <p className="ck-setup-muted">A school-wide operator handles every workflow order of the school; an item-specific one only the item named here.</p>
        {operators.error ? <LoadFailure message={operators.error} onRetry={() => void operators.reload()} /> : (
          <div className="ck-table-wrap"><table className="ck-table">
            <thead><tr><th>Operator</th><th>School</th><th>Scope</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
            <tbody>{operators.rows === null ? <tr><td colSpan={4} className="ck-setup-muted">Loading operator assignments…</td></tr>
              : operators.rows.length === 0 ? <tr><td colSpan={4} className="ck-setup-muted">No operator assignments.</td></tr>
                : operators.rows.map((r) => (
                  <tr key={r.assignmentId}><td>{r.fullName}<div className="ck-setup-muted">{r.email}</div></td><td>{name(r.schoolId)}</td>
                    <td>{scope(r)}</td>
                    <td><button type="button" className="ck-btn ck-btn-ghost" aria-label={`Revoke ${r.fullName} at ${name(r.schoolId)}`} onClick={() => setRevoking(r)}>Revoke</button></td></tr>
                ))}</tbody>
          </table></div>
        )}
        {users.error && <LoadFailure message={users.error} onRetry={() => void users.reload()} />}
        {categories.error && <LoadFailure message={categories.error} onRetry={() => void categories.reload()} />}
      </div>
      <div className="ck-setup-block">
        <h4>Scope an operator to one catalogue item at a school</h4>
        <div className="ck-setup-fields">
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
        <div className="ck-setup-actions">
          <button type="button" className="ck-btn ck-btn-g" disabled={!form.userId || !form.schoolId || !form.category}
            onClick={() => void run(async () => { await setupApi.scopeOperator(Number(form.userId), Number(form.schoolId), form.category); await operators.reload(); }, 'Operator scoped.')}>Assign scope</button>
        </div>
      </div>
      <div className="ck-setup-block">
        <h4>Create a new item-specific operator</h4>
        <div className="ck-setup-fields">
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
        <div className="ck-setup-actions">
          <button type="button" className="ck-btn ck-btn-g" disabled={!created.schoolId || !created.category || !created.fullName.trim() || !created.email.trim() || !created.temporaryPassword}
            onClick={() => void run(async () => {
              await setupApi.createScopedOperator(Number(created.schoolId), { fullName: created.fullName.trim(), email: created.email.trim(), temporaryPassword: created.temporaryPassword }, created.category);
              setCreated({ schoolId: '', category: '', fullName: '', email: '', temporaryPassword: '' });
              await operators.reload(); await users.reload();
            }, 'Operator created for that item at that school.')}>Create operator</button>
        </div>
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
  const designers = useList(setupApi.designers, 'Unable to load the designers.');
  const [form, setForm] = useState({ name: '', phone: '' });
  const { error, notice, run } = useAction();
  return (
    <section className="ck-setup-section">
      <div className="ck-setup-block">
        <h3>Designers</h3>
        <p className="ck-setup-muted">Chosen per order on the School & vendor tab; inactive designers stay on the orders that already have them.</p>
        {designers.error ? <LoadFailure message={designers.error} onRetry={() => void designers.reload()} /> : (
          <div className="ck-table-wrap"><table className="ck-table">
            <thead><tr><th>Name</th><th>Phone</th><th>Status</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
            <tbody>{designers.rows === null ? <tr><td colSpan={4} className="ck-setup-muted">Loading designers…</td></tr>
              : designers.rows.length === 0 ? <tr><td colSpan={4} className="ck-setup-muted">No designers yet.</td></tr>
                : designers.rows.map((d) => (
                  <tr key={d.id}><td>{d.name}</td><td>{d.phone ?? '—'}</td><td>{d.active ? 'Active' : 'Inactive'}</td>
                    <td><button type="button" className="ck-btn ck-btn-ghost" aria-label={`${d.active ? 'Deactivate' : 'Activate'} ${d.name}`}
                      onClick={() => void run(async () => { await setupApi.updateDesigner(d.id, { name: d.name, phone: d.phone, active: !d.active }); await designers.reload(); }, d.active ? 'Designer deactivated.' : 'Designer activated.')}>{d.active ? 'Deactivate' : 'Activate'}</button></td></tr>
                ))}</tbody>
          </table></div>
        )}
      </div>
      <div className="ck-setup-block">
        <h4>Add a designer</h4>
        <div className="ck-setup-fields">
          <label className="field">Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label className="field">Phone<input type="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
        </div>
        <div className="ck-setup-actions">
          <button type="button" className="ck-btn ck-btn-g" disabled={!form.name.trim() || !form.phone.trim()}
            onClick={() => void run(async () => { await setupApi.createDesigner(form.name.trim(), form.phone.trim()); setForm({ name: '', phone: '' }); await designers.reload(); }, 'Designer added.')}>Add designer</button>
        </div>
      </div>
      <Messages error={error} notice={notice} />
    </section>
  );
}

function TimeSlotsTab({ schools }: { schools: SchoolOption[] }) {
  const templates = useList(setupApi.templates, 'Unable to load the workflow templates.');
  const [definitionId, setDefinitionId] = useState<WorkflowDefinitionId>(WORKFLOW_DEFINITIONS[0].id);
  const [schoolId, setSchoolId] = useState('');
  const [defaults, setDefaults] = useState<Record<number, SlaDraft>>({});
  const [overrides, setOverrides] = useState<Record<number, SlaDraft>>({});
  const loadSlots = useCallback(() => (schoolId ? setupApi.timeSlots(Number(schoolId), definitionId) : Promise.resolve([])), [schoolId, definitionId]);
  const slots = useList(loadSlots, 'Unable to load the time slots.');
  const { error, notice, run } = useAction();
  const template = templates.rows?.find((row) => row.definitionId === definitionId) ?? null;
  const definitionLabel = WORKFLOW_DEFINITIONS.find((definition) => definition.id === definitionId)?.label ?? definitionId;
  const chooseTemplate = (id: WorkflowDefinitionId) => { setDefinitionId(id); setDefaults({}); setOverrides({}); };
  const draftOf = (drafts: Record<number, SlaDraft>, step: number, sla: { value: number; unit: SlaUnit } | null | undefined): SlaDraft =>
    drafts[step] ?? { value: sla ? String(sla.value) : '', unit: sla?.unit ?? 'HOURS' };

  return (
    <section className="ck-setup-section">
      <div className="ck-setup-block">
        <h3>Time slots per step</h3>
        <p className="ck-setup-muted">Hour slots use continuous elapsed time, including weekends. Working-day slots run Monday to Friday. A new time slot applies to stages that start after it is saved.</p>
        <div className="ck-setup-fields">
          <label className="field">Workflow template
            <select value={definitionId} onChange={(e) => chooseTemplate(e.target.value as WorkflowDefinitionId)}>
              {WORKFLOW_DEFINITIONS.map((definition) => <option key={definition.id} value={definition.id}>{definition.label}</option>)}
            </select>
          </label>
        </div>
      </div>
      <div className="ck-setup-block">
        <h4>Default for every school</h4>
        {templates.error ? <LoadFailure message={templates.error} onRetry={() => void templates.reload()} />
          : templates.rows === null ? <p className="ck-setup-muted" role="status">Loading templates…</p>
            : !template ? <p className="ck-setup-muted">The {definitionLabel} is not available.</p> : (
              <div className="ck-table-wrap"><table className="ck-table">
                <caption className="ck-sr-only">Default time slots of the {definitionLabel}</caption>
                <thead><tr><th>Step</th><th>Default</th><th>Change to</th></tr></thead>
                <tbody>{template.steps.map((step) => (
                  <tr key={step.order}>
                    <td>{step.order}. {step.title}{step.customisedOnly && <span className="ck-setup-step-note">Customised orders only</span>}</td>
                    <td>{slaText(step.defaultSla)}</td>
                    <td><SlaEditor step={step.order} label="Default" draft={draftOf(defaults, step.order, step.defaultSla)}
                      onChange={(draft) => setDefaults({ ...defaults, [step.order]: draft })}
                      onSave={() => {
                        const draft = draftOf(defaults, step.order, step.defaultSla);
                        void run(async () => {
                          await setupApi.setDefaultSla(definitionId, step.order, Number(draft.value), draft.unit);
                          await templates.reload();
                          if (schoolId) await slots.reload();
                        }, `Step ${step.order} default saved.`);
                      }} /></td>
                  </tr>
                ))}</tbody>
              </table></div>
            )}
      </div>
      <div className="ck-setup-block">
        <h4>School overrides</h4>
        <div className="ck-setup-fields">
          <label className="field">School
            <select value={schoolId} onChange={(e) => { setSchoolId(e.target.value); setOverrides({}); }}>
              <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        </div>
        {schoolId && slots.error && <LoadFailure message={slots.error} onRetry={() => void slots.reload()} />}
        {schoolId && !slots.error && slots.rows === null && <p className="ck-setup-muted" role="status">Loading time slots…</p>}
        {schoolId && !slots.error && slots.rows && (
          <div className="ck-table-wrap"><table className="ck-table">
            <caption className="ck-sr-only">Time slots at the chosen school</caption>
            <thead><tr><th>Step</th><th>Current</th><th>Source</th><th>Change to</th><th><span className="ck-sr-only">Actions</span></th></tr></thead>
            <tbody>{slots.rows.map((r) => (
              <tr key={r.step}>
                <td>{r.step}. {r.title}</td>
                <td>{slaText(r.effective)}</td>
                <td>{r.effective?.source === 'SCHOOL' ? 'School override' : r.effective ? 'Default' : '—'}</td>
                <td><SlaEditor step={r.step} label="School" draft={draftOf(overrides, r.step, r.effective)}
                  onChange={(draft) => setOverrides({ ...overrides, [r.step]: draft })}
                  onSave={() => {
                    const draft = draftOf(overrides, r.step, r.effective);
                    void run(async () => { await setupApi.setTimeSlot(Number(schoolId), definitionId, r.step, Number(draft.value), draft.unit); await slots.reload(); }, `Step ${r.step} time slot saved.`);
                  }} /></td>
                <td>{r.effective?.source === 'SCHOOL' && (
                  <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-label={`Use the default for step ${r.step}`}
                    onClick={() => void run(async () => {
                      await setupApi.clearTimeSlot(Number(schoolId), definitionId, r.step);
                      setOverrides(({ [r.step]: _cleared, ...rest }) => rest);
                      await slots.reload();
                    }, `Step ${r.step} back to default.`)}>Use default</button>
                )}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </div>
      <Messages error={error} notice={notice} />
    </section>
  );
}

/** One WhatsApp group link: the saved link to open, and (when editable) an input whose empty value removes it. */
function GroupLinkRow({ label, saved, value, saving, onChange, onSave }: {
  label: string; saved: string | null; value: string; saving: boolean; onChange: (value: string) => void; onSave: () => void;
}) {
  const ids = useId();
  const trimmed = value.trim();
  const invalid = trimmed !== '' && !normaliseInvite(trimmed);
  const href = safeInviteHref(saved);
  const unchanged = (trimmed ? normaliseInvite(trimmed) : '') === (saved ?? '');
  const verb = saving ? 'Saving…' : !trimmed && saved ? 'Remove' : 'Save';
  return (
    <div className="ck-setup-group" role="group" aria-labelledby={`${ids}-label`}>
      <strong id={`${ids}-label`}>{label}</strong>
      {href ? <a href={href} target="_blank" rel="noopener noreferrer">Open {label.toLowerCase()} <ExternalLink size={14} aria-hidden="true" /></a>
        : <span className="ck-setup-muted">Not set</span>}
      <div className="ck-setup-group-edit">
        <input aria-label={`${label} invite link`} type="url" inputMode="url" placeholder="https://chat.whatsapp.com/…" value={value} disabled={saving}
          aria-invalid={invalid || undefined} aria-describedby={invalid ? `${ids}-error` : undefined} onChange={(e) => onChange(e.target.value)} />
        <button type="button" className="ck-btn ck-btn-ghost" disabled={saving || invalid || unchanged} aria-label={`${verb.replace('…', '')} ${label.toLowerCase()} link`}
          onClick={onSave}>{verb}</button>
        {invalid && <span id={`${ids}-error`} className="ck-setup-error">{INVALID_INVITE_MESSAGE} Leave it empty to remove the link.</span>}
      </div>
    </div>
  );
}

const SCHOOL_TARGETS: ReadonlyArray<{ key: SchoolGroupTarget; label: string }> = [
  { key: 'SCHOOL_GROUP', label: 'School group' },
  { key: 'INTERNAL_APPROVAL', label: 'School internal approval group' },
];

function GroupsTab({ schools }: { schools: SchoolOption[] }) {
  const [global, setGlobal] = useState<InternalApprovalGroupView | null>(null);
  const [globalError, setGlobalError] = useState('');
  const [globalInput, setGlobalInput] = useState('');
  const [globalAttempt, setGlobalAttempt] = useState(0);
  const [schoolId, setSchoolId] = useState('');
  const [links, setLinks] = useState<Record<string, string> | null>(null);
  const [linksError, setLinksError] = useState('');
  const [inputs, setInputs] = useState<Record<string, string>>({});
  const [linksAttempt, setLinksAttempt] = useState(0);
  const [saving, setSaving] = useState<string | null>(null);
  const { error, notice, run } = useAction();

  useEffect(() => {
    let active = true;
    setGlobalError('');
    setupApi.internalApprovalGroup().then(
      (view) => { if (active) { setGlobal(view); setGlobalInput(view.inviteUrl ?? ''); } },
      (e: unknown) => { if (active) setGlobalError(workflowError(e, 'Unable to load the internal approval group.')); },
    );
    return () => { active = false; };
  }, [globalAttempt]);

  useEffect(() => {
    if (!schoolId) { setLinks(null); return undefined; }
    let active = true;
    setLinks(null); setLinksError('');
    setupApi.schoolGroups(Number(schoolId)).then(
      (next) => { if (active) { setLinks(next); setInputs(Object.fromEntries(SCHOOL_TARGETS.map(({ key }) => [key, next[key] ?? '']))); } },
      (e: unknown) => { if (active) setLinksError(workflowError(e, 'Unable to load the school groups.')); },
    );
    return () => { active = false; };
  }, [schoolId, linksAttempt]);

  const saveGlobal = async () => {
    const trimmed = globalInput.trim();
    setSaving('GLOBAL');
    await run(async () => {
      const view = await setupApi.setInternalApprovalGroup(trimmed ? normaliseInvite(trimmed) : null);
      setGlobal(view); setGlobalInput(view.inviteUrl ?? '');
    }, trimmed ? 'Internal approval group saved.' : 'Internal approval group removed.');
    setSaving(null);
  };
  const saveSchool = async (target: SchoolGroupTarget, label: string) => {
    const trimmed = (inputs[target] ?? '').trim();
    setSaving(target);
    await run(async () => {
      const next = await setupApi.saveSchoolGroup(Number(schoolId), target, trimmed ? normaliseInvite(trimmed) ?? trimmed : '');
      setLinks(next); setInputs((current) => ({ ...current, [target]: next[target] ?? '' }));
    }, trimmed ? `${label} link saved.` : `${label} link removed.`);
    setSaving(null);
  };

  return (
    <section className="ck-setup-section">
      <div className="ck-setup-block">
        <h3>Internal approval group</h3>
        <p className="ck-setup-muted">One WhatsApp group for internal review and approval, opened from Pre-Print Quality Check and Final Approval. A school's own internal approval group, where one is set below, is used instead for that school.</p>
        {globalError ? <LoadFailure message={globalError} onRetry={() => setGlobalAttempt((value) => value + 1)} />
          : global === null ? <p className="ck-setup-muted" role="status">Loading the internal approval group…</p>
            : <GroupLinkRow label="Internal approval group" saved={global.inviteUrl} value={globalInput} saving={saving === 'GLOBAL'}
              onChange={setGlobalInput} onSave={() => void saveGlobal()} />}
      </div>
      <div className="ck-setup-block">
        <h3>School groups</h3>
        <p className="ck-setup-muted">Each school's WhatsApp group is opened from the order's School Group Chat card. Vendor groups are kept on each vendor, under Vendors.</p>
        <div className="ck-setup-fields">
          <label className="field">School
            <select value={schoolId} onChange={(e) => setSchoolId(e.target.value)}>
              <option value="">Select school…</option>{schools.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        </div>
        {schoolId && linksError && <LoadFailure message={linksError} onRetry={() => setLinksAttempt((value) => value + 1)} />}
        {schoolId && !linksError && links === null && <p className="ck-setup-muted" role="status">Loading the school groups…</p>}
        {schoolId && links && SCHOOL_TARGETS.map(({ key, label }) => (
          <GroupLinkRow key={key} label={label} saved={links[key] ?? null} value={inputs[key] ?? ''} saving={saving === key}
            onChange={(value) => setInputs((current) => ({ ...current, [key]: value }))} onSave={() => void saveSchool(key, label)} />
        ))}
      </div>
      <Messages error={error} notice={notice} />
    </section>
  );
}

const TABS = [['vendors', 'Vendors'], ['designers', 'Designers'], ['slots', 'Time slots'], ['groups', 'Groups'], ['operators', 'Operators']] as const;
type Tab = typeof TABS[number][0];

function SetupHeading() {
  return (
    <div>
      <h2>Workflow set-up</h2>
      <p className="ck-setup-muted">Suppliers, designers, time slots and groups used by every item's order workflow.</p>
    </div>
  );
}

/**
 * Superadmin's Workflow set-up (design section 4.7, D42): vendors, designers, time slots, WhatsApp groups and operators.
 * The workspace mounts it for platform admins only; anyone else who reaches it is told why, and nothing is fetched.
 */
export function NotebookAdminPanel() {
  const { user } = useAuth();
  const { can } = usePermissions();
  if (user?.role !== 'SUPERADMIN' && !can('platform:admin')) {
    return (
      <div className="ck-setup">
        <SetupHeading />
        <p className="ck-setup-muted" role="status">Only a superadmin manages workflow set-up. Operators see each order's vendors on its stage cards.</p>
      </div>
    );
  }
  return <WorkflowSetup />;
}

function WorkflowSetup() {
  const [tab, setTab] = useState<Tab>('vendors');
  const schools = useList(setupApi.schools, 'Unable to load the schools.');
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
    <div className="ck-setup">
      <SetupHeading />
      {schools.error && <LoadFailure message={schools.error} onRetry={() => void schools.reload()} />}
      <div className="ck-setup-tabs" role="tablist" aria-label="Set-up sections">
        {TABS.map(([key, label], index) => (
          <button key={key} ref={(node) => { tabs.current[key] = node; }} id={tabId(key)} type="button" role="tab" aria-selected={tab === key}
            aria-controls={tab === key ? panelId : undefined} tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)} onKeyDown={(e) => onKeyDown(e, index)}>{label}</button>
        ))}
      </div>
      <div role="tabpanel" id={panelId} aria-labelledby={tabId(tab)} tabIndex={0}>
        {tab === 'vendors' && <VendorDirectory />}
        {tab === 'designers' && <DesignersTab />}
        {tab === 'slots' && <TimeSlotsTab schools={schools.rows ?? []} />}
        {tab === 'groups' && <GroupsTab schools={schools.rows ?? []} />}
        {tab === 'operators' && <OperatorsTab schools={schools.rows ?? []} />}
      </div>
    </div>
  );
}
