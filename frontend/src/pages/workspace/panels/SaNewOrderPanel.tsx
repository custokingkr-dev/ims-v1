import { useEffect, useMemo, useState } from 'react';
import { ArrowLeft } from 'lucide-react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { currentFinancialYearLabel, formatMoney, computeSaOrderValue, EVENT_RATES } from '../utils';
import { SA_NEW_ORDER_CATEGORIES } from '../config';
import { ProductFormBuilder } from '../../../features/catalog/ProductFormBuilder';
import { CategoryIcon } from '../../../features/catalog/categoryIcons';
import { useProductCategories, useProductFormDefinition } from '../../../features/catalog/api';

interface Props {
  onOrderCreated: () => void;
}

export function SaNewOrderPanel({ onOrderCreated }: Props) {
  const [activeCat, setActiveCat] = useState<string | null>(null);
  const productCatalog = useProductCategories();
  const activeCategory = productCatalog.categories?.find((category) => category.code === activeCat) || null;
  // Categories whose form is enabled are served by the shared product form, whatever they are.
  const formCategory = activeCategory?.formEnabled ? activeCat : null;
  const formDefinition = useProductFormDefinition(formCategory);
  // When the catalogue cannot be read there is nothing honest to show: the generic fallback tiles
  // on their own look like the whole catalogue, which is how a mid-session 401 reads as
  // "the categories have disappeared". Show the failure and a retry instead.
  const catalogUnavailable = Boolean(productCatalog.error);
  const categoryOptions = catalogUnavailable ? [] : [
    ...(productCatalog.categories || []).map((category) => ({
      key: category.code,
      title: category.label,
      desc: category.description,
      pill: category.orderType,
    })),
    // One "anything else" route. There were two tiles carrying the CUSTOM key, which offered the
    // same destination twice under different names.
    ...SA_NEW_ORDER_CATEGORIES.filter((item) => item.key === 'CUSTOM').slice(-1)
      .map((item) => ({ key: item.key, title: item.title, desc: item.desc, pill: '' })),
  ];
  const [form, setForm] = useState<any>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState('');
  const [eventItems, setEventItems] = useState<Array<{ type: string; qty: string; notes: string }>>([]);
  const [schoolOptions, setSchoolOptions] = useState<any[]>([]);
  const [schoolLoadError, setSchoolLoadError] = useState('');
  const selectedSchool = useMemo(
    () => schoolOptions.find((school) => String(school.id) === String(form.schoolId)),
    [schoolOptions, form.schoolId]
  );
  const currentYearLabel = currentFinancialYearLabel(
    new Date(),
    Number(selectedSchool?.financialYearStartMonth || 4)
  );

  useEffect(() => {
    api.get('/sa/schools')
      .then((res) => setSchoolOptions(Array.isArray(res.data) ? res.data : []))
      .catch(() => { setSchoolOptions([]); setSchoolLoadError('Failed to load school list. Please refresh the page.'); });
  }, []);

  const categoryMeta = categoryOptions.find((item) => item.key === activeCat) || null;

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
              {catalogUnavailable && (
                <div className="ck-alert ck-alert-re" role="alert">
                  <div>
                    <div>The supply catalog could not be loaded, so no categories can be shown.</div>
                    <div className="ts">{productCatalog.error}</div>
                  </div>
                  <button className="ck-btn ck-btn-ghost" onClick={productCatalog.retry}>Retry</button>
                </div>
              )}
              {!catalogUnavailable && !productCatalog.categories
                && Array.from({ length: 8 }, (_, i) => <div key={`skeleton-${i}`} className="sa-category-card is-loading" aria-hidden="true" />)}
              {!catalogUnavailable && !productCatalog.categories && <p className="ck-sr-only" role="status">Loading catalog</p>}
              {categoryOptions.map((item, idx) => (
                <button type="button" key={`${item.key}-${idx}`} className="sa-category-card"
                  onClick={() => { setActiveCat(item.key); setErrors({}); }}>
                  <span className="sa-category-icon"><CategoryIcon code={item.key} /></span>
                  <span className="sa-category-title">{item.title}</span>
                  <span className="sa-category-desc">{item.desc}</span>
                  {item.pill ? <span className="sa-category-pill">{item.pill}</span> : null}
                </button>
              ))}
            </div>
          </>
        ) : (
          <div className="sa-order-form-card">
            <div className="sa-order-form-head">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => { setActiveCat(null); setErrors({}); }}>
                <ArrowLeft size={16} aria-hidden="true" />Change category</button>
              {categoryMeta ? (
                <div className="sa-order-selected">
                  <span className="sa-order-selected-icon"><CategoryIcon code={categoryMeta.key} size={20} /></span>
                  <div>
                    <div className="sa-order-selected-title">{categoryMeta.title}</div>
                    <div className="sa-order-selected-desc">{categoryMeta.desc}</div>
                  </div>
                </div>
              ) : null}
            </div>

            {errors._api ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{errors._api}</div></div> : null}

            {formCategory && formDefinition.loading ? <p role="status">Loading order options...</p>
              : formCategory && formDefinition.error ? <div className="ck-alert ck-alert-re" role="alert">{formDefinition.error}<button className="ck-btn ck-btn-ghost" onClick={formDefinition.retry}>Retry</button></div>
              : formCategory && !formDefinition.definition?.category.formEnabled ? <p role="status">Ordering is currently unavailable for this category.</p>
              : formCategory && formDefinition.definition?.enabled ? <>
                <div className="ck-product-fields"><label className="field"><span>School</span><select value={form.schoolId || ''} onChange={(e) => setForm({ schoolId: Number(e.target.value) || '' })}><option value="">Select school</option>{schoolOptions.map((school) => <option key={school.id} value={school.id}>{school.name}</option>)}</select></label></div>
                {schoolLoadError && <p className="ck-product-error" role="alert">{schoolLoadError}</p>}
                {form.schoolId ? <ProductFormBuilder key={`${formCategory}-${form.schoolId}`} categoryCode={formCategory} schoolId={Number(form.schoolId)} definition={formDefinition.definition} onSaved={(_, placed) => { if (placed) onOrderCreated(); }} /> : <p className="ck-product-muted">Select a school to begin this order.</p>}
              </> : <>
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
                    <input value={form.academicYear || ''} onChange={(e) => setForm({ ...form, academicYear: e.target.value })} placeholder={currentYearLabel} />
                  </Field>
                  <Field label="Total units">
                    <input type="number" min="0" value={form.size_m || ''} onChange={(e) => setForm({ ...form, size_m: e.target.value })} placeholder="Enter total units" />
                  </Field>
                </>
              )}
              {activeCat === 'NOTEBOOKS' && (
                <>
                  <Field label="Academic year">
                    <input value={form.academicYear || ''} onChange={(e) => setForm({ ...form, academicYear: e.target.value })} placeholder={currentYearLabel} />
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
            </>}
          </div>
        )}
      </div>
    </ModuleShell>
  );
}
