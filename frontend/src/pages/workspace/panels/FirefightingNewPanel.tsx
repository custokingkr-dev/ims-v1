import { useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { formatMoney } from '../utils';
import type { PanelKey } from '../config';
import { createQuotationDocumentClient, type DocumentCapabilities, type QuotationDocument } from '../../../generated/quotationDocumentClient';
import { useAuth } from '../../../contexts/AuthContext';
import { assertProcurementRecoveryUnchanged, clearProcurementRecovery, readProcurementRecovery, withProcurementRecoveryLock, writeProcurementRecovery, type PendingProcurementSave, type ProcurementRecovery } from './firefightingSaveRecovery';

const documents = createQuotationDocumentClient(api);
class RecoveryStorageUnavailable extends Error {}
const submittedStatuses = new Set(['AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'APPROVED', 'CUSTOKING_APPROVED', 'FULFILLED', 'REJECTED']);
function confirmedSubmission(data: { code?: unknown; status?: unknown }, code: string | null) {
  return data.code === code && typeof data.status === 'string' && submittedStatuses.has(data.status);
}

interface QuoteForm {
  vendorName: string;
  amount: string;
  deliveryTimeline: string;
  notes: string;
  documentUrl: string;
  file: File | null;
}

interface FfForm {
  title: string;
  category: string;
  estimatedBudget: string;
  urgency: string;
  requiredByDate: string;
  summary: string;
  quotations: QuoteForm[];
}

interface ExistingQuote {
  id?: string;
  vendorName: string;
  amount?: string | number;
  deliveryTimeline?: string;
  documentUrl?: string;
  notes?: string;
  document?: QuotationDocument | null;
  file?: File | null;
}

interface Props {
  editingCode: string | null;
  setPanel: (key: PanelKey) => void;
  onRefresh: () => Promise<void>;
}

const emptyQuote = (): QuoteForm => ({ vendorName: '', amount: '', deliveryTimeline: '', notes: '', documentUrl: '', file: null });
const ffFormInit = (): FfForm => ({
  title: '', category: 'Furniture & fixtures', estimatedBudget: '', urgency: 'MEDIUM',
  requiredByDate: '', summary: '', quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
});

export function FirefightingNewPanel(props: Props) {
  const { user } = useAuth();
  if (!user) return <ModuleShell title="Urgent Procurement" subtitle="Sign in to save a request."><p>Your school session is required to recover a save.</p></ModuleShell>;
  const recoveryKey = `ck:procurement-save:v1:${user.userId}:${user.branchId ?? 'platform'}:${props.editingCode ?? 'new'}`;
  return <FirefightingEditor key={recoveryKey} {...props} recoveryKey={recoveryKey} />;
}

function FirefightingEditor({ editingCode, setPanel, onRefresh, recoveryKey }: Props & { recoveryKey: string }) {
  const [initialRecovery] = useState(() => {
    try { return { value: readProcurementRecovery(recoveryKey), error: '' }; }
    catch { return { value: null, error: 'Saved procurement recovery data could not be read safely. Open requests to check the last save before repairing browser storage.' }; }
  });
  const [restoredCode, setRestoredCode] = useState(initialRecovery.value?.code ?? null);
  const recoveryRef = useRef<ProcurementRecovery>(initialRecovery.value ?? { version: 1, code: editingCode, pending: null });
  const storedRecoveryRef = useRef<ProcurementRecovery | null>(initialRecovery.value);
  const [pendingRecovery, setPendingRecovery] = useState(initialRecovery.value?.pending ?? null);
  const recoveredInitialOperation = useRef(false);
  const requestCode = useRef<string | null>(restoredCode || editingCode);
  const savingRef = useRef(false);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [loading, setLoading] = useState(Boolean(editingCode));
  const [loadError, setLoadError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [ffForm, setFfForm] = useState<FfForm>(ffFormInit());
  const [ffStep, setFfStep] = useState<1 | 2 | 3>(1);
  const [ffSaving, setFfSaving] = useState(false);
  const [ffError, setFfError] = useState('');
  const [ffDraftSaving, setFfDraftSaving] = useState(false);
  const [ffExistingQuotes, setFfExistingQuotes] = useState<ExistingQuote[]>([]);
  const [deletingQuoteId, setDeletingQuoteId] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [documentCapabilities, setDocumentCapabilities] = useState<DocumentCapabilities | null>(null);
  const [documentCapabilityError, setDocumentCapabilityError] = useState('');
  const [documentAttempt, setDocumentAttempt] = useState(0);
  const [documentBusy, setDocumentBusy] = useState(false);
  const pendingDocuments = useRef<Record<string, File>>({});
  const [pendingDocumentFiles, setPendingDocumentFiles] = useState<Record<string, File>>({});

  const remember = (next: ProcurementRecovery) => {
    assertProcurementRecoveryUnchanged(recoveryKey, storedRecoveryRef.current);
    writeProcurementRecovery(recoveryKey, next); // Must succeed before any create/submit request.
    storedRecoveryRef.current = next;
    recoveryRef.current = next; setPendingRecovery(next.pending);
  };
  const forgetRecovery = () => {
    assertProcurementRecoveryUnchanged(recoveryKey, storedRecoveryRef.current);
    clearProcurementRecovery(recoveryKey);
    storedRecoveryRef.current = null;
  };
  const replaySave = async (operation: PendingProcurementSave) => {
    assertProcurementRecoveryUnchanged(recoveryKey, storedRecoveryRef.current);
    let data: Record<string, unknown>;
    if (operation.kind === 'submit') {
      const current = await api.get<{ code?: string; status?: string }>(`/ff/requests/${operation.code}`);
      if (confirmedSubmission(current.data, operation.code)) data = current.data;
      else if (current.data.code === operation.code && current.data.status === 'DRAFT') {
        data = (await api.post<Record<string, unknown>>(`/ff/requests/${operation.code}/submit`)).data;
      } else throw new Error('The saved request status could not be confirmed.');
      if (!confirmedSubmission(data, operation.code)) throw new Error('The server did not confirm the submitted request status.');
    } else {
      const path = operation.kind === 'request' ? '/ff/requests' : `/ff/requests/${operation.code}/quotations`;
      data = (await api.post<Record<string, unknown>>(path, operation.payload)).data;
    }
    const code = operation.kind === 'request' ? data.code : operation.code;
    if (typeof code !== 'string' || !code.trim() || (operation.kind === 'quotation'
      && (typeof data.id !== 'string' || !data.id.trim() || data.requestId !== operation.code))) {
      throw new Error('The server did not return the saved identifier. Replay the original save to check its result.');
    }
    requestCode.current = code;
    remember({ version: 1, code, pending: null });
    return data;
  };
  const sendRecoverable = async (operation: PendingProcurementSave) => {
    try { remember({ version: 1, code: requestCode.current, pending: operation }); }
    catch { throw new RecoveryStorageUnavailable('Browser recovery storage is unavailable. This save was not sent. Restore browser storage before trying again.'); }
    return replaySave(operation);
  };
  const keepConfirmedQuote = (data: Record<string, unknown>, operation: PendingProcurementSave) => {
    const saved = { ...operation.payload, ...data } as unknown as ExistingQuote;
    setFfExistingQuotes(current => [...current.filter(quote => quote.id !== saved.id), saved]);
    if (operation.quoteIndex !== undefined) {
      const file = ffForm.quotations[operation.quoteIndex]?.file;
      if (file) queueDocument(saved.id!, file);
      setFfForm(current => ({ ...current, quotations: current.quotations.map((item, index) => index === operation.quoteIndex ? emptyQuote() : item) }));
    }
  };
  const recoverSaveLocked = async () => {
    if (!pendingRecovery || savingRef.current) return;
    savingRef.current = true; setFfDraftSaving(true); setFfError('');
    try {
      const saved = await replaySave(pendingRecovery);
      if (pendingRecovery.kind === 'quotation') keepConfirmedQuote(saved, pendingRecovery);
      if (pendingRecovery.kind === 'submit') {
        setSubmitted(true);
        try { forgetRecovery(); }
        catch { setFfError('Submission is confirmed, but the saved browser recovery record could not be cleared. Open requests to continue.'); }
      }
      if (initialRecovery.value?.pending && !recoveredInitialOperation.current) {
        recoveredInitialOperation.current = true;
        setRestoredCode(requestCode.current); setLoadAttempt(value => value + 1);
      }
      setToast(pendingRecovery.kind === 'submit' ? 'Submission confirmed. Open requests to review the current status.'
        : 'Save confirmed. Continue editing the saved draft; reselect any file that is not attached.');
    } catch (failure) {
      setFfError((failure as { response?: { data?: { message?: string } } })?.response?.data?.message
        || 'The result is still unconfirmed. The original save key is kept; retry when access and the connection are available.');
    } finally { savingRef.current = false; setFfDraftSaving(false); }
  };
  const recoverSave = async () => {
    if (savingRef.current) return;
    try { await withProcurementRecoveryLock(recoveryKey, recoverSaveLocked); }
    catch (failure) { setFfError((failure as Error).message || 'This browser could not safely start recovery.'); }
  };

  useEffect(() => {
    let active = true;
    setDocumentCapabilities(null); setDocumentCapabilityError('');
    documents.getCapabilities().then(capabilities => {
      if (active) setDocumentCapabilities(capabilities);
    }).catch(() => { if (active) setDocumentCapabilityError('File storage availability could not be checked. Retry before choosing an attachment.'); });
    return () => { active = false; };
  }, [documentAttempt]);

  const chooseFile = (file: File | undefined, apply: (file: File) => void) => {
    if (!file) return;
    if (!documentCapabilities?.canUpload) { setFfError('Quotation file uploads are currently unavailable. Your quotation details can still be saved.'); return; }
    if (!file.size || file.size > documentCapabilities.maxBytes || !documentCapabilities.allowedContentTypes.includes(file.type)) {
      setFfError('Choose a PDF, PNG, or JPEG file no larger than 5 MB.'); return;
    }
    setFfError(''); apply(file);
  };
  const queueDocument = (quoteId: string, file: File) => {
    pendingDocuments.current = { ...pendingDocuments.current, [quoteId]: file };
    setPendingDocumentFiles(pendingDocuments.current);
  };
  const clearQueuedDocument = (quoteId: string) => {
    const next = { ...pendingDocuments.current }; delete next[quoteId];
    pendingDocuments.current = next; setPendingDocumentFiles(next);
  };
  const removeDocument = async (quoteId: string) => {
    setDocumentBusy(true); setFfError('');
    try {
      await documents.removeDocument({ code: requestCode.current!, quotationId: quoteId });
      setFfExistingQuotes(current => current.map(quote => quote.id === quoteId ? { ...quote, document: null } : quote));
      clearQueuedDocument(quoteId);
      setToast('Quotation file removed. The quotation details are still saved.');
    } catch { setFfError('The file removal could not be confirmed. Reopen the request to check its attachment before trying again.'); }
    finally { setDocumentBusy(false); }
  };
  const downloadDocument = async (quote: ExistingQuote) => {
    if (!quote.id || !quote.document) return;
    setDocumentBusy(true); setFfError('');
    try {
      const file = await documents.downloadDocument({ code: requestCode.current!, quotationId: quote.id });
      const url = URL.createObjectURL(file);
      const link = document.createElement('a'); link.href = url; link.download = quote.document.filename;
      try { document.body.appendChild(link); link.click(); }
      finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
    } catch { setFfError('The quotation file could not be downloaded. Retry when the connection and file storage are available.'); }
    finally { setDocumentBusy(false); }
  };

  const deleteExistingQuote = async (quoteId: string | undefined) => {
    if (!requestCode.current || !quoteId) return;
    setDeletingQuoteId(quoteId);
    setFfError('');
    try {
      await api.delete(`/ff/requests/${requestCode.current}/quotations/${quoteId}`);
      setFfExistingQuotes((prev) => prev.filter((x) => x.id !== quoteId));
      clearQueuedDocument(quoteId);
    } catch (err) {
      setFfError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Could not delete quotation.');
    } finally {
      setDeletingQuoteId(null);
    }
  };

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    let active = true;
    const code = restoredCode || editingCode;
    if (initialRecovery.value?.pending && !recoveredInitialOperation.current) { setLoading(false); return; }
    requestCode.current = code;
    pendingDocuments.current = {}; setPendingDocumentFiles({});
    setSubmitted(false); setLoadError(''); setFfError('');
    if (!code) {
      setFfForm(ffFormInit()); setFfStep(1); setFfExistingQuotes([]); setLoading(false);
      return;
    }
    setLoading(true);
    api.get<{ code: string; title: string; category: string; estimatedBudget?: number; urgency: string; requiredByDate?: string; description?: string; status?: string; quotations?: ExistingQuote[] }>(`/ff/requests/${code}`)
      .then((res) => {
        if (!active) return;
        const d = res.data;
        if (d.status && d.status !== 'DRAFT') setSubmitted(true);
        setFfExistingQuotes(d.quotations || []);
        setFfForm({
          title: d.title || '', category: d.category || 'Furniture & fixtures',
          estimatedBudget: d.estimatedBudget != null ? String(d.estimatedBudget) : '',
          urgency: d.urgency || 'MEDIUM', requiredByDate: d.requiredByDate || '',
          summary: d.description || '', quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
        });
        setFfStep(2);
      })
      .catch(() => { if (active) setLoadError('The draft could not be opened. Retry before editing so existing details are preserved.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [editingCode, restoredCode, loadAttempt]);

  const setFfQuote = (idx: number, field: string, value: string) =>
    setFfForm(f => { const qs = f.quotations.map((q, i) => i === idx ? { ...q, [field]: value } : q); return { ...f, quotations: qs }; });

  const validateQuotations = () => {
    for (let index = 0; index < ffForm.quotations.length; index++) {
      const quote = ffForm.quotations[index];
      const started = Object.values(quote).some(value => typeof value === 'string' ? value.trim() : !!value);
      if (!started) continue;
      if (!quote.vendorName.trim() || !quote.amount.trim() || !Number.isSafeInteger(Number(quote.amount)) || Number(quote.amount) <= 0) {
        setFfError(`Quotation ${index + 1}: enter a vendor name and a whole-number amount greater than zero, or clear the quotation.`);
        return false;
      }
      if ([quote.vendorName, quote.deliveryTimeline, quote.notes, quote.documentUrl].some(value => value.length > 255)) {
        setFfError(`Quotation ${index + 1}: keep each text field to 255 characters or fewer.`);
        return false;
      }
    }
    return true;
  };

  const persistRequestLocked = async (submit: boolean) => {
    if (savingRef.current || submitted || loading || loadError || pendingRecovery || initialRecovery.error) return;
    if (!ffForm.title.trim()) { setFfError('Enter a request title before saving.'); return; }
    if (ffForm.title.length > 255 || ffForm.summary.length > 2000) { setFfError('Keep the title to 255 characters and the description to 2,000 characters or fewer.'); return; }
    if (!Number.isSafeInteger(Number(ffForm.estimatedBudget || 0)) || Number(ffForm.estimatedBudget || 0) < 0) { setFfError('Enter an estimated budget as a whole number, zero or greater.'); return; }
    if (ffForm.requiredByDate && (!/^\d{4}-\d{2}-\d{2}$/.test(ffForm.requiredByDate)
      || Number.isNaN(Date.parse(ffForm.requiredByDate)) || new Date(ffForm.requiredByDate).toISOString().slice(0, 10) !== ffForm.requiredByDate)) {
      setFfError('Enter a valid required-by date.'); return;
    }
    if (!validateQuotations()) return;
    savingRef.current = true;
    submit ? setFfSaving(true) : setFfDraftSaving(true);
    setFfError('');
    try {
      assertProcurementRecoveryUnchanged(recoveryKey, storedRecoveryRef.current);
      if (requestCode.current) {
        await api.patch(`/ff/requests/${requestCode.current}`, {
          title: ffForm.title.trim(), category: ffForm.category, urgency: ffForm.urgency,
          requiredByDate: ffForm.requiredByDate || null,
          estimatedBudget: ffForm.estimatedBudget ? Number(ffForm.estimatedBudget) : 0,
          description: ffForm.summary,
        });
      } else {
        const created = await sendRecoverable({ kind: 'request', code: null, payload: {
          title: ffForm.title, category: ffForm.category, urgency: ffForm.urgency, summary: ffForm.summary,
          requiredByDate: ffForm.requiredByDate || null, estimatedBudget: Number(ffForm.estimatedBudget || 0),
          idempotencyKey: crypto.randomUUID(),
        } });
        requestCode.current = String(created.code);
      }
      // Keep each confirmed quotation on retries instead of creating it again.
      for (const [index, quote] of ffForm.quotations.entries()) {
        if (!quote.vendorName.trim()) continue;
        const payload = {
          vendorName: quote.vendorName.trim(), amount: Number(quote.amount),
          deliveryTimeline: quote.deliveryTimeline.trim(), notes: quote.notes.trim(), documentUrl: quote.documentUrl.trim(),
          idempotencyKey: crypto.randomUUID(),
        };
        const operation: PendingProcurementSave = { kind: 'quotation', code: requestCode.current, payload, quoteIndex: index, fileName: quote.file?.name };
        keepConfirmedQuote(await sendRecoverable(operation), operation);
      }
      for (const [quoteId, file] of Object.entries(pendingDocuments.current)) {
        const uploaded = await documents.uploadDocument({ code: requestCode.current!, quotationId: quoteId }, { file });
        setFfExistingQuotes(current => current.map(quote => quote.id === quoteId ? { ...quote, document: uploaded } : quote));
        clearQueuedDocument(quoteId);
      }
      if (submit) {
        await sendRecoverable({ kind: 'submit', code: requestCode.current, payload: {} });
        setSubmitted(true);
      }
      try {
        await onRefresh();
        forgetRecovery();
        setPanel('ff-dashboard');
      } catch {
        setFfError(`${submit ? 'Request submitted' : 'Draft saved'} as ${requestCode.current}, but the dashboard refresh or browser recovery cleanup could not finish. Open requests to review its status.`);
      }
    } catch (failure) {
      if (failure instanceof RecoveryStorageUnavailable) { setFfError(failure.message); return; }
      const reason = (failure as { response?: { data?: { message?: string } } })?.response?.data?.message;
      if (recoveryRef.current.pending?.kind === 'submit') {
        try {
          const current = await api.get<{ code?: string; status?: string }>(`/ff/requests/${requestCode.current}`);
          if (confirmedSubmission(current.data, requestCode.current)) {
            remember({ version: 1, code: requestCode.current, pending: null }); setSubmitted(true);
            try { forgetRecovery(); } catch { /* Confirmed code remains safe to reopen. */ }
            setFfError(`Submission is confirmed. Current status: ${current.data.status}. Open requests to continue.`);
            return;
          }
        } catch { /* Keep the original submission pending until authoritative confirmation. */ }
      }
      setFfError(`The last save result is unconfirmed. It may already be saved. Confirmed quotations are kept.${Object.keys(pendingDocuments.current).length ? ' A file upload may also have completed; retry safely replaces its attachment.' : ''}${reason ? ` ${reason}` : ''}`);
    } finally {
      savingRef.current = false;
      setFfSaving(false); setFfDraftSaving(false);
    }
  };
  const persistRequest = async (submit: boolean) => {
    if (savingRef.current) return;
    try { await withProcurementRecoveryLock(recoveryKey, () => persistRequestLocked(submit)); }
    catch (failure) { setFfError((failure as Error).message || 'This browser could not safely start the save.'); }
  };
  const saveFfAsDraft = () => void persistRequest(false);
  const submitFfRequest = () => void persistRequest(true);
  const reviewQuotes = [...ffExistingQuotes, ...ffForm.quotations.filter(quote => quote.vendorName.trim())];
  const busy = ffSaving || ffDraftSaving || deletingQuoteId !== null || documentBusy;

  if (pendingRecovery || initialRecovery.error) return <ModuleShell title="Urgent Procurement — Check saved result" subtitle="Resolve the previous save before changing its details.">
    <p role="alert">{initialRecovery.error || ffError || 'A save response was not confirmed. The request may already exist; recovering uses its original save key.'}</p>
    <p>The original request or vendor details are kept in this browser for your school account until the save is confirmed. Clearing browser data removes that recovery record; check your requests before starting again. Files must be reselected after reopening.</p>
    {pendingRecovery && <><p>{pendingRecovery.kind === 'request' ? 'Request creation' : pendingRecovery.kind === 'quotation' ? 'Quotation creation' : 'Submission'}{pendingRecovery.code ? ` for ${pendingRecovery.code}` : ''} is awaiting confirmation.</p>
      <button type="button" className="ck-btn ck-btn-g" disabled={busy} onClick={() => void recoverSave()}>{busy ? 'Checking saved result…' : 'Recover saved result'}</button></>}
    <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => setPanel('ff-dashboard')}>Open requests</button>
  </ModuleShell>;

  if (loading || loadError) return <ModuleShell title="Urgent Procurement — Edit Draft" subtitle="Open the saved request before making changes.">
    {loading ? <p role="status">Opening draft…</p> : <div role="alert"><p>{loadError}</p><button className="ck-btn ck-btn-ghost" onClick={() => setLoadAttempt(value => value + 1)}>Retry opening draft</button></div>}
  </ModuleShell>;

  return (
    <>
    <ModuleShell title={editingCode ? 'Urgent Procurement — Edit Draft' : 'Urgent Procurement — New Request'} subtitle={editingCode ? `Editing draft ${editingCode} — add quotations and submit for approval` : 'Raise a procurement request for anything not in the Custoking catalog'}>
      <fieldset disabled={busy || submitted} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
      <div className="ck-step-bar">
        {([['Describe need', 'What do you need?'], ['Add quotations', 'Optional — speeds up approval'], ['Review & submit', 'Send for approval']] as [string, string][]).map(([label, sub], i) => (
          <button type="button" disabled={ffStep <= i + 1} key={i} className={`ck-step ${ffStep > i + 1 ? 'done' : ffStep === i + 1 ? 'active' : ''}`} onClick={() => { if (ffStep > i + 1) setFfStep((i + 1) as 1 | 2 | 3); }}>
            <span>{ffStep > i + 1 ? '✓' : i + 1}</span>
            <span><strong>{label}</strong><small>{sub}</small></span>
          </button>
        ))}
      </div>

      {ffStep === 1 && (
        <div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 280px), 1fr))', gap: 18 }}>
            <div className="ck-form-card">
              <div className="ck-form-head">What do you need?</div>
              <div className="ck-form-body">
                {ffError && <div role="alert" className="ck-alert ck-alert-re" style={{ marginBottom: 12 }}><span>✕</span><div>{ffError}</div></div>}
                <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 12 }}>
                  <Field label="Request title *" style={{ gridColumn: '1 / -1' }}><input maxLength={255} value={ffForm.title} onChange={(e) => setFfForm({ ...ffForm, title: e.target.value })} placeholder="e.g. Science lab benches, CCTV cameras…" /></Field>
                  <Field label="Category"><select value={ffForm.category} onChange={(e) => setFfForm({ ...ffForm, category: e.target.value })}><option>Furniture & fixtures</option><option>Electronics & security</option><option>Lab equipment</option><option>Sports & playground</option><option>Services & AMC</option><option>Civil & construction</option><option>Events & occasions</option><option>Other</option></select></Field>
                  <Field label="Urgency *"><select value={ffForm.urgency} onChange={(e) => setFfForm({ ...ffForm, urgency: e.target.value })}><option value="HIGH">High — needed within 7 days</option><option value="MEDIUM">Medium — within 30 days</option><option value="LOW">Low — no strict deadline</option></select></Field>
                  <Field label="Required by date"><input type="date" value={ffForm.requiredByDate} onChange={(e) => setFfForm({ ...ffForm, requiredByDate: e.target.value })} /></Field>
                  <Field label="Estimated budget (₹)"><input type="number" min={0} step="1" value={ffForm.estimatedBudget} onChange={(e) => setFfForm({ ...ffForm, estimatedBudget: e.target.value })} placeholder="Approximate amount" /></Field>
                </div>
                <Field label="Description"><textarea maxLength={2000} value={ffForm.summary} onChange={(e) => setFfForm({ ...ffForm, summary: e.target.value })} placeholder="Describe exactly what you need — quantity, size, specs, where it will be used…" style={{ minHeight: 80 }} /></Field>
              </div>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div className="ck-form-card">
                <div className="ck-form-head">How it works</div>
                <div className="ck-form-body" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {[['1', 'or', 'Describe your need and add 2–3 vendor quotations'], ['2', 'or', 'Finance Review: budget check on the quotes'], ['3', 'or', 'Admin Approval: final sign-off'], ['4', 'g', 'Custoking fulfils — single invoice to your school']].map(([n, tone, text]) => (
                    <div key={n} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                      <div style={{ width: 22, height: 22, borderRadius: '50%', background: `var(--${tone}1)`, border: `1.5px solid var(--${tone})`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 700, color: `var(--${tone})`, flexShrink: 0 }}>{n}</div>
                      <div style={{ fontSize: 12.5, color: 'var(--ink2)' }}>{text}</div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8, marginTop: 4 }}>
            <button className="ck-btn ck-btn-ghost" disabled={ffDraftSaving} onClick={saveFfAsDraft}>{ffDraftSaving ? 'Saving…' : 'Save as draft'}</button>
            <button className="ck-btn ck-btn-or" onClick={() => { if (!ffForm.title.trim()) { setFfError('Request title is required'); return; } setFfError(''); setFfStep(2); }}>Next — add quotations →</button>
          </div>
        </div>
      )}

      {ffStep === 2 && (
        <div>
          <div className="ck-alert ck-alert-b" style={{ marginBottom: 18 }}>
            <span>ℹ</span>
            <div>Quotations are optional. Add a vendor name and amount, then attach the vendor's PDF or image. Files are saved privately when you save the draft or submit the request.</div>
          </div>
          {documentCapabilityError ? <p role="alert">{documentCapabilityError} <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setDocumentAttempt(value => value + 1)}>Retry file storage</button></p>
            : !documentCapabilities ? <p role="status">Checking private file storage…</p>
            : !documentCapabilities.available ? <p role="status">{documentCapabilities.unavailableReason || 'Private quotation file storage is unavailable.'} You can still save quotation details and document references. <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setDocumentAttempt(value => value + 1)}>Retry file storage</button></p>
            : !documentCapabilities.canUpload ? <p role="status">Your access allows viewing quotation files. Uploads require permission to update this request.</p> : null}
          {ffExistingQuotes.length > 0 && (
            <div style={{ marginBottom: 14 }}>
              <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', marginBottom: 10 }}>Saved quotations</div>
              {ffExistingQuotes.map((q, idx) => (
                <div key={q.id || idx} style={{ background: 'var(--g1)', border: '1px solid var(--g)', borderRadius: 10, padding: '12px 16px', marginBottom: 8, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 14 }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>{q.vendorName}</div>
                    <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{q.amount ? `₹${formatMoney(Number(q.amount))}` : '—'}{q.deliveryTimeline ? ` · ${q.deliveryTimeline}` : ''}{q.documentUrl ? ` · 📄 ${q.documentUrl}` : ''}</div>
                    {q.document && <p style={{ overflowWrap: 'anywhere' }}><strong>Attached:</strong> {q.document.filename} ({Math.ceil(q.document.sizeBytes / 1024)} KB) <button type="button" className="ck-btn ck-btn-ghost" onClick={() => void downloadDocument(q)}>Download file</button> <button type="button" className="ck-btn ck-btn-ghost" disabled={!documentCapabilities?.canUpload} onClick={() => void removeDocument(q.id!)}>Remove file</button></p>}
                    {q.id && documentCapabilities?.canUpload && <Field label={`${q.document ? 'Replace' : 'Attach'} file for ${q.vendorName}`} hint="PDF, PNG, or JPEG up to 5 MB. Replacement is saved only after upload succeeds."><input type="file" accept="application/pdf,image/png,image/jpeg" onChange={event => { chooseFile(event.target.files?.[0], file => queueDocument(q.id!, file)); event.target.value = ''; }} /></Field>}
                    {q.id && pendingDocumentFiles[q.id] && <p role="status">{pendingDocumentFiles[q.id].name} is selected, not yet confirmed uploaded. <button type="button" className="ck-btn ck-btn-ghost" onClick={() => clearQueuedDocument(q.id!)}>Discard selected file</button></p>}
                  </div>
                  <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--g)', background: 'var(--ck-bg-surface)', padding: '2px 8px', borderRadius: 5 }}>✓ Saved</span>
                  {q.id ? (
                    <button className="ck-btn ck-btn-ghost" disabled={deletingQuoteId === q.id} onClick={() => deleteExistingQuote(q.id)} title="Remove this quotation">{deletingQuoteId === q.id ? '…' : 'Delete'}</button>
                  ) : null}
                </div>
              ))}
              <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', margin: '16px 0 10px' }}>Add more quotations</div>
            </div>
          )}
          {ffForm.quotations.map((q, idx) => (
            <div className="ck-form-card" key={idx} style={{ marginBottom: 14 }}>
              <div className="ck-form-head"><span style={{ fontWeight: 600 }}>Quotation {idx + 1}</span></div>
              <div className="ck-form-body">
                {documentCapabilities?.canUpload && <Field label={`Quotation file ${idx + 1} (optional)`} hint="PDF, PNG, or JPEG up to 5 MB. Selected files upload when you save."><input type="file" accept="application/pdf,image/png,image/jpeg" onChange={event => { chooseFile(event.target.files?.[0], file => setFfForm(current => ({ ...current, quotations: current.quotations.map((item, index) => index === idx ? { ...item, file } : item) }))); event.target.value = ''; }} /></Field>}
                {q.file && <p role="status">{q.file.name} selected · not uploaded yet <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setFfForm(current => ({ ...current, quotations: current.quotations.map((item, index) => index === idx ? { ...item, file: null } : item) }))}>Remove selected file</button></p>}
                <Field label={`Document reference for quotation ${idx + 1} (optional)`}>
                  <input maxLength={255} value={q.documentUrl} onChange={event => setFfQuote(idx, 'documentUrl', event.target.value)} placeholder="Document ID, file name or access-controlled link" aria-describedby={`ff-reference-help-${idx}`} />
                </Field>
                <p id={`ff-reference-help-${idx}`} style={{ fontSize: 14, color: 'var(--ink2)', margin: '6px 0 16px' }}>Only this reference is saved. Share the document with reviewers through your school's usual document channel.</p>
                <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 10 }}>
                  <Field label="Vendor name *"><input maxLength={255} value={q.vendorName} onChange={(e) => setFfQuote(idx, 'vendorName', e.target.value)} placeholder="Vendor / supplier name" /></Field>
                  <Field label="Quoted amount (₹) *"><input type="number" min={0} step="1" value={q.amount} onChange={(e) => setFfQuote(idx, 'amount', e.target.value)} placeholder="Total quote amount" /></Field>
                  <Field label="Delivery timeline"><input maxLength={255} value={q.deliveryTimeline} onChange={(e) => setFfQuote(idx, 'deliveryTimeline', e.target.value)} placeholder="e.g. 2–3 weeks" /></Field>
                </div>
                <Field label="Notes"><input maxLength={255} value={q.notes} onChange={(e) => setFfQuote(idx, 'notes', e.target.value)} placeholder="Includes installation, GST, warranty…" /></Field>
              </div>
            </div>
          ))}
          {ffError && <p role="alert">{ffError}</p>}
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
            <button className="ck-btn ck-btn-ghost" onClick={saveFfAsDraft}>Save as draft</button>
            <button className="ck-btn ck-btn-ghost" onClick={() => setFfStep(1)}>← Back</button>
            <button className="ck-btn ck-btn-or" onClick={() => { if (validateQuotations()) { setFfError(''); setFfStep(3); } }}>Next — review & submit →</button>
          </div>
        </div>
      )}

      {ffStep === 3 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 280px), 1fr))', gap: 18 }}>
          <div>
            <div className="ck-form-card">
              <div className="ck-form-head">Request summary</div>
              <div className="ck-form-body">
                <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 4 }}>{ffForm.title || '(untitled)'}</div>
                <div style={{ fontSize: 13, color: 'var(--ink2)', marginBottom: 12 }}>{ffForm.summary || '—'}</div>
                <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
                  <span style={{ background: 'var(--or1)', color: 'var(--or)', fontSize: 11, fontWeight: 700, padding: '2px 9px', borderRadius: 5 }}>{ffForm.category}</span>
                  <span style={{ background: ffForm.urgency === 'HIGH' ? 'var(--re1)' : ffForm.urgency === 'LOW' ? 'var(--g1)' : 'var(--am1)', color: ffForm.urgency === 'HIGH' ? 'var(--re)' : ffForm.urgency === 'LOW' ? 'var(--g)' : 'var(--am)', fontSize: 11, fontWeight: 700, padding: '2px 9px', borderRadius: 5 }}>{ffForm.urgency}</span>
                  {ffForm.estimatedBudget && <span style={{ fontSize: 12, color: 'var(--ink2)' }}>Est. budget ₹{formatMoney(Number(ffForm.estimatedBudget))}</span>}
                </div>
                {reviewQuotes.length > 0 && (
                  <>
                    <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', marginBottom: 10 }}>Quotation comparison</div>
                    <div style={{ overflowX: 'auto' }}><table className="ck-table" style={{ marginBottom: 0 }}>
                      <thead><tr><th>Vendor</th><th>Amount</th><th>Delivery</th><th>Notes</th><th>Quotation document</th></tr></thead>
                      <tbody>
                        {reviewQuotes.map((q, i) => (
                          <tr key={i}>
                            <td style={{ fontWeight: 600 }}>{q.vendorName}</td>
                            <td style={{ fontWeight: 700, color: 'var(--g)' }}>{q.amount ? `₹${formatMoney(Number(q.amount))}` : '—'}</td>
                            <td>{q.deliveryTimeline || '—'}</td>
                            <td style={{ fontSize: 12, color: 'var(--ink2)' }}>{q.notes || '—'}</td>
                            <td style={{ overflowWrap: 'anywhere' }}>{'document' in q && q.document ? q.document.filename : q.file ? `${q.file.name} (uploads when saved)` : 'id' in q && q.id && pendingDocumentFiles[q.id] ? `${pendingDocumentFiles[q.id].name} (upload pending)` : q.documentUrl || 'Not provided'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table></div>
                  </>
                )}
              </div>
            </div>
            <div className="ck-form-card" style={{ marginBottom: 0 }}>
              <div className="ck-form-head">Approval routing</div>
              <div className="ck-form-body" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {[['1', 'am', 'Finance Review', 'Budget check'], ['2', 'b', 'Admin Approval', 'Final sign-off'], ['3', 'g', 'Custoking fulfils', 'After approval']].map(([n, tone, title, sub]) => (
                  <div key={n} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 12px', background: 'var(--bg)', borderRadius: 8 }}>
                    <div style={{ width: 32, height: 32, borderRadius: '50%', background: `var(--${tone}1)`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 700, color: `var(--${tone})`, flexShrink: 0 }}>{n}</div>
                    <div style={{ flex: 1 }}><div style={{ fontSize: 13, fontWeight: 600 }}>{title}</div><div style={{ fontSize: 12, color: 'var(--ink3)' }}>{sub}</div></div>
                    <span style={{ fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 20, background: 'var(--am1)', color: 'var(--am)' }}>Pending</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div className="ck-form-card">
              <div className="ck-form-head">Submit checklist</div>
              <div style={{ padding: '8px 0' }}>
                {[[!!ffForm.title, 'Request title filled'], [reviewQuotes.length >= 2, '2 quotations added (recommended)'], [reviewQuotes.some(q => q.amount), 'Vendor amounts entered'], [!!(ffForm.urgency), 'Urgency set']].map(([ok, label], i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 16px', borderBottom: i < 3 ? '1px solid var(--border)' : 'none', fontSize: 13 }}>
                    <span style={{ color: ok ? 'var(--g)' : 'var(--ink3)', fontSize: 15 }}>{ok ? '✓' : '○'}</span>
                    <span style={{ color: ok ? 'var(--ink)' : 'var(--ink3)' }}>{label as string}</span>
                  </div>
                ))}
              </div>
            </div>
            {ffError && <div role="alert" className="ck-alert ck-alert-re"><span>✕</span><div>{ffError}</div></div>}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <button className="ck-btn ck-btn-or" style={{ justifyContent: 'center' }} disabled={ffSaving} onClick={submitFfRequest}>{ffSaving ? 'Submitting…' : 'Submit for approval →'}</button>
              <button className="ck-btn ck-btn-ghost" style={{ justifyContent: 'center' }} onClick={() => setFfStep(2)}>← Back to quotations</button>
            </div>
            <div className="ck-alert ck-alert-g"><span>✓</span><div>After submission, review the request status in Urgent Procurement. Submission does not confirm that a notification was delivered.</div></div>
          </div>
        </div>
      )}
      </fieldset>
      {requestCode.current && <button className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => setPanel('ff-dashboard')}>Open requests</button>}
    </ModuleShell>

      {toast && (
        <div className="ck-command-toast ok">
          {toast}
        </div>
      )}
    </>
  );
}
