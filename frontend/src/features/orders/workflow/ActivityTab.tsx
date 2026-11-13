import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import { formatStamp } from '../shared/slaFormat';
import { workflowApi, workflowError } from './api';
import { useWorkflow } from './context';
import { designFileName } from './designFiles';
import { peopleOf, uploaderName } from './model';
import type { ActivityEntry, Stage } from './types';

const ACTIONS: Record<string, string> = {
  TRACKER_CREATED: 'Workflow started',
  STEP_COMPLETED: 'Completed the stage',
  STEP_IMPORTED: 'Imported from the earlier production workflow',
  STEP_ASSIGNED: 'Assigned the stage',
  ORDER_ASSIGNED: 'Assigned the order',
  ORDER_CLAIMED: 'Took the order',
  STEP_DATA_SAVED: 'Saved stage details',
  STEP_REOPENED: 'Reopened the stage',
  SENT_BACK: 'Sent the order back to Designing Activity',
  STEP_SIGNED_OFF: 'Recorded the sign-off',
  HEAD_APPROVED: 'Approved the design',
  CHANGES_REQUESTED: 'Requested design changes',
  VENDORS_SELECTED: 'Selected vendors',
  DESIGNER_SET: 'Set the designer',
  CONTACTS_SAVED: 'Saved contacts',
  GROUP_LINK_SET: 'Saved the school group link',
  INTRO_SENT: 'Sent the introduction message',
};

const CONTACTED: Record<string, string> = {
  SCHOOL: 'School', DESIGNER: 'Designer', PRINTING_PRESS: 'Printing Press', MANUFACTURING_FACTORY: 'Manufacturing Factory', INTERNAL_APPROVER: 'Internal Approver',
};
const OUTCOMES: Record<string, string> = {
  CONNECTED: 'Connected', NO_ANSWER: 'No Answer', BUSY: 'Busy', CALLBACK_REQUESTED: 'Callback Requested', DELAY_REPORTED: 'Delay Reported',
  CHANGES_REQUESTED: 'Changes Requested', APPROVAL_RECEIVED: 'Approval Received',
};

const humanise = (code: string) => code.toLowerCase().replace(/_/g, ' ').replace(/^./, (letter) => letter.toUpperCase());
const text = (value: unknown) => (typeof value === 'string' ? value : '');

interface TimelineItem { id: string; at: string; stepOrder: number | null; actor: string; title: string; body: string }

function describe(entry: ActivityEntry, fieldLabel: (step: number | null, key: string) => string): TimelineItem {
  const details = entry.details ?? {};
  const actor = entry.actorName ?? (entry.actorUserId != null ? `User ${entry.actorUserId}` : 'System');
  let title: string;
  switch (entry.kind) {
    case 'NOTE': title = entry.stepOrder != null ? 'Stage note' : 'Internal note'; break;
    case 'CALL': {
      const who = text(details.contactLabel);
      title = `Call · ${CONTACTED[text(details.contacted)] ?? humanise(text(details.contacted) || 'contact')}${who ? ` · ${who}` : ''} · ${OUTCOMES[text(details.outcome)] ?? humanise(text(details.outcome) || 'logged')}`;
      break;
    }
    case 'FOLLOW_UP': {
      const percent = details.percentComplete;
      title = `Follow-up logged${typeof percent === 'number' ? ` · ${percent}% complete` : ''}`;
      break;
    }
    default: {
      title = ACTIONS[entry.action ?? ''] ?? humanise(entry.action ?? 'Updated');
      const fields = Array.isArray(details.fields) ? details.fields.filter((field): field is string => typeof field === 'string') : [];
      if (entry.action === 'STEP_DATA_SAVED' && fields.length) title = `Saved ${fields.map((key) => fieldLabel(entry.stepOrder, key)).join(', ')}`;
    }
  }
  if (entry.count > 1) title = `${title} (${entry.count} saves)`;
  return { id: entry.id, at: entry.at, stepOrder: entry.stepOrder, actor, title, body: entry.action === 'INTRO_SENT' ? introText(entry) : entry.text ?? '' };
}

/**
 * The introduction message an INTRO_SENT entry records. The server keeps the template with {Name} unfilled; the message
 * the screen offered filled it with the sender's name, so it is shown that way, and not at all when the sender is unknown.
 */
function introText(entry: ActivityEntry): string {
  const text = entry.text ?? '';
  if (!text.includes('{Name}')) return text;
  const name = entry.actorName?.trim();
  return name ? text.split('{Name}').join(name) : '';
}

/**
 * Activity history (section 4.3): the order's timeline (actions, notes, calls, design-file events and legacy follow-ups),
 * filterable to this stage, and the design revision history.
 */
export function ActivityTab({ stage }: { stage: Stage }) {
  const { view, stages, designFiles, callsVersion, user } = useWorkflow();
  const people = useMemo(() => peopleOf(view, user), [view, user]);
  const [scope, setScope] = useState<'all' | 'stage'>('all');
  const [entries, setEntries] = useState<ActivityEntry[] | null>(null);
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const name = useId();
  const step = scope === 'stage' ? stage.view.step : undefined;

  const load = useCallback(async () => {
    setError('');
    try {
      const page = await workflowApi.activity(view.orderId, { step });
      setEntries(page.entries); setNextBefore(page.nextBefore);
    } catch (e) { setEntries(null); setError(workflowError(e, 'The activity history could not be loaded.')); }
  }, [view.orderId, step]);
  // Reloaded whenever the order changes (a save, a completion, a note) or a call is logged.
  useEffect(() => { void load(); }, [load, view.version, view.steps, callsVersion]);

  const more = async () => {
    if (!nextBefore) return;
    setLoadingMore(true);
    try {
      const page = await workflowApi.activity(view.orderId, { step, before: nextBefore });
      setEntries((current) => [...(current ?? []), ...page.entries]); setNextBefore(page.nextBefore);
    } catch (e) { setError(workflowError(e, 'Older activity could not be loaded.')); } finally { setLoadingMore(false); }
  };

  const stageTitle = (order: number | null) => {
    if (order == null) return 'Order';
    const found = stages.find((item) => item.view.step === order);
    return found ? `Step ${order} · ${found.spec.title}` : `Step ${order}`;
  };
  const fieldLabel = (order: number | null, key: string) => stages.find((item) => item.view.step === order)?.spec.fields[key]?.label ?? key;

  // Design files live with the order in school-core; their uploads and removals join the timeline here (D40).
  const designStage = stages.find((item) => item.spec.blocks.some((block) => block.type === 'DESIGN_FILES'));
  const showFiles = scope === 'all' || designStage?.view.step === stage.view.step;
  const oldest = nextBefore && entries?.length ? entries[entries.length - 1].at : null;
  const fileEvents: TimelineItem[] = showFiles ? (designFiles.files ?? []).flatMap((file) => {
    const fileName = designFileName(file);
    const events: TimelineItem[] = [{ id: `file-up:${file.id}`, at: file.uploadedAt, stepOrder: designStage?.view.step ?? null, actor: uploaderName(file.uploadedBy, people) ?? 'Someone', title: `Uploaded design file ${fileName}`, body: '' }];
    // School-core keeps the remover beside the time (catalog V39); a removal recorded before that names no one.
    if (file.supersededAt) events.push({ id: `file-out:${file.id}`, at: file.supersededAt, stepOrder: designStage?.view.step ?? null, actor: uploaderName(file.supersededBy, people) ?? '', title: `Removed design file ${fileName}`, body: '' });
    return events;
  }).filter((event) => !oldest || event.at >= oldest) : [];
  const timeline = entries ? [...entries.map((entry) => describe(entry, fieldLabel)), ...fileEvents].sort((left, right) => right.at.localeCompare(left.at)) : null;
  const revisions = designFiles.files ?? [];

  return (
    <>
      <section className="ck-wf-block" aria-label="Order activity history">
        <header className="ck-wf-block-h">
          <h4>Order activity history</h4>
          <fieldset className="ck-wf-segmented">
            <legend className="ck-sr-only">Show activity for</legend>
            <label><input type="radio" name={name} checked={scope === 'all'} onChange={() => setScope('all')} /> All stages</label>
            <label><input type="radio" name={name} checked={scope === 'stage'} onChange={() => setScope('stage')} /> This stage</label>
          </fieldset>
        </header>
        {error && <div className="ck-alert ck-alert-re" role="alert"><div>{error} <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => void load()}>Retry</button></div></div>}
        {!error && timeline === null && <p className="ck-wf-muted" role="status">Loading activity…</p>}
        {timeline && timeline.length === 0 && <p className="ck-wf-muted">No activity recorded {scope === 'stage' ? 'on this stage ' : ''}yet.</p>}
        {timeline && timeline.length > 0 && (
          <ol className="ck-wf-timeline">
            {timeline.map((item) => (
              <li key={item.id}>
                <div className="ck-wf-strong">{item.title}</div>
                <div className="ck-wf-muted">{[item.actor, formatStamp(item.at, view.timeZone), stageTitle(item.stepOrder)].filter(Boolean).join(' · ')}</div>
                {item.body && <p>{item.body}</p>}
              </li>
            ))}
          </ol>
        )}
        {nextBefore && <div className="ck-wf-block-actions"><button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" disabled={loadingMore} onClick={() => void more()}>Load older activity</button></div>}
      </section>
      <section className="ck-wf-block" aria-label="Design revision history">
        <header className="ck-wf-block-h"><h4>Design revision history</h4></header>
        {designFiles.error && <p className="ck-wf-error" role="alert">{designFiles.error}</p>}
        {revisions.length === 0 ? <p className="ck-wf-muted">No design files uploaded yet.</p> : (
          <div className="ck-table-wrap">
            <table className="ck-table ck-wf-table">
              <thead><tr><th scope="col">Version</th><th scope="col">Filename</th><th scope="col">Uploaded</th><th scope="col">Status</th></tr></thead>
              <tbody>
                {revisions.map((file, index) => {
                  const uploader = uploaderName(file.uploadedBy, people);
                  return (
                    <tr key={file.id}>
                      <td>v{index + 1}</td>
                      <td>{designFileName(file)}</td>
                      <td>{formatStamp(file.uploadedAt, view.timeZone)}{uploader ? ` · ${uploader}` : ''}</td>
                      <td><span className={`ck-wf-badge is-${file.supersededAt ? 'neutral' : 'success'}`}>{file.supersededAt ? 'Removed' : 'Current'}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
