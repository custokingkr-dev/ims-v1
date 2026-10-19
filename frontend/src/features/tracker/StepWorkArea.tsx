import { useEffect, useMemo, useState } from 'react';
import { Check, RotateCcw, Save, ShieldCheck } from 'lucide-react';
import { errorMessage } from '../catalog/api';
import { selectionLabel } from '../catalog/types';
import { DraftImages } from './DraftImages';
import { trackerApi } from './api';
import { QC_ITEMS, SIGN_OFF_STEPS, STEP_FIELDS, countsMismatch, isSignedOff, wordCount, type FieldSpec } from './steps';
import type { DesignerRow, OrderSummary, TrackerStep, TrackerView } from './types';

type Role = 'SUPERADMIN' | 'OPERATIONS' | string;
const NOTE_WORDS = 100;

function toForm(step: TrackerStep, specs: FieldSpec[]): Record<string, string> {
  return Object.fromEntries(specs.map((spec) => [spec.key, step.data[spec.key] == null ? '' : String(step.data[spec.key])]));
}
function toPayload(specs: FieldSpec[], form: Record<string, string>, checks: number[] | null): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const spec of specs) {
    const raw = (form[spec.key] ?? '').trim();
    if (!raw) continue;
    payload[spec.key] = spec.kind === 'whole' || spec.kind === 'number' ? Number(raw) : raw;
  }
  if (checks) payload.checks = checks;
  return payload;
}

function Field({ spec, value, disabled, onChange }: { spec: FieldSpec; value: string; disabled: boolean; onChange: (value: string) => void }) {
  const id = `trk-${spec.key}`;
  return (
    <label className="field" htmlFor={id}>{spec.label}
      {spec.kind === 'textarea' ? <textarea id={id} rows={3} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
        : spec.kind === 'choice' ? (
          <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
            <option value="">Select…</option>{spec.choices?.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        ) : <input id={id} value={value} disabled={disabled} type={spec.kind === 'date' ? 'date' : spec.kind === 'text' ? 'text' : 'number'}
          min={spec.kind === 'whole' || spec.kind === 'number' ? 0 : undefined} onChange={(e) => onChange(e.target.value)} />}
    </label>
  );
}

export function StepWorkArea({ tracker, step, role, userId, designers, operators, summary = null, summaryError = '', onTracker }: {
  tracker: TrackerView; step: TrackerStep; role: Role; userId?: number; summary?: OrderSummary | null; summaryError?: string;
  designers: DesignerRow[]; operators: Array<{ userId: number; fullName: string }>; onTracker: (next: TrackerView) => void;
}) {
  const specs = STEP_FIELDS[step.step] ?? [];
  const superadmin = role === 'SUPERADMIN';
  const [form, setForm] = useState(() => toForm(step, specs));
  const [checks, setChecks] = useState<number[]>(() => (Array.isArray(step.data.checks) ? step.data.checks as number[] : []));
  const [note, setNote] = useState(step.stageNote ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [selectedDraft, setSelectedDraft] = useState<number | null>(null);
  const [comment, setComment] = useState('');
  const [followUp, setFollowUp] = useState({ percentComplete: '', nextFollowUpOn: '', contactPhone: '' });
  const [vendor, setVendor] = useState({ name: tracker.vendor?.name ?? '', phone: tracker.vendor?.phone ?? '' });

  // A fresh server copy (another save, a reopen) replaces whatever was typed against the old one.
  useEffect(() => {
    setForm(toForm(step, specs)); setChecks(Array.isArray(step.data.checks) ? step.data.checks as number[] : []); setNote(step.stageNote ?? '');
    setError(''); setMessage('');
  }, [step.step, step.version]);

  const act = async (operation: () => Promise<TrackerView>, done: string) => {
    setBusy(true); setError(''); setMessage('');
    try { onTracker(await operation()); setMessage(done); } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  };

  const editable = step.can.canEditDetails;
  const noteWords = wordCount(note);
  const signedOff = isSignedOff(step);
  const mismatch = step.step === 11 && countsMismatch(tracker.orderedCount, toPayload(specs, form, null));
  const design = tracker.design;
  const worker = useMemo(() => operators.find((o) => o.userId === step.assigneeUserId), [operators, step.assigneeUserId]);

  return (
    <div className="ck-trk-work">
      {step.step === 1 && (
        <dl className="ck-trk-facts">
          <div><dt>Order</dt><dd>{tracker.orderId}</dd></div>
          <div><dt>School</dt><dd>{tracker.schoolName ?? `School ${tracker.schoolId}`}</dd></div>
          <div><dt>Type</dt><dd>{tracker.customised ? 'Customised notebooks' : 'Wholesale notebooks'}</dd></div>
          <div><dt>Quantity</dt><dd>{tracker.orderedCount.toLocaleString('en-IN')}</dd></div>
          <div><dt>Placed</dt><dd>{new Date(tracker.placedAt).toLocaleString('en-IN', { timeZone: tracker.timeZone })}</dd></div>
          <div><dt>Required by</dt><dd>{summary?.requiredByDate ?? '—'}</dd></div>
        </dl>
      )}
      {step.step === 1 && summaryError && <p className="ck-product-error" role="alert">{summaryError}</p>}
      {step.step === 1 && summary && (
        <table className="ck-table" aria-label="Order lines"><thead><tr><th>Line</th><th>Size</th><th>Ruling</th><th className="ck-num">Books</th><th className="ck-num">Printed pages</th></tr></thead>
          <tbody>{summary.lines.map((line) => (
            <tr key={line.lineNo}><td>{line.lineNo}</td><td>{selectionLabel(line.selections, 'SIZE') || '—'}</td><td>{selectionLabel(line.selections, 'RULING') || '—'}</td>
              <td className="ck-num">{line.bookCount.toLocaleString('en-IN')}</td><td className="ck-num">{line.pageCount.toLocaleString('en-IN')}</td></tr>
          ))}</tbody></table>
      )}

      {step.step === 2 && (
        <>
          <label className="field">Designer
            <select value={tracker.designer?.id ?? ''} disabled={busy || !step.can.canEditDetails && !superadmin}
              onChange={(e) => e.target.value && void act(() => trackerApi.setDesigner(tracker.orderId, Number(e.target.value)), 'Designer saved.')}>
              <option value="">Select designer…</option>
              {designers.filter((d) => d.active || d.id === tracker.designer?.id).map((d) => <option key={d.id} value={d.id}>{d.name}{d.phone ? ` · ${d.phone}` : ''}</option>)}
            </select>
          </label>
          <DraftImages orderId={tracker.orderId} canManage={design.canManageImages} />
          <p className="ck-product-muted">Design status: <strong>{(design.state ?? 'NOT_SENT').replace(/_/g, ' ').toLowerCase()}</strong>
            {design.comment ? ` — ${design.comment}` : ''}</p>
          {(design.state === 'NOT_SENT' || design.state === 'CHANGES_REQUESTED') && (step.status === 'ACTIVE' || step.status === 'COMPLETED') && (
            <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={() => void act(() => trackerApi.sendDesign(tracker.orderId), 'Design sent for approval.')}>Send design for approval</button>
          )}
        </>
      )}

      {step.step === 3 && (
        <>
          <p>Design status: <strong>{(design.state ?? 'NOT_SENT').replace(/_/g, ' ').toLowerCase()}</strong>{design.comment ? ` — ${design.comment}` : ''}</p>
          <DraftImages orderId={tracker.orderId} canManage={false} selectedId={selectedDraft ?? design.approvedDraftRef} onSelect={setSelectedDraft} />
          {superadmin && (design.state === 'SENT' || design.state === 'CHANGES_REQUESTED') && (
            <div className="ck-trk-actions">
              <label className="field">Comment (optional)<textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
              <button type="button" className="ck-btn ck-btn-g" disabled={busy || selectedDraft == null}
                title={selectedDraft == null ? 'Select the draft to approve' : undefined}
                onClick={() => void act(() => trackerApi.approveDesign(tracker.orderId, selectedDraft as number, comment || undefined), 'Design approved.')}><Check size={15} aria-hidden="true" /> Approve selected draft</button>
              {design.state === 'SENT' && (
                <button type="button" className="ck-btn ck-btn-ghost" disabled={busy}
                  onClick={() => void act(() => trackerApi.requestChanges(tracker.orderId, comment || undefined), 'Changes requested.')}>Request changes</button>
              )}
            </div>
          )}
          {!superadmin && <p className="ck-product-muted">Only superadmin approves the design.</p>}
        </>
      )}

      {step.step === 8 && (
        <fieldset className="ck-product-fieldset">
          <legend>Pre-print checklist <span className="ck-product-muted">(optional)</span></legend>
          <ul className="ck-trk-checks">
            {QC_ITEMS.map((label, index) => (
              <li key={label}><label><input type="checkbox" checked={checks.includes(index)} disabled={!editable}
                onChange={(e) => setChecks(e.target.checked ? [...checks, index].sort((a, b) => a - b) : checks.filter((c) => c !== index))} /> {label}</label></li>
            ))}
          </ul>
        </fieldset>
      )}

      {step.step === 9 && <p className="ck-product-muted">Vendor: {tracker.vendor?.name ? `${tracker.vendor.name} · ${tracker.vendor.phone ?? ''}` : 'not assigned'}</p>}

      {step.step === 10 && (
        <>
          <table className="ck-table"><thead><tr><th>Logged</th><th className="ck-num">Complete</th><th>Next follow-up</th><th>Contact</th></tr></thead>
            <tbody>{tracker.followUps.length === 0 ? <tr><td colSpan={4} className="ck-product-muted">No follow-ups logged.</td></tr>
              : tracker.followUps.map((entry) => (
                <tr key={entry.id}><td>{new Date(entry.createdAt).toLocaleDateString('en-IN')}</td><td className="ck-num">{entry.percentComplete}%</td>
                  <td>{entry.nextFollowUpOn ?? '—'}</td><td>{entry.contactPhone ?? '—'}</td></tr>))}</tbody></table>
          {editable && (
            <div className="ck-product-fields">
              <label className="field">Production completed (%)<input type="number" min={0} max={100} value={followUp.percentComplete} onChange={(e) => setFollowUp({ ...followUp, percentComplete: e.target.value })} /></label>
              <label className="field">Next follow-up<input type="date" value={followUp.nextFollowUpOn} onChange={(e) => setFollowUp({ ...followUp, nextFollowUpOn: e.target.value })} /></label>
              <label className="field">Factory contact number<input value={followUp.contactPhone} onChange={(e) => setFollowUp({ ...followUp, contactPhone: e.target.value })} /></label>
              <div><button type="button" className="ck-btn ck-btn-g" disabled={busy || followUp.percentComplete === ''}
                onClick={() => void act(() => trackerApi.addFollowUp(tracker.orderId, { percentComplete: Number(followUp.percentComplete), nextFollowUpOn: followUp.nextFollowUpOn || undefined, contactPhone: followUp.contactPhone || undefined }), 'Follow-up logged.')}>Log follow-up</button></div>
            </div>
          )}
        </>
      )}

      {specs.length > 0 && (
        <div className="ck-product-fields">
          {specs.map((spec) => <Field key={spec.key} spec={spec} value={form[spec.key] ?? ''} disabled={!editable || busy} onChange={(value) => setForm({ ...form, [spec.key]: value })} />)}
        </div>
      )}
      {step.step === 11 && (
        <p className={mismatch ? 'ck-product-error' : 'ck-product-muted'} role={mismatch ? 'alert' : undefined}>
          Ordered {tracker.orderedCount.toLocaleString('en-IN')}.{' '}
          {mismatch ? 'The counts do not match — add a discrepancy note before completing.' : 'Accepted must equal dispatch and not exceed produced, which must not exceed ordered.'}
        </p>
      )}

      {step.step === 9 && superadmin && (
        <div className="ck-product-fields">
          <label className="field">Vendor name<input value={vendor.name} onChange={(e) => setVendor({ ...vendor, name: e.target.value })} /></label>
          <label className="field">Vendor phone<input value={vendor.phone} onChange={(e) => setVendor({ ...vendor, phone: e.target.value })} /></label>
          <div><button type="button" className="ck-btn ck-btn-ghost" disabled={busy || !vendor.name.trim()}
            onClick={() => void act(() => trackerApi.setVendor(tracker.orderId, vendor.name.trim(), vendor.phone.trim()), 'Vendor saved.')}>Save vendor</button></div>
        </div>
      )}

      <label className="field">Stage note <span className="ck-product-muted">{noteWords}/{NOTE_WORDS} words</span>
        <textarea rows={3} value={note} disabled={!step.can.canEditStageNote || busy} aria-invalid={noteWords > NOTE_WORDS} onChange={(e) => setNote(e.target.value)} />
      </label>
      {noteWords > NOTE_WORDS && <p className="ck-product-error" role="alert">A stage note is limited to {NOTE_WORDS} words.</p>}

      {error && <p className="ck-product-error" role="alert">{error}</p>}
      {message && <p className="ck-product-muted" role="status">{message}</p>}

      <div className="ck-trk-actions">
        {specs.length > 0 && editable && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy}
            onClick={() => void act(() => trackerApi.saveDraft(tracker.orderId, step.step, toPayload(specs, form, step.step === 8 ? checks : null), step.version), 'Draft saved.')}><Save size={15} aria-hidden="true" /> Save draft</button>
        )}
        {step.can.canEditStageNote && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy || noteWords > NOTE_WORDS}
            onClick={() => void act(() => trackerApi.saveStageNote(tracker.orderId, step.step, note, step.version), 'Stage note saved.')}>Save stage note</button>
        )}
        {superadmin && SIGN_OFF_STEPS.has(step.step) && step.status === 'ACTIVE' && !signedOff && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void act(() => trackerApi.signOff(tracker.orderId, step.step), 'Signed off.')}><ShieldCheck size={15} aria-hidden="true" /> Sign off</button>
        )}
        {SIGN_OFF_STEPS.has(step.step) && signedOff && <span className="ck-product-muted">Signed off</span>}
        {step.can.canComplete && (
          <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={() => void act(() => trackerApi.complete(tracker.orderId, step.step, step.version), 'Step completed.')}><Check size={15} aria-hidden="true" /> Complete step</button>
        )}
        {step.can.canReopen && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void act(() => trackerApi.reopen(tracker.orderId, step.step), 'Step reopened.')}><RotateCcw size={15} aria-hidden="true" /> Reopen step</button>
        )}
      </div>
      {superadmin && step.status !== 'COMPLETED' && (
        <label className="field">Assigned to
          <select value={step.assigneeUserId ?? ''} disabled={busy} onChange={(e) => e.target.value && void act(() => trackerApi.assignStep(tracker.orderId, step.step, Number(e.target.value)), 'Step reassigned.')}>
            <option value="">{worker ? worker.fullName : 'Unassigned'}</option>
            {operators.filter((o) => o.userId !== step.assigneeUserId).map((o) => <option key={o.userId} value={o.userId}>{o.fullName}</option>)}
          </select>
        </label>
      )}
      {!superadmin && step.assigneeUserId != null && userId != null && step.assigneeUserId !== userId && (
        <p className="ck-product-muted">This step is assigned to {worker?.fullName ?? 'another operator'}.</p>
      )}
    </div>
  );
}
