import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { formatMoney, computeSaOrderValue, EVENT_RATES } from '../utils';
import { SA_NEW_ORDER_CATEGORIES } from '../config';

interface Props {
  onOrderCreated: () => void;
}

export function SaNewOrderPanel({ onOrderCreated }: Props) {
  const [activeCat, setActiveCat] = useState<string | null>(null);
  const [form, setForm] = useState<any>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState('');
  const [eventItems, setEventItems] = useState<Array<{ type: string; qty: string; notes: string }>>([]);
  const [schoolOptions, setSchoolOptions] = useState<any[]>([]);
  const [schoolLoadError, setSchoolLoadError] = useState('');

  useEffect(() => {
    api.get('/sa/schools')
      .then((res) => setSchoolOptions(Array.isArray(res.data) ? res.data : []))
      .catch(() => { setSchoolOptions([]); setSchoolLoadError('Failed to load school list. Please refresh the page.'); });
  }, []);

  const categoryMeta = SA_NEW_ORDER_CATEGORIES.find((item) => item.key === activeCat) || null;

  const submit = async () => {
    const errs: Record<string, string> = {};
    if (!form.schoolId) errs.school = 'School is required';
    if (activeCat === 'CUSTOM' && !form.description) errs.description = 'Description is required';
    if (activeCat === 'UNIFORMS' && !form.academicYear) errs.academicYear = 'Academic year is required';
    if (activeCat === 'NOTEBOOKS' && !form.deliveryDate) errs.deliveryDate = 'Delivery date is required';
    if (activeCat === 'IDCARDS' && !form.cardType) errs.cardType = 'Card type is required';
    if (activeCat === 'STATIONERY' && !form.kitQty) errs.kitQty = 'Quantity is required';
    if (activeCat === 'HOUSEKEEPING') {
      if (!form.startDate) errs.startDate = 'Start date is required';
      if (!form.duration) errs.duration = 'Duration is required';
    }
    if (activeCat === 'EVENTS' && !form.deliveryDate) errs.deliveryDate = 'Delivery date is required';
    if (Object.keys(errs).length) { setErrors(errs); return; }

    setErrors({}); setSaving(true);
    try {
      const value = computeSaOrderValue(activeCat || 'CUSTOM', form, eventItems);
      const res = await api.post('/sa/orders', {
        schoolId: form.schoolId,
        category: activeCat,
        orderData: JSON.stringify({ ...form, eventItems, title: activeCat }),
        subtotal: value * 100,
        gst: 0,
        totalAmount: value * 100,
        requiredByDate: form.deliveryDate || form.startDate || form.requiredByDate || null,
        notes: form.notes || '',
      });
      setNotice(`Order ${res.data?.id} created. Now visible in All Orders.`);
      setActiveCat(null); setForm({}); setEventItems([]);
      window.setTimeout(() => { setNotice(''); onOrderCreated(); }, 1200);
    } catch (e: any) {
      setErrors({ _api: e?.response?.data?.message || 'Failed to create order.' });
    } finally {
      setSaving(false);
    }
  };

  return (
    <ModuleShell title="New order request" subtitle="Select a category — each has a tailored intake form.">
      <div className="sa-order-request">
        {notice ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{notice}</div></div> : null}

        {!activeCat ? (
          <>
            <div className="sa-category-label">Select supply category</div>
            <div className="sa-category-grid">
              {SA_NEW_ORDER_CATEGORIES.map((item, idx) => (
                <button key={`${item.title}-${idx}`} className="sa-category-card" onClick={() => { setActiveCat(item.key); setErrors({}); }}>
                  <div className="sa-category-icon" aria-hidden="true">{item.icon}</div>
                  <div className="sa-category-title">{item.title}</div>
                  <div className="sa-category-desc">{item.desc}</div>
                </button>
              ))}
            </div>
          </>
        ) : (
          <div className="sa-order-form-card">
            <div className="sa-order-form-head">
              <button className="sa-order-back" onClick={() => { setActiveCat(null); setErrors({}); }}>← Change</button>
              {categoryMeta ? (
                <div className="sa-order-selected">
                  <div className="sa-order-selected-icon">{categoryMeta.icon}</div>
                  <div>
                    <div className="sa-order-selected-title">{categoryMeta.title}</div>
                    <div className="sa-order-selected-desc">{categoryMeta.desc}</div>
                  </div>
                </div>
              ) : null}
            </div>

            {errors._api ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{errors._api}</div></div> : null}

            <div className="ck-form-grid ck-fg-2">
              <Field label="School *" error={errors.school}>
                <select value={form.schoolId || ''} onChange={(e) => setForm({ ...form, schoolId: Number(e.target.value) || '' })}>
                  <option value="">Select school</option>
                  {schoolOptions.map((s: any) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
                {schoolLoadError && <div style={{ fontSize: 12, color: 'var(--re)', marginTop: 4 }}>{schoolLoadError}</div>}
              </Field>
              <Field label="Notes">
                <input value={form.notes || ''} onChange={(e) => setForm({ ...form, notes: e.target.value })} placeholder="Optional notes" />
              </Field>

              {activeCat === 'UNIFORMS' && (
                <>
                  <Field label="Academic year *" error={errors.academicYear}>
                    <input value={form.academicYear || ''} onChange={(e) => setForm({ ...form, academicYear: e.target.value })} placeholder="2025-26" />
                  </Field>
                  <Field label="Total units">
                    <input type="number" min="0" value={form.size_m || ''} onChange={(e) => setForm({ ...form, size_m: e.target.value })} placeholder="Enter total units" />
                  </Field>
                </>
              )}
              {activeCat === 'NOTEBOOKS' && (
                <>
                  <Field label="Academic year">
                    <input value={form.academicYear || ''} onChange={(e) => setForm({ ...form, academicYear: e.target.value })} placeholder="2025-26" />
                  </Field>
                  <Field label="Delivery date *" error={errors.deliveryDate}>
                    <input type="date" value={form.deliveryDate || ''} onChange={(e) => setForm({ ...form, deliveryDate: e.target.value })} />
                  </Field>
                  <Field label="Notebook qty">
                    <input type="number" min="0" value={form.notebookQty || ''} onChange={(e) => setForm({ ...form, notebookQty: e.target.value, notebookRows: [{ qty: e.target.value }] })} placeholder="Enter notebook count" />
                  </Field>
                </>
              )}
              {activeCat === 'IDCARDS' && (
                <>
                  <Field label="Card type *" error={errors.cardType}>
                    <input value={form.cardType || ''} onChange={(e) => setForm({ ...form, cardType: e.target.value })} placeholder="Student / Staff / Dual" />
                  </Field>
                  <Field label="Delivery date *" error={errors.deliveryDate}>
                    <input type="date" value={form.deliveryDate || ''} onChange={(e) => setForm({ ...form, deliveryDate: e.target.value })} />
                  </Field>
                  <Field label="Student count">
                    <input type="number" min="0" value={form.studentCount || ''} onChange={(e) => setForm({ ...form, studentCount: e.target.value })} />
                  </Field>
                  <Field label="Staff count">
                    <input type="number" min="0" value={form.staffCount || ''} onChange={(e) => setForm({ ...form, staffCount: e.target.value })} />
                  </Field>
                </>
              )}
              {activeCat === 'STATIONERY' && (
                <Field label="Kit qty *" error={errors.kitQty}>
                  <input type="number" min="0" value={form.kitQty || ''} onChange={(e) => setForm({ ...form, kitQty: e.target.value })} placeholder="Enter kit quantity" />
                </Field>
              )}
              {activeCat === 'HOUSEKEEPING' && (
                <>
                  <Field label="Start date *" error={errors.startDate}>
                    <input type="date" value={form.startDate || ''} onChange={(e) => setForm({ ...form, startDate: e.target.value })} />
                  </Field>
                  <Field label="Duration months *" error={errors.duration}>
                    <input type="number" min="1" value={form.duration || ''} onChange={(e) => setForm({ ...form, duration: e.target.value })} placeholder="12" />
                  </Field>
                  <Field label="Monthly rate">
                    <input type="number" min="0" step="0.01" value={form.monthlyRate || ''} onChange={(e) => setForm({ ...form, monthlyRate: e.target.value })} placeholder="Enter monthly rate" />
                  </Field>
                </>
              )}
              {activeCat === 'EVENTS' && (
                <>
                  <Field label="Delivery date *" error={errors.deliveryDate}>
                    <input type="date" value={form.deliveryDate || ''} onChange={(e) => setForm({ ...form, deliveryDate: e.target.value })} />
                  </Field>
                  <Field label="Item type">
                    <select value={eventItems[0]?.type || ''} onChange={(e) => setEventItems([{ ...(eventItems[0] || { qty: '', notes: '' }), type: e.target.value }])}>
                      <option value="">Select</option>
                      {Object.keys(EVENT_RATES).map((k) => <option key={k} value={k}>{k}</option>)}
                    </select>
                  </Field>
                  <Field label="Qty">
                    <input type="number" min="0" value={eventItems[0]?.qty || ''} onChange={(e) => setEventItems([{ ...(eventItems[0] || { type: '', notes: '' }), qty: e.target.value }])} />
                  </Field>
                </>
              )}
              {activeCat === 'CUSTOM' && (
                <>
                  <Field label="Description *" error={errors.description}>
                    <textarea value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Describe the requirement" />
                  </Field>
                  <Field label="Budget">
                    <input type="number" min="0" step="0.01" value={form.budget || ''} onChange={(e) => setForm({ ...form, budget: e.target.value })} placeholder="Enter expected budget" />
                  </Field>
                </>
              )}

              <div className="sa-order-summary">
                <span>Estimated value</span>
                <strong>₹{formatMoney(computeSaOrderValue(activeCat, form, eventItems))}</strong>
              </div>
            </div>

            <div className="ck-actions-inline" style={{ marginTop: 16 }}>
              <button className="ck-btn ck-btn-ghost" disabled title="Coming soon">Save as draft</button>
              <button className="ck-btn ck-btn-g" disabled={saving} onClick={submit}>{saving ? 'Creating…' : 'Create order →'}</button>
            </div>
          </div>
        )}
      </div>
    </ModuleShell>
  );
}
