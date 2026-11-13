import { useEffect, useId, useState } from 'react';
import { MapPin } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { Field } from '../../../pages/workspace/ui';
import { CategoryIcon } from '../../catalog/categoryIcons';
import { orderLabel } from '../shared/orderLabel';
import { workflowApi } from './api';
import { useWorkflow } from './context';
import { capitalise, estimatedCompletion, pluralise } from './model';
import type { OperatorChoice } from './types';

const count = (value: number) => value.toLocaleString('en-IN');

function Tile({ label, value, sub, wide }: { label: string; value: string; sub: string; wide?: boolean }) {
  return (
    <div className={`ck-wf-kpi${wide ? ' is-wide' : ''}`}>
      <div className="ck-wf-kpi-l">{label}</div>
      <div className="ck-wf-kpi-v">{value}</div>
      <div className="ck-wf-kpi-s">{sub}</div>
    </div>
  );
}

/** The quantity tiles (section 4.3): notebook sizes for notebooks, otherwise the total and up to four order lines. */
function QuantityTiles() {
  const { view, summary } = useWorkflow();
  const noun = view.itemNoun || 'item';
  const counts = summary?.titleCards?.counts;
  if (counts) {
    const total = counts.king + counts.long + counts.jumboKing + counts.jumboLong + counts.fa + counts.other;
    return (
      <>
        <Tile label={`Total ${pluralise(noun)}`} value={count(summary?.quantities?.total ?? total)} sub="All notebook types" />
        <Tile label="King size" value={count(counts.king)} sub="Notebooks" />
        <Tile label="Long size" value={count(counts.long)} sub="Notebooks" />
        <Tile label="Jumbo King" value={count(counts.jumboKing)} sub="Notebooks" />
        <Tile label="Jumbo Long" value={count(counts.jumboLong)} sub="Notebooks" />
        <Tile label="FA notebooks" value={count(counts.fa)} sub="Notebooks" />
        {counts.other > 0 && <Tile label="Other sizes" value={count(counts.other)} sub="Notebooks" />}
      </>
    );
  }
  const lines = summary?.quantities?.lines ?? [];
  return (
    <>
      <Tile label={`Total ${pluralise(noun)}`} value={count(summary?.quantities?.total ?? view.orderedCount)} sub={`All ${pluralise(noun)} on the order`} />
      {lines.slice(0, 4).map((line) => <Tile key={line.lineNo} label={line.label} value={count(line.quantity)} sub={line.details ?? capitalise(pluralise(noun))} />)}
    </>
  );
}

function ProgressRing({ done, total }: { done: number; total: number }) {
  const percent = total ? Math.round((done / total) * 100) : 0;
  const radius = 22;
  const circumference = 2 * Math.PI * radius;
  return (
    <div className="ck-wf-kpi ck-wf-ring-tile">
      <svg viewBox="0 0 56 56" width="56" height="56" role="img" aria-label={`${percent}% complete`}>
        <circle cx="28" cy="28" r={radius} className="ck-wf-ring-track" />
        <circle cx="28" cy="28" r={radius} className="ck-wf-ring-fill" strokeDasharray={circumference} strokeDashoffset={circumference * (1 - percent / 100)} />
        <text x="28" y="32" textAnchor="middle" className="ck-wf-ring-text">{percent}%</text>
      </svg>
      <div>
        <div className="ck-wf-kpi-v is-small">{done} of {total} steps</div>
        <div className="ck-wf-kpi-s">Workflow progress</div>
      </div>
    </div>
  );
}

/** Point of contact: superadmin assigns the order; an eligible operator takes an unassigned one (D37). */
function PointOfContact() {
  const { view, mutate } = useWorkflow();
  const [open, setOpen] = useState(false);
  const [operators, setOperators] = useState<OperatorChoice[] | null>(null);
  const [error, setError] = useState('');
  const [userId, setUserId] = useState('');
  useEffect(() => {
    if (!open || operators) return;
    workflowApi.operators(view.schoolId, view.categoryCode).then(setOperators, () => setError('The operators could not be loaded.'));
  }, [open, operators, view.schoolId, view.categoryCode]);
  const finished = view.progress.finished;
  const assign = async () => {
    if (await mutate(() => workflowApi.assignOrder(view.orderId, Number(userId)), 'Order assigned')) { setOpen(false); setUserId(''); }
  };
  return (
    <div className="ck-wf-poc">
      <span>Point of contact: </span>
      {view.assigneeName ? <strong>{view.assigneeName}</strong> : <span className="ck-wf-badge is-warning">Unassigned</span>}
      {!finished && view.can.canAssign && <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => setOpen(true)}>Assign…</button>}
      {!finished && view.can.canClaim && (
        <button type="button" className="ck-btn ck-btn-g ck-btn-sm" onClick={() => void mutate(() => workflowApi.claim(view.orderId), 'You are now the point of contact')}>Take this order</button>
      )}
      {open && (
        <Modal title="Assign the order" subtitle="Every open stage goes to the operator you choose." onClose={() => setOpen(false)}
          footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-g" disabled={!userId} onClick={() => void assign()}>Assign order</button>
          </>}>
          {error && <p className="ck-wf-error" role="alert">{error}</p>}
          <Field label="Operator">
            <select value={userId} disabled={!operators} onChange={(e) => setUserId(e.target.value)}>
              <option value="">{operators ? 'Select operator…' : 'Loading operators…'}</option>
              {(operators ?? []).map((operator) => <option key={operator.userId} value={operator.userId}>{operator.fullName}</option>)}
            </select>
          </Field>
          {operators && operators.length === 0 && <p className="ck-wf-muted">No operator is scoped to this school and item yet.</p>}
        </Modal>
      )}
    </div>
  );
}

/** The summary row (section 4.3, prototype section 3.1): who and what the order is, how much, by when, and how far along. */
export function SummaryRow({ overdue }: { overdue: boolean }) {
  const { view, summary } = useWorkflow();
  const label = orderLabel(view);
  const estimate = estimatedCompletion(view, summary);
  const heading = useId();
  const status = view.progress.finished ? { text: 'Completed', tone: 'success' } : overdue ? { text: 'Overdue', tone: 'danger' } : { text: 'In progress', tone: 'primary' };
  const city = summary?.schoolCity?.trim();
  return (
    <section className="ck-wf-summary" aria-labelledby={heading}>
      <div className="ck-wf-identity">
        <span className="ck-wf-tile is-large" aria-hidden="true"><CategoryIcon code={view.categoryCode} size={24} /></span>
        <div className="ck-wf-identity-body">
          <div className="ck-wf-identity-top">
            <span className="ck-wf-kpi-l">Order number</span>
            <span className={`ck-wf-badge is-${status.tone}`}>{status.text}</span>
          </div>
          <h2 id={heading} className="ck-wf-number">{label.headline}</h2>
          {label.reference && <div className="ck-wf-muted">{label.reference}</div>}
          <div className="ck-wf-school">{view.schoolName ?? `School ${view.schoolId}`}</div>
          {city && <div className="ck-wf-muted"><MapPin size={12} aria-hidden="true" /> {city}</div>}
          {!view.customised && view.itemCode === 'NB' && <div className="ck-wf-muted">Wholesale notebooks · design steps skipped</div>}
          <PointOfContact />
        </div>
      </div>
      <div className="ck-wf-kpis">
        <QuantityTiles />
        <Tile label="Estimated completion" value={estimate.value} sub={estimate.caption} />
        <ProgressRing done={view.progress.completed} total={view.progress.total} />
      </div>
    </section>
  );
}
