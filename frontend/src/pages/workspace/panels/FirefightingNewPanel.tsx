import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { formatMoney } from '../utils';
import type { PanelKey } from '../config';

interface QuoteForm {
  vendorName: string;
  amount: string;
  deliveryTimeline: string;
  notes: string;
  documentUrl: string;
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
}

interface Props {
  editingCode: string | null;
  setPanel: (key: PanelKey) => void;
  onRefresh: () => Promise<void>;
}

const emptyQuote = (): QuoteForm => ({ vendorName: '', amount: '', deliveryTimeline: '', notes: '', documentUrl: '' });
const ffFormInit = (): FfForm => ({
  title: '', category: 'Furniture & fixtures', estimatedBudget: '', urgency: 'MEDIUM',
  requiredByDate: '', summary: '', quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
});

export function FirefightingNewPanel({ editingCode, setPanel, onRefresh }: Props) {
  const [ffForm, setFfForm] = useState<FfForm>(ffFormInit());
  const [ffStep, setFfStep] = useState<1 | 2 | 3>(1);
  const [ffSaving, setFfSaving] = useState(false);
  const [ffError, setFfError] = useState('');
  const [ffDraftSaving, setFfDraftSaving] = useState(false);
  const [ffExistingQuotes, setFfExistingQuotes] = useState<ExistingQuote[]>([]);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    if (!editingCode) { setFfForm(ffFormInit()); setFfStep(1); setFfExistingQuotes([]); return; }
    api.get<{ code: string; title: string; category: string; estimatedBudget?: number; urgency: string; requiredByDate?: string; description?: string; quotations?: ExistingQuote[] }>(`/ff/requests/${editingCode}`)
      .then((res) => {
        const d = res.data;
        setFfExistingQuotes(d.quotations || []);
        setFfForm({
          title: d.title || '',
          category: d.category || 'Furniture & fixtures',
          estimatedBudget: d.estimatedBudget ? String(d.estimatedBudget) : '',
          urgency: d.urgency || 'MEDIUM',
          requiredByDate: d.requiredByDate || '',
          summary: d.description || '',
          quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
        });
        setFfStep(2);
      })
      .catch((err: unknown) => {
        const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to open draft';
        setToast(msg);
      });
  }, [editingCode]);

  const setFfQuote = (idx: number, field: string, value: string) =>
    setFfForm(f => { const qs = f.quotations.map((q, i) => i === idx ? { ...q, [field]: value } : q); return { ...f, quotations: qs }; });

  const saveFfAsDraft = async () => {
    if (!ffForm.title.trim()) { setFfError('Request title is required'); return; }
    setFfDraftSaving(true); setFfError('');
    try {
      if (editingCode) {
        await api.patch(`/ff/requests/${editingCode}`, {
          title: ffForm.title, category: ffForm.category, urgency: ffForm.urgency,
          requiredByDate: ffForm.requiredByDate || null,
          estimatedBudget: ffForm.estimatedBudget ? Number(ffForm.estimatedBudget) : 0,
          description: ffForm.summary,
        });
      } else {
        await api.post('/workspace/firefighting', { ...ffForm, status: 'draft' });
      }
      await onRefresh();
      setPanel('ff-dashboard');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to save draft';
      setFfError(msg);
    } finally {
      setFfDraftSaving(false);
    }
  };

  const submitFfRequest = async () => {
    if (!ffForm.title.trim()) { setFfError('Request title is required'); return; }
    setFfSaving(true); setFfError('');
    try {
      if (editingCode) {
        await api.patch(`/ff/requests/${editingCode}`, {
          title: ffForm.title, category: ffForm.category, urgency: ffForm.urgency,
          requiredByDate: ffForm.requiredByDate || null,
          estimatedBudget: ffForm.estimatedBudget ? Number(ffForm.estimatedBudget) : 0,
          description: ffForm.summary,
        });
        const newQuotes = ffForm.quotations.filter(q => q.vendorName.trim());
        for (const q of newQuotes) {
          await api.post(`/ff/requests/${editingCode}/quotations`, {
            vendorName: q.vendorName, amount: q.amount ? Number(q.amount) : 0,
            deliveryTimeline: q.deliveryTimeline, notes: q.notes, documentUrl: q.documentUrl,
          });
        }
        await api.post(`/ff/requests/${editingCode}/submit`);
      } else {
        await api.post('/workspace/firefighting', ffForm);
      }
      await onRefresh();
      setPanel('ff-dashboard');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to submit request';
      setFfError(msg);
    } finally {
      setFfSaving(false);
    }
  };

  return (
    <>
    <ModuleShell title={editingCode ? 'Urgent Procurement — Edit Draft' : 'Urgent Procurement — New Request'} subtitle={editingCode ? `Editing draft ${editingCode} — add quotations and submit for approval` : 'Raise a procurement request for anything not in the Custoking catalog'}>
      <div className="ck-step-bar">
        {([['Describe need', 'What do you need?'], ['Add quotations', 'Optional — speeds up approval'], ['Review & submit', 'Send for approval']] as [string, string][]).map(([label, sub], i) => (
          <div key={i} className={`ck-step ${ffStep > i + 1 ? 'done' : ffStep === i + 1 ? 'active' : ''}`} onClick={() => { if (ffStep > i + 1) setFfStep((i + 1) as 1 | 2 | 3); }}>
            <span>{ffStep > i + 1 ? '✓' : i + 1}</span>
            <div><strong>{label}</strong><small>{sub}</small></div>
          </div>
        ))}
      </div>

      {ffStep === 1 && (
        <div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 300px', gap: 18 }}>
            <div className="ck-form-card">
              <div className="ck-form-head">What do you need?</div>
              <div className="ck-form-body">
                {ffError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 12 }}><span>✕</span><div>{ffError}</div></div>}
                <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 12 }}>
                  <Field label="Request title *" style={{ gridColumn: 'span 2' }}><input value={ffForm.title} onChange={(e) => setFfForm({ ...ffForm, title: e.target.value })} placeholder="e.g. Science lab benches, CCTV cameras…" /></Field>
                  <Field label="Category"><select value={ffForm.category} onChange={(e) => setFfForm({ ...ffForm, category: e.target.value })}><option>Furniture & fixtures</option><option>Electronics & security</option><option>Lab equipment</option><option>Sports & playground</option><option>Services & AMC</option><option>Civil & construction</option><option>Events & occasions</option><option>Other</option></select></Field>
                  <Field label="Urgency *"><select value={ffForm.urgency} onChange={(e) => setFfForm({ ...ffForm, urgency: e.target.value })}><option value="HIGH">High — needed within 7 days</option><option value="MEDIUM">Medium — within 30 days</option><option value="LOW">Low — no strict deadline</option></select></Field>
                  <Field label="Required by date"><input type="date" value={ffForm.requiredByDate} onChange={(e) => setFfForm({ ...ffForm, requiredByDate: e.target.value })} /></Field>
                  <Field label="Estimated budget (₹)"><input type="number" value={ffForm.estimatedBudget} onChange={(e) => setFfForm({ ...ffForm, estimatedBudget: e.target.value })} placeholder="Approximate amount" /></Field>
                </div>
                <Field label="Description *"><textarea value={ffForm.summary} onChange={(e) => setFfForm({ ...ffForm, summary: e.target.value })} placeholder="Describe exactly what you need — quantity, size, specs, where it will be used…" style={{ minHeight: 80 }} /></Field>
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
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
            <button className="ck-btn ck-btn-ghost" disabled={ffDraftSaving} onClick={saveFfAsDraft}>{ffDraftSaving ? 'Saving…' : 'Save as draft'}</button>
            <button className="ck-btn ck-btn-or" onClick={() => { if (!ffForm.title.trim()) { setFfError('Request title is required'); return; } setFfError(''); setFfStep(2); }}>Next — add quotations →</button>
          </div>
        </div>
      )}

      {ffStep === 2 && (
        <div>
          <div className="ck-alert ck-alert-b" style={{ marginBottom: 18 }}>
            <span>ℹ</span>
            <div>You need at least <strong>2 quotations</strong> from different vendors. Add vendor name, amount, and upload their quote document or photo. Quotations are optional but speed up approval.</div>
          </div>
          {ffExistingQuotes.length > 0 && (
            <div style={{ marginBottom: 14 }}>
              <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', marginBottom: 10 }}>Already submitted quotations</div>
              {ffExistingQuotes.map((q, idx) => (
                <div key={q.id || idx} style={{ background: 'var(--g1)', border: '1px solid var(--g)', borderRadius: 10, padding: '12px 16px', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 14 }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>{q.vendorName}</div>
                    <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{q.amount ? `₹${formatMoney(Number(q.amount))}` : '—'}{q.deliveryTimeline ? ` · ${q.deliveryTimeline}` : ''}{q.documentUrl ? ` · 📄 ${q.documentUrl}` : ''}</div>
                  </div>
                  <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--g)', background: '#fff', padding: '2px 8px', borderRadius: 5 }}>✓ Saved</span>
                </div>
              ))}
              <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', margin: '16px 0 10px' }}>Add more quotations</div>
            </div>
          )}
          {ffForm.quotations.map((q, idx) => (
            <div className="ck-form-card" key={idx} style={{ marginBottom: 14 }}>
              <div className="ck-form-head"><span style={{ fontWeight: 600 }}>Quotation {idx + 1}</span></div>
              <div className="ck-form-body">
                <div style={{ border: q.documentUrl ? '2px solid var(--g)' : '2px dashed var(--border2)', borderRadius: 12, padding: '28px 20px', textAlign: 'center', cursor: 'pointer', background: q.documentUrl ? 'var(--g1)' : 'var(--bg)', marginBottom: 16 }} onClick={() => (document.getElementById(`ff-file-${idx}`) as HTMLInputElement)?.click()}>
                  <input type="file" id={`ff-file-${idx}`} accept=".pdf,.jpg,.jpeg,.png" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) setFfQuote(idx, 'documentUrl', file.name); }} />
                  {q.documentUrl ? (<><div style={{ fontSize: 24, color: 'var(--g)', marginBottom: 6 }}>✓</div><div style={{ fontWeight: 600, color: 'var(--g)', fontSize: 13.5 }}>{q.documentUrl} uploaded</div><div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 4 }}>Tap to replace</div></>) : (<><div style={{ fontSize: 24, color: 'var(--ink3)', marginBottom: 6 }}>⬆</div><div style={{ fontSize: 13, color: 'var(--ink2)', fontWeight: 500 }}>Upload quotation document</div><div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 3 }}>PDF, JPG or PNG · optional</div></>)}
                </div>
                <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 10 }}>
                  <Field label="Vendor name *"><input value={q.vendorName} onChange={(e) => setFfQuote(idx, 'vendorName', e.target.value)} placeholder="Vendor / supplier name" /></Field>
                  <Field label="Quoted amount (₹) *"><input type="number" value={q.amount} onChange={(e) => setFfQuote(idx, 'amount', e.target.value)} placeholder="Total quote amount" /></Field>
                  <Field label="Delivery timeline"><input value={q.deliveryTimeline} onChange={(e) => setFfQuote(idx, 'deliveryTimeline', e.target.value)} placeholder="e.g. 2–3 weeks" /></Field>
                </div>
                <Field label="Notes"><input value={q.notes} onChange={(e) => setFfQuote(idx, 'notes', e.target.value)} placeholder="Includes installation, GST, warranty…" /></Field>
              </div>
            </div>
          ))}
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
            <button className="ck-btn ck-btn-ghost" onClick={() => setFfStep(1)}>← Back</button>
            <button className="ck-btn ck-btn-or" onClick={() => setFfStep(3)}>Next — review & submit →</button>
          </div>
        </div>
      )}

      {ffStep === 3 && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 280px', gap: 18 }}>
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
                {ffForm.quotations.some(q => q.vendorName) && (
                  <>
                    <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', marginBottom: 10 }}>Quotation comparison</div>
                    <div className="ck-alert ck-alert-g" style={{ marginBottom: 12 }}><span>✦</span><div>Custoking quote is auto-recommended when it meets quantity, deadline, budget and GST criteria.</div></div>
                    <table className="ck-table" style={{ marginBottom: 0 }}>
                      <thead><tr><th>Vendor</th><th>Amount</th><th>Delivery</th><th>Notes</th></tr></thead>
                      <tbody>
                        {ffForm.quotations.filter(q => q.vendorName).map((q, i) => (
                          <tr key={i} style={q.vendorName.toLowerCase().includes('custoking') ? { background: '#f0faf4' } : {}}>
                            <td style={{ fontWeight: 600 }}>{q.vendorName}{q.vendorName.toLowerCase().includes('custoking') && <span style={{ fontSize: 10, fontWeight: 700, background: 'var(--g)', color: '#fff', padding: '1px 7px', borderRadius: 5, marginLeft: 6 }}>✦ Our quote</span>}</td>
                            <td style={{ fontWeight: 700, color: 'var(--g)' }}>{q.amount ? `₹${formatMoney(Number(q.amount))}` : '—'}</td>
                            <td>{q.deliveryTimeline || '—'}</td>
                            <td style={{ fontSize: 12, color: 'var(--ink2)' }}>{q.notes || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
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
                {[[!!ffForm.title, 'Request description filled'], [ffForm.quotations.filter(q => q.vendorName).length >= 2, '2 quotations added (recommended)'], [ffForm.quotations.some(q => q.amount), 'Vendor amounts entered'], [!!(ffForm.urgency), 'Urgency set']].map(([ok, label], i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 16px', borderBottom: i < 3 ? '1px solid var(--border)' : 'none', fontSize: 13 }}>
                    <span style={{ color: ok ? 'var(--g)' : 'var(--ink3)', fontSize: 15 }}>{ok ? '✓' : '○'}</span>
                    <span style={{ color: ok ? 'var(--ink)' : 'var(--ink3)' }}>{label as string}</span>
                  </div>
                ))}
              </div>
            </div>
            {ffError && <div className="ck-alert ck-alert-re"><span>✕</span><div>{ffError}</div></div>}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <button className="ck-btn ck-btn-or" style={{ justifyContent: 'center' }} disabled={ffSaving} onClick={submitFfRequest}>{ffSaving ? 'Submitting…' : 'Submit for approval →'}</button>
              <button className="ck-btn ck-btn-ghost" style={{ justifyContent: 'center' }} onClick={() => setFfStep(2)}>← Back to quotations</button>
            </div>
            <div className="ck-alert ck-alert-g"><span>✓</span><div>Once submitted, Finance Review is notified. Track status in real time.</div></div>
          </div>
        </div>
      )}
    </ModuleShell>

      {toast && (
        <div className="ck-command-toast ok" style={{ position: 'fixed', bottom: 24, right: 24, zIndex: 9999 }}>
          {toast}
        </div>
      )}
    </>
  );
}
