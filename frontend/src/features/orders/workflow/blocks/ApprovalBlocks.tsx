import { useState } from 'react';
import { Check, ExternalLink } from 'lucide-react';
import { Modal } from '../../../../components/Modal';
import { Field } from '../../../../pages/workspace/ui';
import { safeEvidenceHref } from '../../shared/invite';
import { formatStamp } from '../../shared/slaFormat';
import { APPROVAL_COMMENT_MAX_CHARS, APPROVAL_COMMENT_MAX_WORDS, wordLimit } from '../../shared/words';
import { workflowApi } from '../api';
import { useWorkflow } from '../context';
import { stageWithField, valueText } from '../model';
import type { BlockSpec, DesignDecision } from '../types';
import { Badge, BlockCard, blockTitle, useStage, Warning } from './shared';

/** The link CDR Conversion saved, with when and by whom: what Head Approval approves (D21). */
function useCdrLink() {
  const { stages } = useWorkflow();
  const stage = stageWithField(stages, 'cdrLink');
  const link = stage ? valueText(stage.view.data.cdrLink).trim() : '';
  return { stage, link, href: safeEvidenceHref(link) };
}

export function CdrForApprovalBlock({ block }: { block: BlockSpec }) {
  const { view } = useWorkflow();
  const { stage, link, href } = useCdrLink();
  const title = blockTitle(block);
  const conversion = stage?.spec.title ?? 'CDR Conversion';
  return (
    <BlockCard title={title} badge={link ? <Badge tone="success">Link saved</Badge> : <Badge tone="warning">Link not saved</Badge>}>
      {link ? (
        <div className="ck-wf-link-record">
          {href
            ? <a className="ck-wf-link" href={href} target="_blank" rel="noopener noreferrer">{link} <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> (opens in a new tab)</span></a>
            : <span className="ck-wf-strong">{link}</span>}
          {stage?.view.dataSavedAt && (
            <div className="ck-wf-muted">Saved {formatStamp(stage.view.dataSavedAt, view.timeZone)}{stage.view.dataSavedByName ? ` by ${stage.view.dataSavedByName}` : ''}</div>
          )}
        </div>
      ) : <Warning>No CDR link saved yet. Go back to {conversion} and save the link first.</Warning>}
    </BlockCard>
  );
}

const DECISIONS: Record<DesignDecision['state'], string> = {
  NOT_SENT: 'Not decided',
  SENT: 'Waiting for a decision',
  CHANGES_REQUESTED: 'Changes requested',
  APPROVED: 'Approved',
};

function DecisionRecord({ design, cdrLink }: { design: DesignDecision; cdrLink: string }) {
  const { view } = useWorkflow();
  if (design.state !== 'APPROVED' && design.state !== 'CHANGES_REQUESTED') return null;
  const stale = design.state === 'APPROVED' && !!design.approvedCdrLink && design.approvedCdrLink !== cdrLink;
  return (
    <div className={`ck-wf-record${design.state === 'APPROVED' && !stale ? ' is-success' : ' is-warning'}`}>
      <div className="ck-wf-strong">
        {DECISIONS[design.state]}
        {design.decidedByName ? ` by ${design.decidedByName}` : ''}
        {design.decidedAt ? ` · ${formatStamp(design.decidedAt, view.timeZone)}` : ''}
      </div>
      {design.comment && <p>{design.comment}</p>}
      {stale && <p>The CDR link changed after this approval, so the design needs approving again.</p>}
    </div>
  );
}

/**
 * Head Approval (D20, D21): a superadmin approves the exact CDR link saved now, or requests changes, which reopens Designing
 * Activity. A comment is required either way. Everyone else sees who decided, when and why.
 */
export function HeadApprovalBlock({ block }: { block: BlockSpec }) {
  const { view, stages, mutate, busy, user } = useWorkflow();
  const { stage } = useStage();
  const { link, stage: cdrStage } = useCdrLink();
  const [comment, setComment] = useState('');
  const [confirming, setConfirming] = useState(false);
  const limit = wordLimit(comment, APPROVAL_COMMENT_MAX_WORDS);
  const design = stages.find((item) => item.spec.code === 'DESIGNING_ACTIVITY')?.spec.title ?? 'Designing Activity';
  const canDecide = view.can.canApproveDesign;
  const ready = !!comment.trim() && !limit.over;
  // Why there is no decision form here. Superadmin decides only while this stage is in progress and a CDR link is saved.
  const closedReason = !user.superadmin
    ? 'Only a superadmin approves the design.'
    : stage.view.status === 'COMPLETED'
      ? `${stage.spec.title} is completed. Reopen it to record a new decision.`
      : stage.view.status === 'LOCKED'
        ? `The design can be approved once ${stage.spec.title} is in progress.`
        : !link
          ? 'Approve is available once a CDR link is saved.'
          : 'The design cannot be approved right now. Refresh the order to see its latest state.';
  const approve = async () => {
    if (await mutate(() => workflowApi.approveDesign(view.orderId, comment.trim(), stage.view.version), 'Design approved')) setComment('');
  };
  const requestChanges = async () => {
    setConfirming(false);
    if (await mutate(() => workflowApi.requestChanges(view.orderId, comment.trim(), stage.view.version), `Changes requested · ${design} reopened`)) setComment('');
  };
  return (
    <BlockCard title={blockTitle(block)}>
      <p className="ck-wf-info">Approval applies to the CDR link saved in {cdrStage?.spec.title ?? 'CDR Conversion'}. Your decision is recorded against your signed-in account.</p>
      <DecisionRecord design={view.design} cdrLink={link} />
      {canDecide ? (
        <>
          <Field label="Approval comments" hint={limit.label} error={limit.over ? `Use at most ${limit.max} words.` : undefined}>
            <textarea rows={3} value={comment} maxLength={APPROVAL_COMMENT_MAX_CHARS} placeholder="Add feedback or reason for rejection" onChange={(e) => setComment(e.target.value)} />
          </Field>
          <div className="ck-wf-block-actions">
            <button type="button" className="ck-btn ck-btn-g" disabled={busy || !link || !ready} onClick={() => void approve()}><Check size={14} aria-hidden="true" /> Approve design</button>
            <button type="button" className="ck-btn ck-btn-ghost ck-wf-danger-text" disabled={busy || !ready} onClick={() => setConfirming(true)}>Request changes</button>
          </div>
          {!link && <p className="ck-wf-muted">Approve is available once a CDR link is saved.</p>}
          {link && !comment.trim() && <p className="ck-wf-muted">A comment is required to approve or to request changes.</p>}
        </>
      ) : <p className="ck-wf-muted">{closedReason}</p>}
      {confirming && (
        <Modal title="Request changes?" subtitle={`This reopens ${design}.`} onClose={() => setConfirming(false)}
          footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setConfirming(false)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-re" onClick={() => void requestChanges()}>Request changes</button>
          </>}>
          <p>The order goes back to {design}. Saved details are kept, and the CDR link must be approved again afterwards.</p>
          <p className="ck-wf-muted">Your comment: {comment.trim()}</p>
        </Modal>
      )}
    </BlockCard>
  );
}
