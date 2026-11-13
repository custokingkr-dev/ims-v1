import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { ArrowLeft, ArrowRight, Check, Lock, MoreHorizontal } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { Field } from '../../../pages/workspace/ui';
import { formatStamp } from '../shared/slaFormat';
import { NOTE_MAX_WORDS, REASON_MAX_CHARS, REASON_MAX_WORDS, WORD_LIMITED_MAX_CHARS, wordLimit } from '../shared/words';
import { ActivityTab } from './ActivityTab';
import { workflowApi } from './api';
import { Block } from './blocks/Block';
import { LegacyDetailsBlock } from './blocks/SignOffBlocks';
import { StageContext, useStage, type StageContextValue } from './blocks/shared';
import { ChecklistTab } from './ChecklistTab';
import { useWorkflow } from './context';
import { fieldValueForSave, legacyEntries, sopStageName, TAB_LABELS, tabsOf, valueText } from './model';
import { SchoolVendorTab } from './SchoolVendorTab';
import { SlaTimer } from './SlaTimer';
import { StageIcon } from './StageIcon';
import type { OperatorChoice, Stage, StepSpec, Tab } from './types';

const STATUS_WORDS = { COMPLETED: 'Completed', ACTIVE: 'In progress', LOCKED: 'Pending' } as const;

/** The stage's real status, said in words with its tone (never colour alone). */
export function StageStatusBadge({ stage, overdue }: { stage: Stage; overdue: boolean }) {
  const status = stage.view.status;
  const tone = status === 'COMPLETED' ? 'success' : status === 'ACTIVE' ? (overdue ? 'danger' : 'primary') : 'neutral';
  return <span className={`ck-wf-badge is-${tone}`}>{status === 'ACTIVE' && overdue ? 'Overdue' : STATUS_WORDS[status]}</span>;
}

/**
 * The stage's description. Where the client SOP names the stage differently (D49), its name is the description's tooltip,
 * and is read out with it in the stage header.
 */
function StageDescription({ spec, announce = false }: { spec: StepSpec; announce?: boolean }) {
  if (!spec.description) return null;
  const sop = sopStageName(spec);
  return (
    <p className="ck-wf-muted" title={sop ? `SOP name: ${sop}` : undefined}>
      {spec.description}
      {sop && announce && <span className="ck-sr-only"> SOP name: {sop}.</span>}
    </p>
  );
}

function StageNotes({ stage }: { stage: Stage }) {
  const { view, run } = useWorkflow();
  const [body, setBody] = useState('');
  const limit = wordLimit(body, NOTE_MAX_WORDS);
  // The stage's own capability: a stage not reached yet takes no notes (the server refuses them, and D18 keeps it read-only).
  // The order's canAddNote is for the sidebar's internal notes.
  const canAdd = stage.view.can.canAddNote;
  const save = async () => {
    if (await run(() => workflowApi.addNote(view.orderId, body.trim(), stage.view.step), 'Activity note saved')) setBody('');
  };
  return (
    <section className="ck-wf-block" aria-label="Stage notes & next action">
      <header className="ck-wf-block-h"><h4>Stage notes &amp; next action</h4></header>
      {canAdd && (
        <>
          <Field label="Stage note" hint={limit.label} error={limit.over ? `Use at most ${limit.max} words.` : undefined}>
            <textarea rows={3} value={body} maxLength={WORD_LIMITED_MAX_CHARS} placeholder="Log the discussion, blocker, owner and next follow-up..." onChange={(e) => setBody(e.target.value)} />
          </Field>
          <div className="ck-wf-block-actions">
            <button type="button" className="ck-btn ck-btn-ghost" disabled={!body.trim() || limit.over} onClick={() => void save()}>Save activity note</button>
          </div>
        </>
      )}
      {stage.view.notes.length > 0 ? (
        <ul className="ck-wf-notes">
          {stage.view.notes.map((note) => (
            <li key={note.id}>
              <div className="ck-wf-muted">{note.authorName ?? `User ${note.authorUserId}`} · {formatStamp(note.createdAt, view.timeZone)}</div>
              <p>{note.body}</p>
            </li>
          ))}
        </ul>
      ) : <p className="ck-wf-muted">No notes on this stage yet.</p>}
    </section>
  );
}

/** The superadmin's "⋯" menu on a stage: reopen it with a reason, or give it to an operator. */
function StageMenu({ stage }: { stage: Stage }) {
  const { view, mutate } = useWorkflow();
  const [open, setOpen] = useState(false);
  const [dialog, setDialog] = useState<'reopen' | 'assign' | null>(null);
  const [reason, setReason] = useState('');
  const reasonLimit = wordLimit(reason, REASON_MAX_WORDS);
  const [operators, setOperators] = useState<OperatorChoice[] | null>(null);
  const [operatorError, setOperatorError] = useState('');
  const [userId, setUserId] = useState('');
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const canReopen = stage.view.can.canReopen && stage.view.status !== 'LOCKED';
  const canAssign = view.can.canAssign && stage.view.status !== 'COMPLETED';
  useEffect(() => {
    if (!open) return undefined;
    const close = (event: MouseEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  useEffect(() => {
    if (dialog !== 'assign' || operators) return;
    workflowApi.operators(view.schoolId, view.categoryCode).then(setOperators, () => setOperatorError('The operators could not be loaded.'));
  }, [dialog, operators, view.schoolId, view.categoryCode]);
  if (!canReopen && !canAssign) return null;
  const pick = (next: 'reopen' | 'assign') => { setOpen(false); setDialog(next); };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => { if (event.key === 'Escape') setOpen(false); };
  const reopen = async () => {
    if (await mutate(() => workflowApi.reopen(view.orderId, stage.view.step, reason.trim(), stage.view.version), `${stage.spec.title} reopened`)) {
      setDialog(null); setReason('');
    }
  };
  const assign = async () => {
    if (await mutate(() => workflowApi.assignStep(view.orderId, stage.view.step, Number(userId)), `${stage.spec.title} assigned`)) {
      setDialog(null); setUserId('');
    }
  };
  return (
    <div className="ck-wf-menu" ref={root} onKeyDown={onKeyDown}>
      <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined}
        aria-label="More stage actions" onClick={() => setOpen((value) => !value)}><MoreHorizontal size={16} aria-hidden="true" /></button>
      {open && (
        <div id={menuId} role="menu" className="ck-wf-menu-list">
          {canReopen && <button type="button" role="menuitem" onClick={() => pick('reopen')}>Reopen stage…</button>}
          {canAssign && <button type="button" role="menuitem" onClick={() => pick('assign')}>Assign stage…</button>}
        </div>
      )}
      {dialog === 'reopen' && (
        <Modal title={`Reopen ${stage.spec.title}?`} subtitle="Later stages lock again; saved details are kept and the stage's clock restarts." onClose={() => setDialog(null)}
          footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setDialog(null)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-g" disabled={!reason.trim() || reasonLimit.over} onClick={() => void reopen()}>Reopen stage</button>
          </>}>
          <Field label="Reason" hint={reasonLimit.label} error={reasonLimit.over ? `Use at most ${REASON_MAX_WORDS} words.` : undefined}>
            <textarea rows={3} maxLength={REASON_MAX_CHARS} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        </Modal>
      )}
      {dialog === 'assign' && (
        <Modal title={`Assign ${stage.spec.title}`} subtitle={stage.view.assigneeName ? `Now with ${stage.view.assigneeName}.` : 'Nobody is assigned yet.'} onClose={() => setDialog(null)}
          footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setDialog(null)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-g" disabled={!userId} onClick={() => void assign()}>Assign</button>
          </>}>
          {operatorError && <p className="ck-wf-error" role="alert">{operatorError}</p>}
          <Field label="Operator">
            <select value={userId} onChange={(e) => setUserId(e.target.value)} disabled={!operators}>
              <option value="">{operators ? 'Select operator…' : 'Loading operators…'}</option>
              {(operators ?? []).map((operator) => <option key={operator.userId} value={operator.userId}>{operator.fullName}</option>)}
            </select>
          </Field>
        </Modal>
      )}
    </div>
  );
}

function WorkArea({ stage, previous, next, overdue, dirty }: {
  stage: Stage; previous: Stage | undefined; next: Stage | undefined; overdue: boolean; dirty: boolean;
}) {
  const { view, mutate, goToStage, busy } = useWorkflow();
  const context = useStage();
  const status = stage.view.status;
  const locked = status === 'LOCKED';
  const active = status === 'ACTIVE';
  const unmet = stage.view.unmet;
  const canComplete = active && stage.view.can.canComplete && unmet.length === 0;
  const hasLegacyBlock = stage.spec.blocks.some((block) => block.type === 'LEGACY_DETAILS');
  const reasonId = useId();
  const complete = async () => {
    const saved = await context.flush();
    if (!saved) return;
    const completed = await mutate(() => workflowApi.complete(view.orderId, stage.view.step, saved.version), `${stage.spec.title} completed`);
    if (completed && next) goToStage(next.view.step);
  };
  let reason: ReactNode = null;
  if (active && !canComplete) {
    if (!stage.view.can.canComplete) reason = stage.spec.superadminCompletes ? 'A superadmin completes this stage.' : 'Only this stage’s assignee or a superadmin can complete it.';
    else if (unmet.length) reason = <>Still needed: {unmet.map((item) => item.message).join(' · ')}</>;
  }
  return (
    <>
      {locked && previous && <div className="ck-wf-locked" role="note"><Lock size={14} aria-hidden="true" /> Opens for editing when {previous.spec.title} is completed.</div>}
      <section className="ck-wf-block" aria-label="Stage workspace">
        <header className="ck-wf-block-h"><h4>Stage workspace</h4><StageStatusBadge stage={stage} overdue={overdue} /></header>
        <StageDescription spec={stage.spec} />
        {active && unmet.length > 0 && (
          <div className="ck-wf-left">
            <div className="ck-wf-strong">What&apos;s left to complete</div>
            <ul>{unmet.map((item, index) => <li key={`${item.code}-${index}`}>{item.message}</li>)}</ul>
          </div>
        )}
        {active && unmet.length === 0 && <p className="ck-wf-ok"><Check size={14} aria-hidden="true" /> Everything this stage needs is in place.</p>}
      </section>
      {stage.spec.blocks.map((block, index) => <Block key={`${block.type}-${index}`} block={block} />)}
      {!hasLegacyBlock && legacyEntries(stage).length > 0 && (
        <LegacyDetailsBlock block={{ type: 'LEGACY_DETAILS', title: null, fields: null, checklist: null, save: null, categories: null, suggestedByItem: null }} />
      )}
      <StageNotes stage={stage} />
      <footer className="ck-wf-footer">
        <button type="button" className="ck-btn ck-btn-ghost" disabled={!previous} onClick={() => previous && goToStage(previous.view.step)}>
          <ArrowLeft size={14} aria-hidden="true" /> Previous stage
        </button>
        <div className="ck-wf-footer-end">
          <StageMenu stage={stage} />
          {status === 'COMPLETED'
            ? <button type="button" className="ck-btn ck-btn-g" disabled><Check size={14} aria-hidden="true" /> Stage completed</button>
            : <button type="button" className="ck-btn ck-btn-g" disabled={!canComplete || busy} aria-describedby={reason ? reasonId : undefined} onClick={() => void complete()}>
              Complete stage &amp; continue <ArrowRight size={14} aria-hidden="true" />
            </button>}
        </div>
      </footer>
      {reason && <p id={reasonId} className="ck-wf-reason">{reason}</p>}
      {active && canComplete && dirty && <p className="ck-wf-reason">Your unsaved changes on this stage are saved when you complete it.</p>}
      {active && !canComplete && dirty && <p className="ck-wf-reason">Save your changes to update what is still needed.</p>}
    </>
  );
}

/**
 * The stage panel (section 4.3): header with the SLA timer, the tabs the template lists for the stage, and the tab's content.
 * Typed values live in `draft` until saved; every save is partial, so one card never overwrites another's fields.
 */
export function StagePanel({ stage, previous, next, overdue, tab, onTab, draft, onDraft }: {
  stage: Stage; previous: Stage | undefined; next: Stage | undefined; overdue: boolean; tab: Tab; onTab: (tab: Tab) => void;
  draft: Record<string, string>; onDraft: (step: number, next: Record<string, string>) => void;
}) {
  const workflow = useWorkflow();
  const { view, offsetMs, mutate } = workflow;
  const tabs = tabsOf(stage.spec);
  const shown = tabs.includes(tab) ? tab : 'WORK';
  const base = useId();
  const tabRefs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({});
  const editable = stage.view.can.canEdit && stage.view.status !== 'LOCKED';
  const dirtyKeys = Object.keys(draft).filter((key) => stage.spec.fields[key] && !stage.spec.fields[key].legacy && draft[key] !== valueText(stage.view.data[key]));

  const value = useMemo<StageContextValue>(() => {
    const step = stage.view.step;
    const payload = (keys: string[]) => Object.fromEntries(keys.map((key) => [key, fieldValueForSave(stage.spec.fields[key], draft[key] ?? '')]));
    const forget = (keys: string[]) => onDraft(step, Object.fromEntries(Object.entries(draft).filter(([key]) => !keys.includes(key))));
    const save = async (data: Record<string, unknown>, success: string) =>
      mutate(() => workflowApi.saveStepData(view.orderId, step, data, stage.view.version), success);
    return {
      stage,
      editable,
      draft,
      setDraft: (key, text) => onDraft(step, { ...draft, [key]: text }),
      saveFields: async (keys, success) => {
        if (!keys.length) return true;
        const next = await save(payload(keys), success);
        if (next) forget(keys);
        return !!next;
      },
      saveData: async (data, success) => !!(await save(data, success)),
      flush: async () => {
        if (!dirtyKeys.length) return stage.view;
        const next = await save(payload(dirtyKeys), 'Changes saved');
        if (!next) return null;
        forget(dirtyKeys);
        return next.steps.find((item) => item.step === step) ?? null;
      },
    };
  }, [stage, editable, draft, onDraft, mutate, view.orderId, dirtyKeys.join('|')]);

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = tabs.length - 1;
    const moves: Partial<Record<string, number>> = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last };
    const target = moves[event.key];
    if (target === undefined) return;
    event.preventDefault();
    onTab(tabs[target]);
    tabRefs.current[tabs[target]]?.focus();
  };
  const tabId = (key: Tab) => `${base}-tab-${key}`;
  const panelId = `${base}-panel`;

  return (
    <StageContext.Provider value={value}>
      <section className="ck-wf-stage" aria-labelledby={`${base}-title`}>
        <header className="ck-wf-stage-h">
          <span className="ck-wf-tile" aria-hidden="true"><StageIcon name={stage.spec.icon} phase={stage.spec.phase} /></span>
          <div className="ck-wf-stage-title">
            <h3 id={`${base}-title`}>Step {stage.view.step}: {stage.spec.title}</h3>
            <StageDescription spec={stage.spec} announce />
            {stage.view.assigneeName && stage.view.status !== 'COMPLETED' && <p className="ck-wf-muted">Assigned to {stage.view.assigneeName}</p>}
          </div>
          <SlaTimer step={stage.view} offsetMs={offsetMs} />
        </header>
        <div className="ck-wf-tabs" role="tablist" aria-label="Stage sections">
          {tabs.map((key, index) => (
            <button key={key} ref={(node) => { tabRefs.current[key] = node; }} id={tabId(key)} type="button" role="tab" aria-selected={shown === key}
              aria-controls={shown === key ? panelId : undefined} tabIndex={shown === key ? 0 : -1} className="ck-wf-tab"
              onClick={() => onTab(key)} onKeyDown={(event) => onKeyDown(event, index)}>{TAB_LABELS[key]}</button>
          ))}
        </div>
        <div role="tabpanel" id={panelId} aria-labelledby={tabId(shown)} tabIndex={0} className="ck-wf-tabpanel">
          {shown === 'WORK' && <WorkArea stage={stage} previous={previous} next={next} overdue={overdue} dirty={dirtyKeys.length > 0} />}
          {shown === 'SCHOOL_VENDOR' && <SchoolVendorTab stage={stage} />}
          {shown === 'CHECKLIST' && <ChecklistTab />}
          {shown === 'ACTIVITY' && <ActivityTab stage={stage} />}
        </div>
      </section>
    </StageContext.Provider>
  );
}
