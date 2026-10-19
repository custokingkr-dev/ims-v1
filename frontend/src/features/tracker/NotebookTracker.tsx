import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, Lock, CheckCircle2, Circle } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { errorMessage } from '../catalog/api';
import { StepWorkArea } from './StepWorkArea';
import { trackerApi } from './api';
import { slaLabel, wordCount } from './steps';
import type { DesignerRow, OrderSummary, TrackerStep, TrackerView } from './types';
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

/** The superadmin / operator view of one notebook order: the tracker's steps, the active work area and the order's people and notes. */
export function NotebookTracker({ orderId, initial, onBack, onChanged }: { orderId: string; initial?: TrackerView; onBack: () => void; onChanged?: () => void }) {
  const { user } = useAuth();
  const [tracker, setTracker] = useState<TrackerView | null>(initial ?? null);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<number | null>(initial?.progress.currentStep ?? null);
  const [designers, setDesigners] = useState<DesignerRow[]>([]);
  const [operators, setOperators] = useState<Array<{ userId: number; fullName: string }>>([]);
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
    if (!initial) trackerApi.get(orderId).then((next) => { if (active) { setTracker(next); setSelected(next.progress.currentStep ?? next.steps[0]?.step ?? null); } })
      .catch((e: unknown) => { if (active) setError(errorMessage(e, 'Unable to load this order.')); });
    return () => { active = false; };
  }, [orderId, initial]);

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
        setOperators([...new Map(eligible.map((r) => [r.userId, { userId: r.userId, fullName: r.fullName }])).values()]);
      }).catch(() => { /* Reassigning is unavailable without the operator list; the rest still works. */ });
    }
    return () => { active = false; };
  }, [superadmin, tracker?.schoolId]);

  if (error) return <div className="ck-trk"><button className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Back</button><p className="ck-product-error" role="alert">{error}</p></div>;
  if (!tracker) return <div className="ck-trk"><p className="ck-product-muted">Loading order…</p></div>;

  const step = tracker.steps.find((s) => s.step === selected) ?? tracker.steps[0];
  const { progress } = tracker;
  const addNote = async () => {
    setNoteError('');
    try { accept(await trackerApi.addInternalNote(tracker.orderId, noteText)); setNoteText(''); } catch (e) { setNoteError(errorMessage(e)); }
  };

  return (
    <div className="ck-trk">
      <header className="ck-trk-head">
        <button className="ck-btn ck-btn-ghost" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Back to orders</button>
        <div>
          <h2>{tracker.orderId}</h2>
          <p className="ck-product-muted">{tracker.schoolName ?? `School ${tracker.schoolId}`} · {tracker.orderedCount.toLocaleString('en-IN')} notebooks · {tracker.customised ? 'Customised' : 'Wholesale'}</p>
        </div>
        <div className="ck-trk-progress" role="status">
          {progress.finished ? 'Completed' : `Step ${progress.currentStep} · ${progress.currentTitle}`}
          <span className="ck-product-muted"> — {progress.completed} of {progress.total} done</span>
        </div>
      </header>

      <ol className="ck-trk-stepper" aria-label="Order progress">
        {tracker.steps.map((s, i) => (
          <li key={s.step} className={stepClass(s, s.step === step?.step)}>
            <span className="ck-trk-dot" aria-hidden="true">{s.status === 'COMPLETED' ? <CheckCircle2 size={16} /> : s.status === 'ACTIVE' ? <Circle size={16} /> : <Lock size={14} />}</span>
            <span>{i + 1}</span>
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
              {step.status === 'LOCKED'
                ? <p className="ck-product-muted">This step unlocks when the previous step is completed.</p>
                : <StepWorkArea tracker={tracker} step={step} role={user?.role ?? ''} userId={user?.userId} designers={designers} operators={operators} summary={summary} summaryError={summaryError} onTracker={accept} />}
            </>
          )}
        </section>

        <aside className="ck-trk-side" aria-label="People and notes">
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
            <ul className="ck-trk-notes">
              {tracker.internalNotes.length === 0 && <li className="ck-product-muted">No notes yet.</li>}
              {tracker.internalNotes.map((n) => <li key={n.id}>{n.body}<small>{new Date(n.createdAt).toLocaleString('en-IN', { timeZone: tracker.timeZone })}</small></li>)}
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
