import { useEffect, useId, useState } from 'react';
import { CheckSquare, FileText, Maximize2, Plus, Square, StickyNote } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { formatStamp, upcomingDueLabel, upcomingDueTone } from '../shared/slaFormat';
import { NOTE_MAX_WORDS, WORD_LIMITED_MAX_CHARS, wordLimit } from '../shared/words';
import { workflowApi, workflowError } from './api';
import { useWorkflow } from './context';
import { designFileKind, designFileName, isCdr, isRasterFile, openDesignFile, useDesignPicture } from './designFiles';
import { initials, supersededOrCurrent } from './model';
import type { CallLogEntry } from './types';

function DesignPreview() {
  const { view, designFiles } = useWorkflow();
  const { current } = supersededOrCurrent(designFiles.files ?? []);
  const latest = current[current.length - 1];
  const picture = useDesignPicture(view.orderId, latest);
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState('');
  const name = latest ? designFileName(latest) : '';
  const view_ = () => { if (latest) openDesignFile(view.orderId, latest).catch((e: unknown) => setError(workflowError(e, 'The file could not be opened.'))); };
  const art = latest && (isRasterFile(latest)
    ? (picture ? <img src={picture} alt={`Current design: ${name}`} /> : <p className="ck-wf-muted">{picture === null ? 'The preview could not be loaded.' : 'Loading preview…'}</p>)
    : <span className="ck-wf-file-art"><FileText size={36} aria-hidden="true" /><span>{designFileKind(latest)}</span></span>);
  return (
    <section className="ck-wf-widget" aria-label="Current Design Preview">
      <header className="ck-wf-widget-h">
        <h4>Current Design Preview</h4>
        {latest && <button type="button" className="ck-wf-icon-btn" aria-label="Expand the design preview" onClick={() => setExpanded(true)}><Maximize2 size={14} aria-hidden="true" /></button>}
      </header>
      {designFiles.error && <p className="ck-wf-error" role="alert">{designFiles.error}</p>}
      {!latest && !designFiles.error && <p className="ck-wf-muted">{designFiles.files ? 'No design files yet.' : 'Loading…'}</p>}
      {latest && (
        <>
          <div className="ck-wf-preview">{art}</div>
          <div className="ck-wf-preview-foot">
            <span className="ck-wf-muted ck-wf-ellipsis" title={name}>{name}</span>
            <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={view_}>{isCdr(latest) ? 'Download file' : 'View file'}</button>
          </div>
          {error && <p className="ck-wf-error" role="alert">{error}</p>}
        </>
      )}
      {expanded && latest && (
        <Modal title="Current design" subtitle={name} onClose={() => setExpanded(false)}
          footer={<button type="button" className="ck-btn ck-btn-ghost" onClick={() => setExpanded(false)}>Close</button>}>
          <div className="ck-wf-preview is-large">{art}</div>
        </Modal>
      )}
    </section>
  );
}

function SchoolFeedback() {
  const { view, summary, openCallForm, callsVersion } = useWorkflow();
  const [calls, setCalls] = useState<CallLogEntry[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    workflowApi.calls(view.orderId, 100).then((rows) => { if (active) { setCalls(rows); setError(''); } },
      (e: unknown) => { if (active) setError(workflowError(e, 'The school feedback could not be loaded.')); });
    return () => { active = false; };
  }, [view.orderId, callsVersion]);
  const changes = (calls ?? [])
    .filter((call) => call.contacted === 'SCHOOL' && call.outcome === 'CHANGES_REQUESTED' && call.notes?.trim())
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    .slice(0, 2);
  const contact = view.contacts.schoolContactName?.trim();
  return (
    <section className="ck-wf-widget" aria-label="School Feedback">
      <header className="ck-wf-widget-h">
        <h4>School Feedback</h4>
        {view.can.canLogCall && (
          <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => openCallForm({ contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED' })}>
            <Plus size={13} aria-hidden="true" /> Add<span className="ck-sr-only"> school feedback</span>
          </button>
        )}
      </header>
      <div className="ck-wf-chip-row">
        <span className="ck-wf-avatar" aria-hidden="true">{initials(contact || view.schoolName)}</span>
        <div>
          <div className="ck-wf-strong">{contact || 'No school contact saved'}</div>
          <div className="ck-wf-muted">School contact</div>
        </div>
      </div>
      {summary?.schoolNotes?.trim() && (
        <div className="ck-wf-feedback"><div className="ck-wf-muted">From the order form</div><p>{summary.schoolNotes}</p></div>
      )}
      {changes.map((call) => (
        <div key={call.id} className="ck-wf-feedback">
          <div className="ck-wf-muted">Logged feedback · {formatStamp(call.createdAt, view.timeZone)}</div>
          <p>{call.notes}</p>
        </div>
      ))}
      {error && <p className="ck-wf-error" role="alert">{error}</p>}
      {calls && !changes.length && !summary?.schoolNotes?.trim() && <p className="ck-wf-muted">No feedback logged yet.</p>}
    </section>
  );
}

function InternalNotes() {
  const { view, run, goToStage, stages } = useWorkflow();
  const [body, setBody] = useState('');
  const ids = useId();
  const limit = wordLimit(body, NOTE_MAX_WORDS);
  const save = async () => { if (await run(() => workflowApi.addNote(view.orderId, body.trim()), 'Note saved')) setBody(''); };
  const current = view.progress.currentStep ?? stages[stages.length - 1]?.view.step ?? 1;
  return (
    <section className="ck-wf-widget" aria-label="Internal Notes">
      <header className="ck-wf-widget-h"><h4>Internal Notes</h4><StickyNote size={14} aria-hidden="true" /></header>
      {view.can.canAddNote && (
        <>
          <label className="ck-sr-only" htmlFor={`${ids}-note`}>Internal note</label>
          <textarea id={`${ids}-note`} rows={3} value={body} maxLength={WORD_LIMITED_MAX_CHARS} placeholder="Add notes, discussions, next actions..." aria-describedby={`${ids}-count`}
            onChange={(e) => setBody(e.target.value)} />
          <div className="ck-wf-widget-row">
            <span id={`${ids}-count`} className={`ck-wf-muted${limit.over ? ' ck-wf-error' : ''}`}>{limit.label}</span>
            <button type="button" className="ck-btn ck-btn-g ck-btn-sm" disabled={!body.trim() || limit.over} onClick={() => void save()}>Save note</button>
          </div>
        </>
      )}
      {view.internalNotes.length === 0 ? <p className="ck-wf-muted">No notes yet.</p> : (
        <ul className="ck-wf-notes">
          {view.internalNotes.slice(0, 3).map((note) => (
            <li key={note.id}>
              <div className="ck-wf-muted">{note.authorName ?? `User ${note.authorUserId}`} · {formatStamp(note.createdAt, view.timeZone)}</div>
              <p>{note.body}</p>
            </li>
          ))}
        </ul>
      )}
      <button type="button" className="ck-wf-text-btn" onClick={() => goToStage(current, 'ACTIVITY')}>View all in Activity history</button>
    </section>
  );
}

function UpcomingActions() {
  const { view } = useWorkflow();
  return (
    <section className="ck-wf-widget" aria-label="Upcoming Actions">
      <header className="ck-wf-widget-h"><h4>Upcoming Actions</h4><span className="ck-wf-badge is-warning">Live list</span></header>
      {view.upcomingActions.length === 0 ? <p className="ck-wf-muted">Nothing is due.</p> : (
        <ul className="ck-wf-upcoming">
          {view.upcomingActions.map((action, index) => {
            const label = upcomingDueLabel(action, view.timeZone);
            return (
              <li key={`${action.kind}-${index}`} className={action.done ? 'is-done' : undefined}>
                {action.done ? <CheckSquare size={16} aria-hidden="true" /> : <Square size={16} aria-hidden="true" />}
                <div>
                  <div>{action.title}<span className="ck-sr-only">{action.done ? ' (done)' : ''}</span></div>
                  {label && <div className={`ck-wf-due is-${upcomingDueTone(action)}`}>{label}</div>}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** The right-hand widgets (section 4.4), the same on every stage. */
export function Sidebar() {
  return (
    <aside className="ck-wf-side" aria-label="Order details">
      <DesignPreview />
      <SchoolFeedback />
      <InternalNotes />
      <UpcomingActions />
    </aside>
  );
}
