import { useEffect, useId, useRef, useState } from 'react';
import { Check, ExternalLink, RefreshCw, Save } from 'lucide-react';
import { createNotebookTrackerClient, type NotebookTrackerClient } from '../../generated/notebookTrackerClient';
import api from '../../services/api';
import { formatSchoolDateTime } from '../../utils/schoolLocalization';
import { errorMessage } from './api';
import './product-form.css';

export type ProductionStageStatus = 'LOCKED' | 'ACTIVE' | 'COMPLETED';
export interface ProductionStage {
  code: string; order: number; title: string; status: ProductionStageStatus;
  /** Redacted to {} by the server for school-side viewers. */
  data: Record<string, unknown>; note: string | null; completedAt: string | null;
}
/** GET /catalog/orders/{id}/production-workflow. The server decides who may edit or complete; this screen only follows it. */
export interface ProductionWorkflowView {
  orderId: string; category: string; status: 'ACTIVE' | 'COMPLETED'; currentStage: string; version: number;
  canEdit: boolean; canComplete: boolean; stages: ProductionStage[];
}

const UNREADABLE = 'The production workflow could not be read. Please refresh the order.';
const STATUS_LABEL: Record<ProductionStageStatus, string> = { COMPLETED: 'Completed', ACTIVE: 'In progress', LOCKED: 'Not started' };
/**
 * The school-scoped links the notebook tracker already keeps (operations-service); product orders read the same rows.
 * They go through the generated, contract-checked client, so each key here must be a target the contract accepts.
 */
const whatsAppGroupLinks = createNotebookTrackerClient(api);
type WhatsAppGroupTarget = Parameters<NotebookTrackerClient['saveSchoolWhatsAppGroupLink']>[0]['target'];
const WHATSAPP_GROUPS = [
  { key: 'SCHOOL_GROUP', label: 'School group' },
  { key: 'INTERNAL_APPROVAL', label: 'Internal team' },
  { key: 'PRODUCT_VENDOR', label: 'Vendor group' },
] as const satisfies ReadonlyArray<{ key: WhatsAppGroupTarget; label: string }>;
type WhatsAppGroupKey = typeof WHATSAPP_GROUPS[number]['key'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
const optionalString = (value: unknown) => value == null || typeof value === 'string';

function parseWorkflow(value: unknown): ProductionWorkflowView {
  if (!isRecord(value) || typeof value.orderId !== 'string' || typeof value.category !== 'string'
    || !['ACTIVE', 'COMPLETED'].includes(String(value.status)) || typeof value.currentStage !== 'string'
    || typeof value.version !== 'number' || !Array.isArray(value.stages)
    || !value.stages.every((stage: unknown) => isRecord(stage) && typeof stage.code === 'string' && typeof stage.order === 'number'
      && typeof stage.title === 'string' && ['LOCKED', 'ACTIVE', 'COMPLETED'].includes(String(stage.status))
      && (stage.data == null || isRecord(stage.data)) && optionalString(stage.note) && optionalString(stage.completedAt))) {
    throw new Error(UNREADABLE);
  }
  const stages = (value.stages as Record<string, unknown>[]).map((stage): ProductionStage => ({
    code: stage.code as string, order: stage.order as number, title: stage.title as string,
    status: stage.status as ProductionStageStatus, data: isRecord(stage.data) ? stage.data : {},
    note: typeof stage.note === 'string' ? stage.note : null,
    completedAt: typeof stage.completedAt === 'string' ? stage.completedAt : null,
  })).sort((left, right) => left.order - right.order);
  const canEdit = value.canEdit === true;
  return {
    orderId: value.orderId, category: value.category, status: value.status as ProductionWorkflowView['status'],
    currentStage: value.currentStage, version: value.version, canEdit, canComplete: canEdit && value.canComplete === true, stages,
  };
}
function parseLinks(value: unknown): Record<string, string> {
  if (!isRecord(value) || Object.values(value).some((link) => typeof link !== 'string')) {
    throw new Error('WhatsApp group links could not be read.');
  }
  return value as Record<string, string>;
}
/**
 * Mirrors NotebookTrackerService.validateWhatsAppInviteUrl, which parses the raw text with java.net.URI: https and
 * chat.whatsapp.com in any case, an empty port at most, and no userinfo, query or fragment (even empty ones). The check
 * runs on the raw text, not on `new URL()`, because the browser parser normalises inputs the server refuses (`:443`,
 * `https://@…`, `https:chat…`, backslashes, dot segments, a percent-encoded host). No `u` flag, so `i` never folds a
 * non-ASCII letter onto an ASCII one.
 */
const WHATSAPP_INVITE = /^https:\/\/chat\.whatsapp\.com:?\/[A-Za-z0-9_-]{10,64}$/i;
const isWhatsAppInvite = (value: string) => WHATSAPP_INVITE.test(value);
const stageDetails = (stage?: ProductionStage | null) => (typeof stage?.data.details === 'string' ? stage.data.details : '');
const activeOf = (workflow: ProductionWorkflowView) => workflow.stages.find((stage) => stage.status === 'ACTIVE') ?? null;
/** The active stage, or for a finished workflow the last completed one, so there is always something to read. */
function defaultSelection(workflow: ProductionWorkflowView): string {
  const active = activeOf(workflow);
  if (active) return active.code;
  const done = workflow.stages.filter((stage) => stage.status === 'COMPLETED');
  return done.find((stage) => stage.code === workflow.currentStage)?.code ?? done[done.length - 1]?.code ?? '';
}
function statusLine(stage: ProductionStage): string {
  if (stage.status !== 'COMPLETED' || !stage.completedAt || Number.isNaN(Date.parse(stage.completedAt))) return STATUS_LABEL[stage.status];
  return `Completed ${formatSchoolDateTime(stage.completedAt)}`;
}

/** Unsaved text for the active stage. A null field has not been touched, so it keeps showing the server's value. */
type Draft = { code: string; details: string | null; note: string | null };

export function ProductOrderWorkflow({ orderId, schoolId, refreshKey = '', staff, superadmin }: {
  orderId: string;
  /** The order's school; WhatsApp group links are kept per school. */
  schoolId?: number | null;
  /** Changes whenever the parent order changes (status, version or an explicit refresh), which refetches the workflow. */
  refreshKey?: string;
  /** SUPERADMIN or OPERATIONS: sees stage details and the WhatsApp groups. School-side users see progress only. */
  staff: boolean;
  /** Only a superadmin changes WhatsApp group links. */
  superadmin: boolean;
}) {
  const base = `/catalog/orders/${encodeURIComponent(orderId)}/production-workflow`;
  const [workflow, setWorkflow] = useState<ProductionWorkflowView | null>(null);
  const [selectedCode, setSelectedCode] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [attempt, setAttempt] = useState(0);
  const latest = useRef<{ version: number; activeCode: string | null } | null>(null);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  /**
   * 'reset' follows a successful save or completion: the server now holds what was typed. 'keep' is any other refresh,
   * which must not throw away unsaved text while the same stage is still active (a failed save, a parent refresh).
   */
  const apply = (next: ProductionWorkflowView, mode: 'reset' | 'keep') => {
    const previous = latest.current;
    if (previous && next.version < previous.version) return; // A slower, older response must not roll the view back.
    const activeCode = activeOf(next)?.code ?? null;
    const moved = !previous || previous.activeCode !== activeCode;
    latest.current = { version: next.version, activeCode };
    setWorkflow(next);
    setDraft((current) => (mode === 'keep' && !moved && current?.code === activeCode ? current : null));
    setSelectedCode((current) => (mode === 'keep' && !moved && next.stages.some((stage) => stage.code === current && stage.status !== 'LOCKED')
      ? current : defaultSelection(next)));
  };

  useEffect(() => {
    let active = true;
    setLoadError('');
    api.get<unknown>(base).then(({ data }) => {
      const next = parseWorkflow(data);
      if (active) apply(next, 'keep');
    }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause, 'Unable to load production stages.')); });
    return () => { active = false; };
    // apply only touches state setters and refs, so it is safe to leave out.
  }, [base, refreshKey, attempt]);

  const activeStage = workflow ? activeOf(workflow) : null;
  const selected = workflow?.stages.find((stage) => stage.code === selectedCode) ?? null;
  const completed = workflow?.stages.filter((stage) => stage.status === 'COMPLETED').length ?? 0;
  const serverDetails = stageDetails(activeStage);
  const serverNote = activeStage?.note ?? '';
  const ownDraft = draft && activeStage && draft.code === activeStage.code ? draft : null;
  const details = ownDraft?.details ?? serverDetails;
  const note = ownDraft?.note ?? serverNote;
  const dirty = details !== serverDetails || note !== serverNote;
  const editing = !!workflow?.canEdit && !!activeStage && selected?.code === activeStage.code;

  const edit = (field: 'details' | 'note', value: string) => {
    if (!activeStage) return;
    const code = activeStage.code;
    setDraft((current) => ({
      code, details: current?.code === code ? current.details : null, note: current?.code === code ? current.note : null, [field]: value,
    }));
  };
  const stageUrl = (code: string) => `${base}/stages/${encodeURIComponent(code)}`;
  const putStage = async (current: ProductionWorkflowView, stage: ProductionStage) => parseWorkflow((await api.put<unknown>(stageUrl(stage.code),
    { data: { details }, note: note.trim() ? note : null, expectedVersion: current.version })).data);
  const mutate = async (operation: (current: ProductionWorkflowView) => Promise<ProductionWorkflowView>, success: string) => {
    if (!workflow || busy) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const next = await operation(workflow);
      if (!mounted.current) return;
      apply(next, 'reset');
      setNotice(success);
    } catch (cause) {
      if (!mounted.current) return;
      setError(errorMessage(cause));
      // Pick up the new version and stage states, but keep what the user typed for the same stage.
      try {
        const next = parseWorkflow((await api.get<unknown>(base)).data);
        if (mounted.current) apply(next, 'keep');
      } catch { /* Keep the visible workflow; the failure is already shown. */ }
    } finally {
      if (mounted.current) setBusy(false);
    }
  };
  const save = () => {
    if (!activeStage) return;
    void mutate((current) => putStage(current, activeStage), 'Stage update saved.');
  };
  // Completing never drops typed text: unsaved changes are saved first, then the stage is completed at the new version.
  const complete = () => {
    if (!activeStage) return;
    void mutate(async (current) => {
      const version = dirty ? (await putStage(current, activeStage)).version : current.version;
      return parseWorkflow((await api.post<unknown>(`${stageUrl(activeStage.code)}/complete`, { expectedVersion: version })).data);
    }, 'Stage completed.');
  };

  return <section className="ck-product-workflow" aria-label="Production workflow">
    <div className="ck-product-section-head">
      <h2>Production workflow</h2>
      {workflow && <span className="ck-status">{workflow.status === 'COMPLETED' ? 'Completed' : `${completed} / ${workflow.stages.length} stages`}</span>}
    </div>
    {error && <div className="ck-alert ck-alert-re" role="alert">{error}</div>}
    {notice && <div className="ck-alert ck-alert-g" role="status">{notice}</div>}
    {loadError && <div className="ck-alert ck-alert-re ck-product-workflow-load-error" role="alert">
      <span>{loadError}</span>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={16} aria-hidden="true" />Retry</button>
    </div>}
    {!workflow ? !loadError && <p role="status">Loading production stages…</p> : <>
      {!workflow.canEdit && <p className="ck-product-muted">{staff ? 'This workflow is read-only.' : 'The production team updates these stages as your order moves forward.'}</p>}
      <ol className="ck-product-workflow-stages" aria-label="Production stages">
        {workflow.stages.map((stage) => {
          const isSelected = selectedCode === stage.code;
          return <li key={stage.code}
            className={`ck-product-workflow-stage ck-product-workflow-${stage.status.toLowerCase()}${isSelected ? ' ck-product-workflow-selected' : ''}`}>
            <button type="button" className="ck-btn ck-btn-ghost" disabled={stage.status === 'LOCKED'}
              aria-current={isSelected ? 'step' : undefined} onClick={() => setSelectedCode(stage.code)}>
              <span className="ck-product-workflow-stage-no">{String(stage.order).padStart(2, '0')}</span>
              <span className="ck-product-workflow-stage-name">{stage.title}<small>{STATUS_LABEL[stage.status]}</small></span>
            </button>
          </li>;
        })}
      </ol>
      {editing && activeStage ? <div className="ck-product-workflow-editor">
        <h3>{activeStage.title}</h3>
        <label className="field">Production update
          <textarea value={details} maxLength={4000} disabled={busy} onChange={(event) => edit('details', event.target.value)} />
        </label>
        <label className="field">Stage note
          <textarea value={note} maxLength={4000} disabled={busy} onChange={(event) => edit('note', event.target.value)} />
        </label>
        <div className="ck-product-actions">
          {!workflow.canComplete ? <span className="ck-product-muted">A superadmin completes this stage.</span>
            : dirty && <span className="ck-product-muted">Completing the stage saves this update first.</span>}
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy || !dirty} onClick={save}><Save size={16} aria-hidden="true" />Save update</button>
          {workflow.canComplete && <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={complete}><Check size={16} aria-hidden="true" />Complete stage</button>}
        </div>
      </div> : selected && <StageRecord stage={selected} staff={staff} />}
    </>}
    {staff && typeof schoolId === 'number' && <WhatsAppGroups schoolId={schoolId} editable={superadmin} />}
  </section>;
}

/** A read-only stage: what was recorded for staff, and only its status and completion time for school-side viewers. */
function StageRecord({ stage, staff }: { stage: ProductionStage; staff: boolean }) {
  const details = stageDetails(stage);
  return <div className="ck-product-workflow-record">
    <h3>{stage.title}</h3>
    <p className="ck-product-muted">{statusLine(stage)}</p>
    {staff && <dl>
      <div><dt>Production update</dt><dd>{details || 'No update recorded.'}</dd></div>
      <div><dt>Stage note</dt><dd>{stage.note || 'No note recorded.'}</dd></div>
    </dl>}
  </div>;
}

/**
 * The school's WhatsApp groups, kept by operations-service and shared with the notebook tracker. It loads and saves on
 * its own, so a link change never reloads the stages or touches a stage draft, and a failure here never hides the stages.
 * The server decides who may read the links and stores each one in its own canonical form; this shows what it returns.
 */
function WhatsAppGroups({ schoolId, editable }: { schoolId: number; editable: boolean }) {
  const hintId = useId();
  const [links, setLinks] = useState<Record<string, string> | null>(null);
  const [inputs, setInputs] = useState<Record<string, string>>({});
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState<WhatsAppGroupKey | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setLoadError(''); setLinks(null);
    // The generated type is only what the contract promises; parseLinks checks what actually arrived.
    whatsAppGroupLinks.getSchoolWhatsAppGroupLinks({ schoolId }).then((data: unknown) => {
      const parsed = parseLinks(data);
      if (active) { setLinks(parsed); setInputs(Object.fromEntries(WHATSAPP_GROUPS.map(({ key }) => [key, parsed[key] ?? '']))); }
    }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause, 'Unable to load WhatsApp groups.')); });
    return () => { active = false; };
  }, [schoolId, attempt]);

  const save = async (key: WhatsAppGroupKey, label: string) => {
    setSaving(key); setError(''); setNotice('');
    try {
      const data: unknown = await whatsAppGroupLinks.saveSchoolWhatsAppGroupLink({ schoolId, target: key }, { url: (inputs[key] ?? '').trim() });
      const next = parseLinks(data);
      setLinks(next);
      setInputs((current) => ({ ...current, [key]: next[key] ?? '' }));
      setNotice(next[key] ? `${label} link saved.` : `${label} link removed.`);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setSaving(null);
    }
  };

  return <section className="ck-product-workflow-groups" aria-label="WhatsApp groups">
    <h3>WhatsApp groups</h3>
    <p className="ck-product-muted">These links apply to every order from this school.</p>
    {loadError && <div className="ck-alert ck-alert-re ck-product-workflow-load-error" role="alert">
      <span>{loadError}</span>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={16} aria-hidden="true" />Retry</button>
    </div>}
    {error && <div className="ck-alert ck-alert-re" role="alert">{error}</div>}
    {notice && <div className="ck-alert ck-alert-g" role="status">{notice}</div>}
    {!links ? !loadError && <p role="status">Loading WhatsApp groups…</p> : WHATSAPP_GROUPS.map(({ key, label }) => {
      const saved = links[key] ?? '';
      const value = inputs[key] ?? '';
      const trimmed = value.trim();
      const invalid = trimmed !== '' && !isWhatsAppInvite(trimmed);
      const verb = saving === key ? 'Saving' : !trimmed && saved ? 'Remove' : 'Save';
      return <div className="ck-product-workflow-group" key={key} role="group" aria-labelledby={`${hintId}-${key}-label`}>
        <strong id={`${hintId}-${key}-label`}>{label}</strong>
        {saved && isWhatsAppInvite(saved)
          ? <a href={saved} target="_blank" rel="noopener noreferrer">Open {label.toLowerCase()} <ExternalLink size={14} aria-hidden="true" /></a>
          : <span className="ck-product-muted">Not set</span>}
        {editable && <div className="ck-product-workflow-group-edit">
          <input aria-label={`${label} invite URL`} type="url" maxLength={2048} placeholder="https://chat.whatsapp.com/…" value={value}
            aria-invalid={invalid || undefined} aria-describedby={invalid ? `${hintId}-${key}` : undefined} disabled={saving !== null}
            onChange={(event) => setInputs((current) => ({ ...current, [key]: event.target.value }))} />
          <button type="button" className="ck-btn ck-btn-ghost" disabled={saving !== null || invalid || trimmed === saved}
            aria-label={`${verb} ${label.toLowerCase()} link`}
            onClick={() => void save(key, label)}>{saving === key ? 'Saving…' : verb}</button>
          {invalid && <span id={`${hintId}-${key}`} className="ck-product-error">Enter a chat.whatsapp.com group invite link, or leave it empty to remove it.</span>}
        </div>}
      </div>;
    })}
  </section>;
}
