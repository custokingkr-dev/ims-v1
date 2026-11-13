import { useState } from 'react';
import { CheckCircle2, Undo2 } from 'lucide-react';
import { Modal } from '../../../../components/Modal';
import { Field } from '../../../../pages/workspace/ui';
import { formatStamp } from '../../shared/slaFormat';
import { REASON_MAX_CHARS, REASON_MAX_WORDS, wordLimit } from '../../shared/words';
import { workflowApi } from '../api';
import { useWorkflow } from '../context';
import { legacyEntries, stageByCode, valueText } from '../model';
import type { BlockSpec } from '../types';
import { BlockCard, blockTitle, useStage, Warning } from './shared';

/**
 * A stage's sign-off (D20): an authenticated superadmin action that records who and when. It approves the data saved now,
 * so typed changes are saved first; a later change voids it on the server, and the banner comes back.
 */
export function SignOffBlock({ block }: { block: BlockSpec }) {
  const { view, mutate, busy } = useWorkflow();
  const { stage, flush } = useStage();
  const spec = stage.spec.signOff;
  if (!spec) return null;
  const record = stage.view.signOff;
  const signOff = async () => {
    const current = await flush();
    if (current) await mutate(() => workflowApi.signOff(view.orderId, stage.view.step, current.version), 'Sign-off recorded');
  };
  return (
    <BlockCard title={blockTitle(block, spec.label)}>
      {!record && spec.banner && <Warning>{spec.banner}</Warning>}
      {record ? (
        <div className="ck-wf-record is-success">
          <div className="ck-wf-strong"><CheckCircle2 size={14} aria-hidden="true" /> Signed off by {record.byName ?? `user ${record.by}`} · {formatStamp(record.at, view.timeZone)}</div>
        </div>
      ) : <p className="ck-wf-muted">Status: Pending</p>}
      {stage.view.can.canSignOff && !record && (
        <div className="ck-wf-block-actions">
          <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={() => void signOff()}>{spec.label}</button>
        </div>
      )}
      {!stage.view.can.canSignOff && !record && <p className="ck-wf-muted">A superadmin records this sign-off.</p>}
    </BlockCard>
  );
}

/**
 * "Not verified – changes needed" (D22): a superadmin sends the order back to Designing Activity with a reason. An order
 * without that stage (a wholesale notebook order) waits here until the school verifies.
 */
export function SendBackBlock({ block }: { block: BlockSpec }) {
  const { view, stages, mutate, goToStage } = useWorkflow();
  const { stage } = useStage();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  // The same reopen as the stage menu's, so the same limits as its reason (NoteRules.REASON_MAX_WORDS).
  const reasonLimit = wordLimit(reason, REASON_MAX_WORDS);
  const changesNeeded = Object.entries(stage.spec.fields)
    .some(([key, spec]) => spec.kind === 'CHOICE' && valueText(stage.view.data[key]) === 'CHANGES_REQUIRED');
  if (!changesNeeded) return null;
  const design = stageByCode(stages, 'DESIGNING_ACTIVITY');
  const send = async () => {
    if (!design) return;
    const next = await mutate(() => workflowApi.reopen(view.orderId, design.view.step, reason.trim(), design.view.version), `Sent back to ${design.spec.title}`);
    if (next) { setOpen(false); setReason(''); goToStage(design.view.step); }
  };
  return (
    <BlockCard title={blockTitle(block)} tone="warning">
      <p>The school has not verified the sample and asked for changes.</p>
      {!design && <p className="ck-wf-muted">This order has no {`Designing Activity`} stage, so it waits here until the school verifies.</p>}
      {design && (design.view.can.canReopen
        ? <div className="ck-wf-block-actions"><button type="button" className="ck-btn ck-btn-ghost" onClick={() => setOpen(true)}><Undo2 size={14} aria-hidden="true" /> Send back to {design.spec.title}</button></div>
        : <p className="ck-wf-muted">A superadmin can send the order back to {design.spec.title}.</p>)}
      {open && design && (
        <Modal title={`Send back to ${design.spec.title}?`} subtitle="The stages after it lock again until they are completed in order." onClose={() => setOpen(false)}
          footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-g" disabled={!reason.trim() || reasonLimit.over} onClick={() => void send()}>Send back</button>
          </>}>
          <Field label="Reason" hint={reasonLimit.label} error={reasonLimit.over ? `Use at most ${REASON_MAX_WORDS} words.` : undefined}>
            <textarea rows={3} maxLength={REASON_MAX_CHARS} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
        </Modal>
      )}
    </BlockCard>
  );
}

/** Values an earlier version of the workflow saved on this stage, kept and shown read-only (D41). */
export function LegacyDetailsBlock({ block }: { block: BlockSpec }) {
  const { stage } = useStage();
  const entries = legacyEntries(stage);
  if (!entries.length) return null;
  return (
    <details className="ck-wf-block ck-wf-legacy">
      <summary>{blockTitle(block)}</summary>
      <dl>
        {entries.map((entry) => <div key={entry.key}><dt>{entry.label}</dt><dd>{entry.value}</dd></div>)}
      </dl>
    </details>
  );
}
