import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, FileSpreadsheet, RotateCcw, Save, ShieldCheck } from 'lucide-react';
import { selectionLabel } from '../catalog/types';
import { DraftImages } from './DraftImages';
import { trackerApi, trackerError } from './api';
import { PRINTING_CHECK_ITEMS, PRODUCTION_FOLLOW_UP_ITEMS, PRODUCTION_PLANNING_ITEMS, QC_ITEMS, SIGN_OFF_STEPS, STEP_FIELDS, VENDOR_CATEGORY_LABELS, VENDOR_DIRECTORY, countsMismatch, formatDay, isSignedOff, titleCardQuantities, titleCardQuantitiesFromCounts, vendorCategoriesForStep, wordCount, type FieldSpec, type VendorCategory } from './steps';
import type { DesignerRow, OrderSummary, TrackerContact, TrackerDesigner, TrackerStep, TrackerView } from './types';
import type { NotebookWorkbookPreview } from '../../generated/catalogClient';
import '../catalog/product-form.css';

type Role = 'SUPERADMIN' | 'OPERATIONS' | string;
const NOTE_WORDS = 100;

function toForm(step: TrackerStep, specs: FieldSpec[]): Record<string, string> {
  return Object.fromEntries(specs.map((spec) => [spec.key, step.data[spec.key] == null ? '' : String(step.data[spec.key])]));
}
function fieldValue(spec: FieldSpec, form: Record<string, string>): string | number | null {
  const raw = (form[spec.key] ?? '').trim();
  if (!raw) return null;
  return spec.kind === 'whole' || spec.kind === 'number' ? Number(raw) : raw;
}
/** The typed fields only, empty ones left out: what the count-mismatch warning reads. */
function toPayload(specs: FieldSpec[], form: Record<string, string>): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const spec of specs) {
    const value = fieldValue(spec, form);
    if (value != null) payload[spec.key] = value;
  }
  return payload;
}

/** Steps whose work area has a checklist. */
const CHECKLIST_STEPS = new Set([5, 7, 8, 10]);
/** Step 5's workbook snapshot: written only by "Save workbook totals", and sent back as saved by every other save of the step. */
const WORKBOOK_KEYS = ['workbookFileName', 'workbookSheetName', 'workbookOrderId', 'workbookImportedAt', 'workbookTotalQuantity', 'workbookKing',
  'workbookJumboKing', 'workbookLong', 'workbookJumboLong', 'workbookFa', 'workbookValidRows'];

/**
 * The whole work area as one save: every field (an emptied one as null, so the server clears it), the checklist and, on
 * step 5, the saved workbook totals. A save that sent only its own part used to wipe the rest (dev audit 2026-11-12, #8).
 */
function stepData(step: TrackerStep, specs: FieldSpec[], form: Record<string, string>, checks: number[], checkField: string): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  if (step.step === 5) for (const key of WORKBOOK_KEYS) if (step.data[key] != null) data[key] = step.data[key];
  for (const spec of specs) data[spec.key] = fieldValue(spec, form);
  if (CHECKLIST_STEPS.has(step.step)) data[checkField] = checks;
  return data;
}

function blank(value: unknown): boolean { return value == null || value === '' || (Array.isArray(value) && value.length === 0); }
function sameValue(a: unknown, b: unknown): boolean {
  if (blank(a) || blank(b)) return blank(a) && blank(b);
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((item, i) => Number(item) === Number(b[i]));
  if (typeof a === 'number' || typeof b === 'number') return Number(a) === Number(b);
  return String(a) === String(b);
}
/** Whether the screen holds work the server has not saved: an empty field or checklist matches a missing one. */
function unsaved(step: TrackerStep, data: Record<string, unknown>): boolean {
  return Object.entries(data).some(([key, value]) => !sameValue(value, step.data[key]));
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

/** The order's one vendor (spec v4: assigned by superadmin, name and phone), shown on the design and printing steps. */
function VendorCard({ heading, nameLabel, contactLabel, vendor, editable, busy, onSave }: {
  heading: string; nameLabel: string; contactLabel: string; vendor: TrackerContact | null; editable: boolean; busy: boolean;
  onSave: (name: string, phone: string) => void;
}) {
  const [form, setForm] = useState({ name: vendor?.name ?? '', phone: vendor?.phone ?? '' });
  useEffect(() => { setForm({ name: vendor?.name ?? '', phone: vendor?.phone ?? '' }); }, [vendor?.name, vendor?.phone]);
  return (
    <section className="ck-trk-card" aria-label={heading}>
      <h4>{heading}</h4>
      <dl className="ck-trk-facts">
        <div><dt>{nameLabel}</dt><dd>{vendor?.name || 'Not assigned'}</dd></div>
        <div><dt>{contactLabel}</dt><dd>{vendor?.phone || '—'}</dd></div>
      </dl>
      {editable && (
        <div className="ck-product-fields">
          <label className="field">Vendor name<input value={form.name} disabled={busy} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label className="field">Vendor phone<input type="tel" value={form.phone} disabled={busy} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
          <div><button type="button" className="ck-btn ck-btn-ghost" disabled={busy || !form.name.trim()} onClick={() => onSave(form.name.trim(), form.phone.trim())}>Save vendor</button></div>
        </div>
      )}
    </section>
  );
}

/** The order's designer (spec v4: chosen per order from the superadmin's designer list; name and phone). */
function DesignerCard({ designer, designers, editable, busy, onChoose }: {
  designer: TrackerDesigner | null; designers: DesignerRow[]; editable: boolean; busy: boolean; onChoose: (id: number) => void;
}) {
  return (
    <section className="ck-trk-card" aria-label="Designer">
      <h4>Designer</h4>
      <dl className="ck-trk-facts">
        <div><dt>Name</dt><dd>{designer?.name ?? 'Not chosen'}</dd></div>
        <div><dt>Phone</dt><dd>{designer?.phone || '—'}</dd></div>
      </dl>
      {editable && (
        <label className="field">Choose designer
          <select value={designer?.id ?? ''} disabled={busy} onChange={(e) => e.target.value && onChoose(Number(e.target.value))}>
            <option value="">Select designer…</option>
            {designers.filter((d) => d.active || d.id === designer?.id).map((d) => <option key={d.id} value={d.id}>{d.name}{d.phone ? ` · ${d.phone}` : ''}</option>)}
          </select>
        </label>
      )}
    </section>
  );
}

function SelectedVendorGroups({ step, selections }: { step: number; selections: Record<string, string[]> }) {
  const categories = vendorCategoriesForStep(step).filter((category) => step === 5 || (selections[category]?.length ?? 0) > 0);
  if (!categories.length && step !== 7) return null;
  return (
    <section className="ck-trk-card" aria-label="Selected vendors">
      <h4>Selected vendors</h4>
      {step === 7 && <p><strong>Internal Approval Group</strong></p>}
      {categories.map((category) => {
        const selected = VENDOR_DIRECTORY[category].filter((vendor) => selections[category]?.includes(vendor.id));
        return (
          <div key={category}>
            <h5>{VENDOR_CATEGORY_LABELS[category]}</h5>
            {selected.length ? <ul>{selected.map((vendor) => <li key={vendor.id}>{vendor.name}</li>)}</ul>
              : <p className="ck-product-muted">No {VENDOR_CATEGORY_LABELS[category].toLowerCase()} selected for this order.</p>}
          </div>
        );
      })}
    </section>
  );
}

const WHATSAPP_GROUP_TARGETS = [
  { key: 'SCHOOL_GROUP', label: 'School group' },
  { key: 'INTERNAL_APPROVAL', label: 'Internal approval group' },
  ...VENDOR_DIRECTORY.board.map((vendor) => ({ key: vendor.id, label: `${vendor.name} · board supplier` })),
  ...VENDOR_DIRECTORY.lamination.map((vendor) => ({ key: vendor.id, label: `${vendor.name} · lamination` })),
  ...VENDOR_DIRECTORY.binder.map((vendor) => ({ key: vendor.id, label: `${vendor.name} · binder` })),
  ...VENDOR_DIRECTORY.offset.map((vendor) => ({ key: vendor.id, label: `${vendor.name} · offset printer` })),
] as const;
type WhatsAppGroupTarget = (typeof WHATSAPP_GROUP_TARGETS)[number]['key'];

function WhatsAppGroupLinks({ orderId, editable }: { orderId: string; editable: boolean }) {
  const [links, setLinks] = useState<Record<string, string>>({});
  const [target, setTarget] = useState<WhatsAppGroupTarget>('SCHOOL_GROUP');
  const [url, setUrl] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    trackerApi.whatsappGroupLinks(orderId)
      .then((result) => { if (active) { setLinks(result); setUrl(result[target] ?? ''); } })
      .catch((failure: unknown) => { if (active) setError(trackerError(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [orderId]);

  const save = async () => {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const updated = await trackerApi.saveWhatsAppGroupLink(orderId, target, url.trim());
      setLinks(updated);
      setUrl(updated[target] ?? '');
      setNotice(updated[target] ? 'WhatsApp redirect saved for this school.' : 'WhatsApp redirect removed.');
    } catch (failure) {
      setError(trackerError(failure));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="ck-trk-card" aria-label="WhatsApp group redirects">
      <h4>WhatsApp group redirects</h4>
      <p className="ck-product-muted">Configured links apply to this school. Only validated WhatsApp group invites are opened.</p>
      {error && <p className="ck-product-error" role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      {loading ? <p role="status">Loading group links…</p> : (
        <div className="ck-trk-group-links">
          {Object.entries(links).map(([key, link]) => {
            const targetLabel = WHATSAPP_GROUP_TARGETS.find((item) => item.key === key)?.label ?? key;
            let href: string | null = null;
            try {
              const parsed = new URL(link);
              if (parsed.protocol === 'https:' && parsed.hostname === 'chat.whatsapp.com') href = parsed.href;
            } catch { /* Invalid persisted links are not made clickable. */ }
            return href ? <a key={key} className="ck-btn ck-btn-ghost" href={href} target="_blank" rel="noopener noreferrer">
              Open {targetLabel}
            </a> : null;
          })}
          {!Object.keys(links).length && <p className="ck-product-muted">No group redirects are configured.</p>}
        </div>
      )}
      {editable && !loading && <div className="ck-product-fields">
        <label className="field">Group target
          <select value={target} disabled={saving} onChange={(event) => {
            const next = event.target.value;
            const selected = WHATSAPP_GROUP_TARGETS.find((item) => item.key === next);
            if (!selected) return;
            setTarget(selected.key);
            setUrl(links[next] ?? '');
          }}>
            {WHATSAPP_GROUP_TARGETS.map((item) => <option key={item.key} value={item.key}>{item.label}</option>)}
          </select>
        </label>
        <label className="field">WhatsApp invite link
          <input type="url" maxLength={2048} placeholder="https://chat.whatsapp.com/…" value={url}
            disabled={saving} onChange={(event) => setUrl(event.target.value)} />
        </label>
        <div><button type="button" className="ck-btn ck-btn-ghost" disabled={saving}
          onClick={() => void save()}>{saving ? 'Saving…' : url.trim() ? 'Save redirect' : 'Remove redirect'}</button></div>
      </div>}
    </section>
  );
}

export function StepWorkArea({ tracker, step, role, userId, designers, operators, summary = null, summaryError = '', onTracker }: {
  tracker: TrackerView; step: TrackerStep; role: Role; userId?: number; summary?: OrderSummary | null; summaryError?: string;
  designers: DesignerRow[]; operators: Array<{ userId: number; fullName: string }>; onTracker: (next: TrackerView) => void;
}) {
  const specs = STEP_FIELDS[step.step] ?? [];
  const checkField = step.step === 5 ? 'planningChecks' : step.step === 8 ? 'pressChecks' : step.step === 10 ? 'productionChecks' : 'checks';
  const superadmin = role === 'SUPERADMIN';
  const [form, setForm] = useState(() => toForm(step, specs));
  const [checks, setChecks] = useState<number[]>(() => (Array.isArray(step.data[checkField]) ? step.data[checkField] as number[] : []));
  const [note, setNote] = useState(step.stageNote ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [selectedDraft, setSelectedDraft] = useState<number | null>(null);
  const [comment, setComment] = useState('');
  const [workbookFile, setWorkbookFile] = useState<File | null>(null);
  const [workbookPreview, setWorkbookPreview] = useState<NotebookWorkbookPreview | null>(null);
  const [followUp, setFollowUp] = useState({ percentComplete: '', nextFollowUpOn: '', contactPhone: '' });
  const [vendorSelections, setVendorSelections] = useState<Record<string, string[]>>(() => tracker.vendorSelections);

  // Set when this screen's own save brought the fresh copy below and a refusal that followed it must stay on screen.
  const keepNotice = useRef(false);

  // A fresh server copy (another save, a reopen) replaces whatever was typed against the old one.
  useEffect(() => {
    setForm(toForm(step, specs)); setChecks(Array.isArray(step.data[checkField]) ? step.data[checkField] as number[] : []); setNote(step.stageNote ?? '');
    setVendorSelections(tracker.vendorSelections);
    setWorkbookFile(null);
    setWorkbookPreview(null);
    if (keepNotice.current) keepNotice.current = false;
    else { setError(''); setMessage(''); }
  }, [step.step, step.version, checkField, tracker.orderId, tracker.version]);

  const act = async (operation: () => Promise<TrackerView>, done: string) => {
    setBusy(true); setError(''); setMessage('');
    try { onTracker(await operation()); setMessage(done); } catch (e) { setError(trackerError(e)); } finally { setBusy(false); }
  };

  const editable = step.can.canEditDetails;
  const noteWords = wordCount(note);
  const signedOff = isSignedOff(step);
  const draft = stepData(step, specs, form, checks, checkField);
  const dirty = editable && (specs.length > 0 || CHECKLIST_STEPS.has(step.step)) && unsaved(step, draft);
  // Saving changes to a signed-off step takes the sign-off back (spec: Sequence and drafts), so completing over them would only be refused.
  const needsNewSignOff = SIGN_OFF_STEPS.has(step.step) && signedOff && dirty;
  /**
   * Sign off and Complete act on what is saved, so unsaved ticks and fields are saved first and the action then runs on the
   * version that save left (dev audit 2026-11-12, #9). A refused action still leaves the draft saved, and says why.
   */
  const actAfterSaving = async (operation: (version: number) => Promise<TrackerView>, done: string) => {
    if (!dirty) return act(() => operation(step.version), done);
    setBusy(true); setError(''); setMessage('');
    let saved: TrackerView | null = null;
    try {
      saved = await trackerApi.saveDraft(tracker.orderId, step.step, draft, step.version);
      const version = saved.steps.find((s) => s.step === step.step)?.version ?? step.version;
      onTracker(await operation(version)); setMessage(done);
    } catch (e) {
      if (saved) { keepNotice.current = true; onTracker(saved); }
      setError(trackerError(e));
    } finally { setBusy(false); }
  };
  const mismatch = step.step === 11 && countsMismatch(tracker.orderedCount, toPayload(specs, form));
  // A wholesale order has no design approval (null); only the customised design steps 2 and 3 read it.
  const design = tracker.design;
  // Head approval completes only once a design is approved; the server checks it too, so say why before it is refused.
  const awaitingApproval = step.step === 4 && design?.state !== 'APPROVED';
  const completeHint = useId();
  const worker = useMemo(() => operators.find((o) => o.userId === step.assigneeUserId), [operators, step.assigneeUserId]);
  const saveVendor = (name: string, phone: string) => void act(() => trackerApi.setVendor(tracker.orderId, name, phone), 'Vendor saved.');
  const saveVendorSelections = () => void act(
    () => trackerApi.setVendorSelections(tracker.orderId, vendorSelections),
    'Vendor selections saved.',
  );
  const previewWorkbook = async () => {
    if (!workbookFile) return;
    setBusy(true); setError(''); setMessage('');
    try {
      setWorkbookPreview(await trackerApi.previewWorkbook(tracker.orderId, workbookFile));
      setMessage('Workbook preview ready. No order data has been changed.');
    } catch (e) {
      setError(trackerError(e));
    } finally {
      setBusy(false);
    }
  };
  const saveWorkbookTotals = () => {
    if (!workbookPreview) return;
    const snapshot = {
      ...draft,
      workbookFileName: workbookPreview.fileName,
      workbookSheetName: workbookPreview.sheetName,
      workbookOrderId: workbookPreview.workbookOrderId ?? '',
      workbookImportedAt: new Date().toISOString(),
      workbookTotalQuantity: workbookPreview.total,
      workbookKing: workbookPreview.king,
      workbookJumboKing: workbookPreview.jumboKing,
      workbookLong: workbookPreview.longBooks,
      workbookJumboLong: workbookPreview.jumboLong,
      workbookFa: workbookPreview.fa,
      workbookValidRows: workbookPreview.validRows,
    };
    void act(() => trackerApi.saveDraft(tracker.orderId, step.step, snapshot, step.version), 'Workbook totals saved to planning.');
  };
  const toggleVendor = (category: VendorCategory, id: string, checked: boolean) => setVendorSelections((current) => {
    const existing = current[category] ?? [];
    const next = checked ? [...existing, id] : existing.filter((selected) => selected !== id);
    return { ...current, [category]: next };
  });

  return (
    <div className="ck-trk-work">
      <WhatsAppGroupLinks orderId={tracker.orderId} editable={superadmin} />
      {step.step === 1 && (
        <dl className="ck-trk-facts">
          <div><dt>Order</dt><dd>{tracker.orderId}</dd></div>
          <div><dt>School</dt><dd>{tracker.schoolName ?? `School ${tracker.schoolId}`}</dd></div>
          <div><dt>Type</dt><dd>{tracker.customised ? 'Customised notebooks' : 'Wholesale notebooks'}</dd></div>
          <div><dt>Quantity</dt><dd>{tracker.orderedCount.toLocaleString('en-IN')}</dd></div>
          <div><dt>Placed</dt><dd>{new Date(tracker.placedAt).toLocaleString('en-IN', { timeZone: tracker.timeZone })}</dd></div>
          <div><dt>Required by</dt><dd>{summary?.requiredByDate ? formatDay(summary.requiredByDate) : '—'}</dd></div>
        </dl>
      )}
      {step.step === 1 && summaryError && <p className="ck-product-error" role="alert">{summaryError}</p>}
      {step.step === 1 && summary && (
        <div className="ck-table-wrap"><table className="ck-table" aria-label="Order lines"><thead><tr><th>Line</th><th>Size</th><th>Ruling</th><th className="ck-num">Books</th><th className="ck-num">Printed pages</th></tr></thead>
          <tbody>{summary.lines.map((line) => (
            <tr key={line.lineNo}><td>{line.lineNo}</td><td>{selectionLabel(line.selections, 'SIZE') || '—'}</td><td>{selectionLabel(line.selections, 'RULING') || '—'}</td>
              <td className="ck-num">{line.bookCount.toLocaleString('en-IN')}</td><td className="ck-num">{line.pageCount.toLocaleString('en-IN')}</td></tr>
          ))}</tbody></table></div>
      )}
      {step.step === 5 && summary && (
        <section className="ck-trk-card" aria-label="Title-card sheet quantities">
          <h4>Title-card sheet quantities</h4>
          <p className="ck-product-muted">Calculated from the saved notebook order quantities.</p>
          <dl className="ck-trk-facts">
            {Object.entries(titleCardQuantities(summary.lines)).map(([category, quantity]) => (
              <div key={category}><dt>{category === 'king' ? 'King + Jumbo King' : category === 'jumboLong' ? 'Jumbo Long' : category.toUpperCase()}</dt>
                <dd>{quantity.toLocaleString('en-IN')} sheets</dd></div>
            ))}
          </dl>
        </section>
      )}
      {step.step === 5 && (
        <section className="ck-trk-card" aria-label="Notebook workbook planning">
          <h4><FileSpreadsheet size={16} aria-hidden="true" /> Workbook planning</h4>
          <p className="ck-product-muted">Preview an .xlsx order workbook, then explicitly save its totals to planning. Catalogue order lines are never changed.</p>
          {editable && (
            <div className="ck-trk-actions">
              <label className="field">Order workbook (.xlsx)
                <input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                  disabled={busy} onChange={(event) => {
                    setWorkbookFile(event.target.files?.[0] ?? null);
                    setWorkbookPreview(null);
                    setError('');
                    setMessage('');
                  }} />
              </label>
              <button type="button" className="ck-btn ck-btn-ghost" disabled={busy || !workbookFile} onClick={() => void previewWorkbook()}>
                Preview workbook
              </button>
            </div>
          )}
          {workbookPreview && (
            <div className="ck-trk-workbook-preview" aria-live="polite">
              <p><strong>Preview only:</strong> {workbookPreview.fileName} · {workbookPreview.sheetName}
                {workbookPreview.workbookOrderId ? ` · Workbook order ${workbookPreview.workbookOrderId}` : ''}</p>
              <dl className="ck-trk-facts">
                <div><dt>Total books</dt><dd>{workbookPreview.total.toLocaleString('en-IN')}</dd></div>
                <div><dt>King</dt><dd>{workbookPreview.king.toLocaleString('en-IN')}</dd></div>
                <div><dt>Jumbo King</dt><dd>{workbookPreview.jumboKing.toLocaleString('en-IN')}</dd></div>
                <div><dt>Long</dt><dd>{workbookPreview.longBooks.toLocaleString('en-IN')}</dd></div>
                <div><dt>Jumbo Long</dt><dd>{workbookPreview.jumboLong.toLocaleString('en-IN')}</dd></div>
                <div><dt>FA</dt><dd>{workbookPreview.fa.toLocaleString('en-IN')}</dd></div>
              </dl>
              <p className="ck-product-muted">Calculated title-card sheets: {Object.entries(titleCardQuantitiesFromCounts({
                king: workbookPreview.king, jumboKing: workbookPreview.jumboKing, long: workbookPreview.longBooks,
                jumboLong: workbookPreview.jumboLong, fa: workbookPreview.fa,
              })).map(([category, quantity]) =>
                `${category === 'king' ? 'King + Jumbo King' : category === 'jumboLong' ? 'Jumbo Long' : category.toUpperCase()}: ${quantity.toLocaleString('en-IN')}`).join(' · ')}</p>
              {editable && <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={saveWorkbookTotals}>
                <Save size={15} aria-hidden="true" /> Save workbook totals
              </button>}
            </div>
          )}
          {typeof step.data.workbookTotalQuantity === 'number' && (
            <div className="ck-trk-workbook-saved">
              <h5>Saved planning snapshot</h5>
              <p>{String(step.data.workbookFileName ?? 'Workbook')} · {String(step.data.workbookSheetName ?? 'Sheet')}
                {step.data.workbookImportedAt ? ` · Saved ${new Date(String(step.data.workbookImportedAt)).toLocaleString('en-IN')}` : ''}</p>
              <p>{Number(step.data.workbookTotalQuantity).toLocaleString('en-IN')} total books across {Number(step.data.workbookValidRows ?? 0).toLocaleString('en-IN')} rows.</p>
            </div>
          )}
        </section>
      )}
      {step.step === 5 && (
        <section className="ck-trk-card" aria-label="Select vendors for this order">
          <h4>Select vendors for this order</h4>
          <p className="ck-product-muted">Only selected vendors appear in their relevant downstream stages.</p>
          <div className="ck-trk-cards">
            {(Object.keys(VENDOR_DIRECTORY) as VendorCategory[]).map((category) => (
              <fieldset key={category} className="ck-product-fieldset">
                <legend>{VENDOR_CATEGORY_LABELS[category]}</legend>
                {VENDOR_DIRECTORY[category].map((vendor) => (
                  <label key={vendor.id} className="ck-trk-vendor-option">
                    <input type="checkbox" checked={vendorSelections[category]?.includes(vendor.id) ?? false}
                      disabled={!superadmin || busy}
                      onChange={(event) => toggleVendor(category, vendor.id, event.target.checked)} />
                    {vendor.name}
                  </label>
                ))}
              </fieldset>
            ))}
          </div>
          {superadmin && <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={saveVendorSelections}>Save vendor selections</button>}
        </section>
      )}

      {step.step === 2 && design && (
        <>
          <div className="ck-trk-cards">
            <VendorCard heading="Vendor" nameLabel="Name" contactLabel="Phone" vendor={tracker.vendor} editable={superadmin} busy={busy} onSave={saveVendor} />
            <DesignerCard designer={tracker.designer} designers={designers} editable={editable || superadmin} busy={busy}
              onChoose={(id) => void act(() => trackerApi.setDesigner(tracker.orderId, id), 'Designer saved.')} />
          </div>
          <DraftImages orderId={tracker.orderId} canManage={design.canManageImages} preview />
          <p className="ck-product-muted">Design status: <strong>{(design.state ?? 'NOT_SENT').replace(/_/g, ' ').toLowerCase()}</strong>
            {design.comment ? ` — ${design.comment}` : ''}</p>
          {(design.state === 'NOT_SENT' || design.state === 'CHANGES_REQUESTED') && (step.status === 'ACTIVE' || step.status === 'COMPLETED') && (
            <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={() => void act(() => trackerApi.sendDesign(tracker.orderId), 'Design sent for approval.')}>Send design for approval</button>
          )}
        </>
      )}

      {step.step === 4 && design && (
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

      {step.step === 5 && (
        <fieldset className="ck-product-fieldset">
          <legend>Production planning checklist</legend>
          <ul className="ck-trk-checks">
            {PRODUCTION_PLANNING_ITEMS.map((label, index) => (
              <li key={label}><label><input type="checkbox" checked={checks.includes(index)} disabled={!editable}
                onChange={(e) => setChecks(e.target.checked ? [...checks, index].sort((a, b) => a - b) : checks.filter((c) => c !== index))} /> {label}</label></li>
            ))}
          </ul>
        </fieldset>
      )}
      {step.step === 7 && (
        <fieldset className="ck-product-fieldset">
          <legend>Pre-print checklist <span className="ck-product-muted">(all checks required)</span></legend>
          <ul className="ck-trk-checks">
            {QC_ITEMS.map((label, index) => (
              <li key={label}><label><input type="checkbox" checked={checks.includes(index)} disabled={!editable}
                onChange={(e) => setChecks(e.target.checked ? [...checks, index].sort((a, b) => a - b) : checks.filter((c) => c !== index))} /> {label}</label></li>
            ))}
          </ul>
        </fieldset>
      )}
      {step.step === 8 && (
        <fieldset className="ck-product-fieldset">
          <legend>Printing &amp; lamination checklist</legend>
          <ul className="ck-trk-checks">
            {PRINTING_CHECK_ITEMS.map((label, index) => (
              <li key={label}><label><input type="checkbox" checked={checks.includes(index)} disabled={!editable}
                onChange={(e) => setChecks(e.target.checked ? [...checks, index].sort((a, b) => a - b) : checks.filter((c) => c !== index))} /> {label}</label></li>
            ))}
          </ul>
        </fieldset>
      )}

      {step.step === 8 && (
        <VendorCard heading="Printing vendor" nameLabel="Printing vendor" contactLabel="Contact" vendor={tracker.vendor} editable={superadmin} busy={busy} onSave={saveVendor} />
      )}
      {step.step >= 6 && <SelectedVendorGroups step={step.step} selections={tracker.vendorSelections} />}

      {step.step === 10 && (
        <>
          <fieldset className="ck-product-fieldset">
            <legend>Production follow-up checklist</legend>
            <ul className="ck-trk-checks">
              {PRODUCTION_FOLLOW_UP_ITEMS.map((label, index) => (
                <li key={label}><label><input type="checkbox" checked={checks.includes(index)} disabled={!editable}
                  onChange={(e) => setChecks(e.target.checked ? [...checks, index].sort((a, b) => a - b) : checks.filter((c) => c !== index))} /> {label}</label></li>
              ))}
            </ul>
          </fieldset>
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

      <label className="field">Stage note <span className="ck-product-muted">{noteWords}/{NOTE_WORDS} words</span>
        <textarea rows={3} value={note} disabled={!step.can.canEditStageNote || busy} aria-invalid={noteWords > NOTE_WORDS} onChange={(e) => setNote(e.target.value)} />
      </label>
      {noteWords > NOTE_WORDS && <p className="ck-product-error" role="alert">A stage note is limited to {NOTE_WORDS} words.</p>}

      {error && <p className="ck-product-error" role="alert">{error}</p>}
      {message && <p className="ck-product-muted" role="status">{message}</p>}

      <div className="ck-trk-actions">
        {(specs.length > 0 || step.step === 10) && editable && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy}
            onClick={() => void act(() => trackerApi.saveDraft(tracker.orderId, step.step, draft, step.version), 'Draft saved.')}><Save size={15} aria-hidden="true" /> Save draft</button>
        )}
        {step.can.canEditStageNote && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy || noteWords > NOTE_WORDS}
            onClick={() => void act(() => trackerApi.saveStageNote(tracker.orderId, step.step, note, step.version), 'Stage note saved.')}>Save stage note</button>
        )}
        {superadmin && SIGN_OFF_STEPS.has(step.step) && step.status === 'ACTIVE' && !signedOff && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void actAfterSaving((version) => trackerApi.signOff(tracker.orderId, step.step, version), 'Signed off.')}><ShieldCheck size={15} aria-hidden="true" /> Sign off</button>
        )}
        {SIGN_OFF_STEPS.has(step.step) && signedOff && <span className="ck-product-muted">Signed off</span>}
        {step.can.canComplete && (
          <button type="button" className="ck-btn ck-btn-g" disabled={busy || awaitingApproval || needsNewSignOff}
            aria-describedby={awaitingApproval || needsNewSignOff ? completeHint : undefined}
            onClick={() => void actAfterSaving((version) => trackerApi.complete(tracker.orderId, step.step, version), 'Step completed.')}><Check size={15} aria-hidden="true" /> Complete step</button>
        )}
        {step.can.canComplete && awaitingApproval && <span id={completeHint} className="ck-product-muted">Approve a design first</span>}
        {step.can.canComplete && needsNewSignOff && <span id={completeHint} className="ck-product-muted">Your changes need a new sign-off: save the draft, then sign off again</span>}
        {step.can.canReopen && (
          <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void act(() => trackerApi.reopen(tracker.orderId, step.step), 'Step reopened.')}><RotateCcw size={15} aria-hidden="true" /> Reopen step</button>
        )}
      </div>
      {superadmin && step.status !== 'COMPLETED' && (
        <label className="field">Assigned to
          <select value={step.assigneeUserId ?? ''} disabled={busy} onChange={(e) => e.target.value && void act(() => trackerApi.assignStep(tracker.orderId, step.step, Number(e.target.value)), 'Step reassigned.')}>
            <option value="">{step.assigneeName ?? worker?.fullName ?? (step.assigneeUserId != null ? `User ${step.assigneeUserId}` : 'Unassigned')}</option>
            {operators.filter((o) => o.userId !== step.assigneeUserId).map((o) => <option key={o.userId} value={o.userId}>{o.fullName}</option>)}
          </select>
        </label>
      )}
      {!superadmin && step.assigneeUserId != null && userId != null && step.assigneeUserId !== userId && (
        <p className="ck-product-muted">This step is assigned to {step.assigneeName ?? worker?.fullName ?? 'another operator'}.</p>
      )}
    </div>
  );
}
