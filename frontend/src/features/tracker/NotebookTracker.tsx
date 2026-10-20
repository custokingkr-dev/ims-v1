import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, Lock, CheckCircle2, Circle, RefreshCw } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { errorMessage } from '../catalog/api';
import { StepWorkArea } from './StepWorkArea';
import { trackerApi } from './api';
import { TBD_STEPS, formatDay, sizeSummary, slaLabel, stepState, wordCount } from './steps';
import type { DesignerRow, OrderSummary, TrackerStep, TrackerView } from './types';
import '../catalog/product-form.css';
import './tracker.css';

function stepClass(step: TrackerStep, selected: boolean): string {
  const state = step.status === 'COMPLETED' ? 'done' : step.status === 'ACTIVE' ? (step.overdue ? 'late' : 'active') : 'locked';
  return `ck-trk-step is-${state}${selected ? ' is-selected' : ''}`;
}
function dueText(step: TrackerStep, timeZone: string): string {
  if (step.status === 'COMPLETED' && step.completedAt) return `Done ${new Date(step.completedAt).toLocaleString('en-IN', { timeZone, dateStyle: 'medium', timeStyle: 'short' })}`;
  if (!step.sla) return step.status === 'ACTIVE' ? 'No timer' : '';
  if (step.status === 'ACTIVE' && step.dueAt) return `${step.overdue ? 'Overdue · was due' : 'Due'} ${new Date(step.dueAt).toLocaleString('en-IN', { timeZone, dateStyle: 'medium', timeStyle: 'short' })}`;
  return slaLabel(step);
}

/** Superadmin gives every open step of the order to one operator; the per-step choice stays in each work area. */
function OrderAssignment({ tracker, operators, operatorsError, onTracker }: {
  tracker: TrackerView; operators: Array<{ userId: number; fullName: string }>; operatorsError: string; onTracker: (next: TrackerView) => void;
}) {
  const [userId, setUserId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const open = tracker.steps.filter((s) => s.status !== 'COMPLETED');
  const assignees = [...new Set(open.map((s) => s.assigneeUserId).filter((id): id is number => id != null))];
  const name = (id: number) => operators.find((o) => o.userId === id)?.fullName ?? `User ${id}`;
  const assign = async () => {
    setBusy(true); setError('');
    try { onTracker(await trackerApi.assignOrder(tracker.orderId, Number(userId))); setUserId(''); }
    catch (e) { setError(errorMessage(e, 'Unable to assign the order.')); } finally { setBusy(false); }
  };
  if (open.length === 0) return null;
  return (
    <section aria-label="Assignment">
      <h4>Assignment</h4>
      <p>{open.some((s) => s.assigneeUserId == null)
        ? (assignees.length ? `Some open steps are not assigned. Assigned: ${assignees.map(name).join(', ')}.` : 'This order is not assigned to an operator.')
        : `Assigned to ${assignees.map(name).join(', ')}.`}</p>
      <label className="field">Assign order to
        <select value={userId} disabled={busy} onChange={(e) => setUserId(e.target.value)}>
          <option value="">Select operator…</option>
          {operators.map((o) => <option key={o.userId} value={o.userId}>{o.fullName}</option>)}
        </select>
      </label>
      {operatorsError && <p className="ck-product-error" role="alert">{operatorsError}</p>}
      {error && <p className="ck-product-error" role="alert">{error}</p>}
      <button type="button" className="ck-btn ck-btn-ghost" disabled={busy || !userId} onClick={() => void assign()}>Assign order</button>
    </section>
  );
}

/** The superadmin / operator view of one notebook order: the tracker's steps, the active work area and the order's people and notes. */
export function NotebookTracker({ orderId, initial, onBack, onChanged }: { orderId: string; initial?: TrackerView; onBack: () => void; onChanged?: () => void }) {
  const { user } = useAuth();
  const [tracker, setTracker] = useState<TrackerView | null>(initial ?? null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<number | null>(initial?.progress.currentStep ?? null);
  const [designers, setDesigners] = useState<DesignerRow[]>([]);
  const [operators, setOperators] = useState<Array<{ userId: number; fullName: string }>>([]);
  const [operatorsError, setOperatorsError] = useState('');
  const [summary, setSummary] = useState<OrderSummary | null>(null);
  const [summaryError, setSummaryError] = useState('');
  const [noteText, setNoteText] = useState('');
  const [noteError, setNoteError] = useState('');
  const superadmin = user?.role === 'SUPERADMIN';

  const accept = useCallback((next: TrackerView) => {
    setTracker(next);
    setSelected((current) => (current != null && next.steps.some((s) => s.step === current) ? current : next.progress.currentStep ?? next.steps[0]?.step ?? null));
    onChanged?.();
  }, [onChanged]);

  useEffect(() => {
    let active = true;
    if (!initial) {
      setError('');
      trackerApi.get(orderId).then((next) => { if (active) { setTracker(next); setSelected(next.progress.currentStep ?? next.steps[0]?.step ?? null); } })
        .catch((e: unknown) => { if (active) setError(errorMessage(e, 'Unable to load this order.')); });
    }
    return () => { active = false; };
  }, [orderId, initial, attempt]);

  useEffect(() => {
    let active = true;
    trackerApi.summary(orderId).then((next) => { if (active) { setSummary(next); setSummaryError(''); } })
      .catch((e: unknown) => { if (active) setSummaryError(errorMessage(e, 'Unable to load the order details.')); });
    return () => { active = false; };
  }, [orderId]);

  useEffect(() => {
    let active = true;
    trackerApi.designers().then((rows) => { if (active) setDesigners(rows); }).catch(() => { /* The designer list is optional context. */ });
    if (superadmin && tracker) {
      trackerApi.operators().then((rows) => {
        if (!active) return;
        const eligible = rows.filter((r) => r.schoolId === tracker.schoolId && (r.catalogCategory === null || r.catalogCategory === 'NOTEBOOKS'));
        setOperators([...new Map(eligible.map((r) => [r.userId, { userId: r.userId, fullName: r.fullName }])).values()]); setOperatorsError('');
      }).catch((e: unknown) => { if (active) setOperatorsError(errorMessage(e, 'The operator list could not be loaded, so the order cannot be assigned now.')); });
    }
    return () => { active = false; };
  }, [superadmin, tracker?.schoolId]);

  if (error) {
    return (
      <div className="ck-trk">
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Back</button></div>
        <div className="ck-alert ck-alert-re" role="alert">{error}</div>
        <div><button type="button" className="ck-btn ck-btn-ghost" onClick={() => { setError(''); setAttempt((n) => n + 1); }}><RefreshCw size={15} aria-hidden="true" /> Retry</button></div>
      </div>
    );
  }
  if (!tracker) return <div className="ck-trk"><p className="ck-product-muted" role="status">Loading order…</p></div>;

  const step = tracker.steps.find((s) => s.step === selected) ?? tracker.steps[0];
  const { progress } = tracker;
  const sizes = summary ? sizeSummary(summary.lines) : [];
  // Notes carry only the author's id: name the ones this screen knows, and say plainly when it does not.
  const author = (id: number) => (id === user?.userId ? 'You' : operators.find((o) => o.userId === id)?.fullName ?? `User ${id}`);
  const addNote = async () => {
    setNoteError('');
    try { accept(await trackerApi.addInternalNote(tracker.orderId, noteText)); setNoteText(''); } catch (e) { setNoteError(errorMessage(e)); }
  };

  return (
    <div className="ck-trk">
      <header className="ck-trk-head" role="group" aria-label={`Order ${tracker.orderId}`}>
        <button type="button" className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Back to orders</button>
        <div>
          <h2>{tracker.orderId}</h2>
          <p className="ck-product-muted">{tracker.schoolName ?? `School ${tracker.schoolId}`} · {tracker.orderedCount.toLocaleString('en-IN')} notebooks · {tracker.customised ? 'Customised' : 'Wholesale'}</p>
        </div>
        <dl className="ck-trk-summary">
          <div><dt>Deliver by</dt><dd>{summary?.requiredByDate ? formatDay(summary.requiredByDate) : '—'}</dd></div>
          {sizes.length > 0 && (
            <div><dt>Sizes and pages</dt><dd><ul className="ck-trk-sizes">
              {sizes.map((s) => <li key={`${s.size}|${s.pages}`}>{`${s.size} · ${s.pages.toLocaleString('en-IN')} pages · ${s.books.toLocaleString('en-IN')} books`}</li>)}
            </ul></dd></div>
          )}
        </dl>
        <div className="ck-trk-progress" role="status">
          {progress.finished ? 'Completed' : `Step ${progress.currentStep} · ${progress.currentTitle}`}
          <span className="ck-product-muted"> — {progress.completed} of {progress.total} done</span>
        </div>
      </header>

      <ol className="ck-trk-stepper" aria-label="Order progress">
        {tracker.steps.map((s) => (
          <li key={s.step} className={stepClass(s, s.step === step?.step)} title={`${s.title}: ${stepState(s)}`}>
            <span className="ck-trk-dot" aria-hidden="true">{s.status === 'COMPLETED' ? <CheckCircle2 size={16} /> : s.status === 'ACTIVE' ? <Circle size={16} /> : <Lock size={14} />}</span>
            <span aria-hidden="true">{s.step}</span>
            <span className="ck-sr-only">Step {s.step}, {s.title}: {stepState(s)}</span>
          </li>
        ))}
      </ol>

      <div className="ck-trk-grid">
        <nav className="ck-trk-stages" aria-label="Stages">
          {tracker.steps.map((s) => (
            <button key={s.step} type="button" className={stepClass(s, s.step === step?.step)} aria-current={s.step === step?.step ? 'step' : undefined}
              disabled={s.status === 'LOCKED'} onClick={() => setSelected(s.step)}>
              <strong>{s.step}. {s.title}</strong>
              <small className={s.overdue && s.status === 'ACTIVE' ? 'ck-trk-late' : undefined}>{dueText(s, tracker.timeZone)}</small>
            </button>
          ))}
        </nav>

        <section className="ck-trk-main" aria-label={step ? `${step.title} work area` : 'Work area'}>
          {step && (
            <>
              <h3>{step.step}. {step.title}</h3>
              {TBD_STEPS.has(step.step) && (
                <p className="ck-trk-tbd"><strong>TBD</strong> This step keeps the prototype's fields until it is finalised. Drafts save and the step completes as usual.</p>
              )}
              {step.status === 'LOCKED'
                ? <p className="ck-product-muted">This step unlocks when the previous step is completed.</p>
                : <StepWorkArea tracker={tracker} step={step} role={user?.role ?? ''} userId={user?.userId} designers={designers} operators={operators} summary={summary} summaryError={summaryError} onTracker={accept} />}
            </>
          )}
        </section>

        <aside className="ck-trk-side" aria-label="People and notes">
          {superadmin && <OrderAssignment tracker={tracker} operators={operators} operatorsError={operatorsError} onTracker={accept} />}
          <section>
            <h4>Contacts</h4>
            <p>Designer: {tracker.designer ? `${tracker.designer.name}${tracker.designer.phone ? ` · ${tracker.designer.phone}` : ''}` : '—'}</p>
            <p>Vendor: {tracker.vendor?.name ? `${tracker.vendor.name}${tracker.vendor.phone ? ` · ${tracker.vendor.phone}` : ''}` : '—'}</p>
            <p>School: {summary ? ([summary.schoolContactEmail, summary.schoolContactPhone].filter(Boolean).join(' · ') || 'no contact given at onboarding') : '—'}</p>
          </section>
          {summary?.schoolNotes && <section><h4>School feedback</h4><p>{summary.schoolNotes}</p></section>}
          {tracker.design.comment && tracker.design.state === 'CHANGES_REQUESTED' && (
            <section><h4>Changes requested</h4><p>{tracker.design.comment}</p></section>
          )}
          <section>
            <h4>Internal notes</h4>
            <ul className="ck-trk-notes" aria-label="Internal notes">
              {tracker.internalNotes.length === 0 && <li className="ck-product-muted">No notes yet.</li>}
              {tracker.internalNotes.map((n) => (
                <li key={n.id}>{n.body}<small>{author(n.authorUserId)} · {new Date(n.createdAt).toLocaleString('en-IN', { timeZone: tracker.timeZone, dateStyle: 'medium', timeStyle: 'short' })}</small></li>
              ))}
            </ul>
            <label className="field">Add a note <span className="ck-product-muted">{wordCount(noteText)}/100 words</span>
              <textarea rows={2} value={noteText} onChange={(e) => setNoteText(e.target.value)} />
            </label>
            {noteError && <p className="ck-product-error" role="alert">{noteError}</p>}
            <button type="button" className="ck-btn ck-btn-ghost" disabled={!noteText.trim() || wordCount(noteText) > 100} onClick={() => void addNote()}>Add note</button>
          </section>
        </aside>
      </div>
    </div>
  );
}
