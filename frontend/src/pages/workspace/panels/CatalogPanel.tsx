import React, { useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { OrderSummaryPanel, thStyle, tdStyle, inlineInputStyle } from '../ui';
import { formatMoney, toPaise } from '../utils';
import { CATALOG_TILES } from '../config';
import type { PanelKey } from '../config';

interface Props {
  setPanel: (key: PanelKey) => void;
}

export function CatalogPanel({ setPanel }: Props) {
  const { user } = useAuth();
  const schoolScopedParams = user?.role !== 'SUPERADMIN' && user?.branchId ? { schoolId: user.branchId } : undefined;

  const [activeCat, setActiveCat] = useState<string | null>(null);
  const [catalogSearch, setCatalogSearch] = useState('');
  const [catalogSaving, setCatalogSaving] = useState(false);
  const [catalogNotice, setCatalogNotice] = useState<{ type: 'success' | 'error' | 'draft'; msg: string } | null>(null);

  const [uniformForm, setUniformForm] = useState({ academicYear: '2024–25', requiredByDate: '', classGroup: 'Class 6–8', logoOnUniform: 'Yes — school logo embroidered', specialInstructions: '', items: [{ name: 'Shirt (white)', sizeBreakdown: 'S:20 M:60 L:15 XL:5', qty: 100, unitPrice: 320 }, { name: 'Trousers / skirt', sizeBreakdown: 'S:20 M:60 L:15 XL:5', qty: 100, unitPrice: 480 }, { name: 'PE T-shirt', sizeBreakdown: 'S:30 M:50 L:20', qty: 100, unitPrice: 220 }] });
  const [notebookForm, setNotebookForm] = useState({ numStudents: 487, notebooksPerStudent: 6, requiredByDate: '', coverLogo: 'School logo — printed', delivery: 'Deliver to school', schoolNameOnSpine: 'Yes', items: [{ type: 'Ruled notebook', size: 'A4', pages: '120', qty: 1200, unitPrice: 45 }, { type: 'School diary', size: 'A5', pages: '200 pg', qty: 487, unitPrice: 80 }, { type: 'Graph notebook', size: 'A4', pages: '60 pg', qty: 300, unitPrice: 28 }] });
  const [stationeryForm, setStationeryForm] = useState({ packType: 'Per-student kit', numKits: 487, requiredByDate: '', items: [{ name: 'Ball pen (blue)', brand: 'Reynolds 045', perKit: 2, unitPrice: 6 }, { name: 'Pencil HB', brand: 'Natraj 621', perKit: 2, unitPrice: 5 }, { name: 'Eraser', brand: 'Apsara Non-dust', perKit: 1, unitPrice: 8 }, { name: 'Scale 30cm', brand: 'Camlin', perKit: 1, unitPrice: 12 }] });
  const [idCardForm, setIdCardForm] = useState({ studentCount: 487, staffCount: 68, spareCards: 20, lanyardIncluded: 'Yes — with hook', requiredByDate: '' });
  const [housekeepingForm, setHousekeepingForm] = useState({ contractType: 'Weekly — 3 days', startDate: '', duration: '3 months', staffRequired: 4 });
  const [eventsForm, setEventsForm] = useState({ eventName: '', eventDate: '', deliveryDeadline: '', items: [{ name: 'Trophy — gold', spec: '6 inch, resin base', qty: 20, unitPrice: 480 }, { name: 'Certificate', spec: 'A4, GSM 150, colour', qty: 200, unitPrice: 35 }, { name: 'Stage backdrop', spec: '12×8 ft, flex print', qty: 1, unitPrice: 4800 }] });
  const [healthForm, setHealthForm] = useState({ requiredByDate: '', deliveryTo: 'Main office', items: [{ name: 'First aid kit (standard)', qty: 6, unitPrice: 850 }, { name: 'Hand sanitizer 500ml', qty: 24, unitPrice: 180 }, { name: 'Disposable gloves (box 100)', qty: 10, unitPrice: 250 }] });

  const calcUniform = () => { const subtotalRs = uniformForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.05); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcNotebook = () => { const subtotalRs = notebookForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcStationery = () => { const kitCost = stationeryForm.items.reduce((s, r) => s + r.perKit * r.unitPrice, 0); const subtotalRs = kitCost * stationeryForm.numKits; const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcIdCard = () => { const total = idCardForm.studentCount + idCardForm.staffCount + idCardForm.spareCards; const cardCost = total * 30; const lanyardCost = idCardForm.lanyardIncluded.startsWith('Yes') ? total * 8 : 0; const subtotalRs = cardCost + lanyardCost; const gstRs = Math.round(subtotalRs * 0.18); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs, total }; };
  const calcHousekeeping = () => { const months = ({ '1 month': 1, '3 months': 3, '6 months': 6, 'Academic year': 10 } as Record<string, number>)[housekeepingForm.duration] ?? 3; const subtotalRs = housekeepingForm.staffRequired * months * 9000; return { subtotalRs, gstRs: 0, totalRs: subtotalRs, months }; };
  const calcEvents = () => { const subtotalRs = eventsForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs, gstRs: 0, totalRs: subtotalRs }; };
  const calcHealth = () => { const totalRs = healthForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs: totalRs, gstRs: 0, totalRs }; };

  const addUniformItem = () => setUniformForm((f) => ({ ...f, items: [...f.items, { name: '', sizeBreakdown: '', qty: 0, unitPrice: 0 }] }));
  const addNotebookType = () => setNotebookForm((f) => ({ ...f, items: [...f.items, { type: '', size: 'A4', pages: '120', qty: 0, unitPrice: 0 }] }));
  const addStationeryItem = () => setStationeryForm((f) => ({ ...f, items: [...f.items, { name: '', brand: '', perKit: 1, unitPrice: 0 }] }));
  const addEventItem = () => setEventsForm((f) => ({ ...f, items: [...f.items, { name: '', spec: '', qty: 0, unitPrice: 0 }] }));

  const uniformSummaryLines = () => [
    ...uniformForm.items.filter((r) => (r.name || '').trim() || r.qty > 0 || r.unitPrice > 0).map((r) => ({ label: `${r.name || 'Item'} × ${r.qty || 0}`, value: (r.qty || 0) * (r.unitPrice || 0) })),
    { label: 'GST 5%', value: calcUniform().gstRs },
  ];

  const getCalcs = (cat: string) => {
    if (cat === 'UNIFORMS') return calcUniform();
    if (cat === 'NOTEBOOKS') return calcNotebook();
    if (cat === 'STATIONERY') return calcStationery();
    if (cat === 'IDCARDS') return calcIdCard();
    if (cat === 'HOUSEKEEPING') return calcHousekeeping();
    if (cat === 'EVENTS') return calcEvents();
    return calcHealth();
  };

  const getFormData = (cat: string) => {
    if (cat === 'UNIFORMS') return uniformForm;
    if (cat === 'NOTEBOOKS') return notebookForm;
    if (cat === 'STATIONERY') return stationeryForm;
    if (cat === 'IDCARDS') return idCardForm;
    if (cat === 'HOUSEKEEPING') return housekeepingForm;
    if (cat === 'EVENTS') return eventsForm;
    return healthForm;
  };

  const getRequiredByDate = (cat: string) => {
    if (cat === 'UNIFORMS') return uniformForm.requiredByDate;
    if (cat === 'NOTEBOOKS') return notebookForm.requiredByDate;
    if (cat === 'STATIONERY') return stationeryForm.requiredByDate;
    if (cat === 'IDCARDS') return idCardForm.requiredByDate;
    if (cat === 'HOUSEKEEPING') return housekeepingForm.startDate;
    if (cat === 'EVENTS') return eventsForm.deliveryDeadline;
    return healthForm.requiredByDate;
  };

  const getSummaryLines = (cat: string) => {
    if (cat === 'UNIFORMS') return uniformSummaryLines();
    if (cat === 'NOTEBOOKS') return [...notebookForm.items.map((r) => ({ label: `${r.type || 'Notebook'} · ${r.qty} units`, value: r.qty * r.unitPrice })), { label: 'GST 12%', value: calcNotebook().gstRs }];
    if (cat === 'STATIONERY') return [{ label: 'Kit cost per student', value: stationeryForm.items.reduce((s, r) => s + r.perKit * r.unitPrice, 0) }, { label: `${stationeryForm.numKits} kits`, value: calcStationery().subtotalRs }, { label: 'GST 12%', value: calcStationery().gstRs }];
    if (cat === 'IDCARDS') return [{ label: `${calcIdCard().total} cards`, value: calcIdCard().subtotalRs }, { label: 'GST', value: calcIdCard().gstRs }];
    if (cat === 'HOUSEKEEPING') return [{ label: `${housekeepingForm.staffRequired} staff`, value: calcHousekeeping().subtotalRs }];
    if (cat === 'EVENTS') return eventsForm.items.map((r) => ({ label: `${r.name || 'Item'} × ${r.qty}`, value: r.qty * r.unitPrice }));
    return healthForm.items.map((r) => ({ label: `${r.name} × ${r.qty}`, value: r.qty * r.unitPrice }));
  };

  const getDelivery = (cat: string) => {
    if (cat === 'UNIFORMS') return '3–4 weeks';
    if (cat === 'NOTEBOOKS') return '1–2 weeks';
    if (cat === 'STATIONERY') return '5–7 days';
    if (cat === 'HOUSEKEEPING') return 'Service contract';
    if (cat === 'EVENTS') return 'Ready as planned';
    return '3–10 days';
  };

  const submitCatalogOrder = async (category: string, place: boolean) => {
    setCatalogSaving(true);
    setCatalogNotice(null);
    try {
      const calcs = getCalcs(category);
      const formData = getFormData(category);
      const requiredByDate = getRequiredByDate(category);
      const res = await api.post('/supply/orders', {
        category,
        orderData: JSON.stringify({ ...formData, title: category }),
        subtotal: toPaise(calcs.subtotalRs),
        gst: toPaise(calcs.gstRs),
        totalAmount: toPaise(calcs.totalRs),
        requiredByDate: requiredByDate || null,
        status: 'DRAFT',
        ...(schoolScopedParams || {}),
      });
      const orderId: string = res.data.id;
      if (place) {
        await api.post(`/supply/orders/${orderId}/place`);
        const msg = category === 'UNIFORMS' || category === 'NOTEBOOKS'
          ? `Order placed. It has moved to Design approval. After design approval it will go to superadmin for final approval.`
          : category === 'STATIONERY' || category === 'EVENTS'
            ? 'Order placed. It is now in processing and pending superadmin approval.'
            : 'Order placed! You will receive a confirmation within 2 hours. Track it under My Orders.';
        setCatalogNotice({ type: 'success', msg });
        setActiveCat(null);
        setPanel('orders');
      } else {
        setCatalogNotice({ type: 'draft', msg: 'Draft saved.' });
        window.setTimeout(() => setCatalogNotice(null), 3000);
      }
    } catch (err: unknown) {
      setCatalogNotice({ type: 'error', msg: (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Failed to save order.') });
    } finally {
      setCatalogSaving(false);
    }
  };

  return (
    <div className="ck-content">
      <div className="ck-ph">
        <div>
          <h1 className="ck-page-title">Catalog</h1>
          <p className="ck-page-sub">Order directly from Custoking — uniforms, stationery, ID cards, services and more</p>
        </div>
        <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('orders')}>View my orders →</button>
      </div>

      {catalogNotice && (
        <div className={`ck-alert ${catalogNotice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}>
          <span>{catalogNotice.type === 'error' ? '✕' : catalogNotice.type === 'draft' ? '✦' : '✓'}</span>
          <div>{catalogNotice.msg}</div>
        </div>
      )}

      {activeCat === null ? (
        <>
          <div style={{ position: 'relative', marginBottom: 22 }}>
            <input placeholder="Search catalog — uniforms, notebooks, ID cards…" value={catalogSearch} onChange={(e) => setCatalogSearch(e.target.value)} style={{ width: '100%', border: '1px solid var(--border2)', borderRadius: 20, padding: '10px 20px 10px 44px', fontSize: 13.5, fontFamily: 'DM Sans, sans-serif', outline: 'none', background: 'var(--white)' }} />
            <span style={{ position: 'absolute', left: 16, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink3)', fontSize: 15 }}>🔍</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 14, marginBottom: 28 }}>
            {CATALOG_TILES.filter((c) => !catalogSearch || `${c.name} ${c.desc}`.toLowerCase().includes(catalogSearch.toLowerCase())).map((c) => (
              <div key={c.key} onClick={() => { setActiveCat(c.key); setCatalogNotice(null); }} style={{ background: 'var(--white)', border: '1px solid var(--border)', borderRadius: 'var(--r)', overflow: 'hidden', cursor: 'pointer' }}>
                <div style={{ height: 90, background: c.headerBg, position: 'relative', overflow: 'hidden' }}>
                  <img src={`https://source.unsplash.com/featured/400x200/?${c.imgQ}`} alt={c.name} loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block', position: 'absolute', inset: 0 }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                  <span style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 38 }}>{c.emoji}</span>
                </div>
                <div style={{ padding: '13px 14px 14px' }}>
                  <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 3 }}>{c.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--ink3)', marginBottom: 10 }}>{c.desc}</div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span className={`pill ${c.pillClass}`}>{c.pill}</span>
                    <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--g)' }}>Order →</span>
                  </div>
                </div>
              </div>
            ))}
            <div onClick={() => setPanel('ff-new')} style={{ background: 'var(--or1)', border: '1.5px dashed var(--or2)', borderRadius: 'var(--r)', padding: 14, cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', textAlign: 'center', minHeight: 175 }}>
              <div style={{ fontSize: 30, marginBottom: 8 }}>🔥</div>
              <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--or)', marginBottom: 4 }}>Don't see what you need?</div>
              <div style={{ fontSize: 12, color: 'var(--or)', opacity: .8 }}>Use Firefighting to raise a custom request with quotations</div>
            </div>
          </div>
        </>
      ) : (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 22 }}>
            <button className="ck-btn ck-btn-ghost" style={{ fontSize: 12 }} onClick={() => { setActiveCat(null); setCatalogNotice(null); }}>← Back to catalog</button>
            <span style={{ fontSize: 13, color: 'var(--ink3)' }}>Catalog / {activeCat}</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 280px', gap: 18 }}>
            <div>
              <div className="ck-form-card">
                {['IDCARDS', 'HOUSEKEEPING', 'HEALTH'].includes(activeCat || '') && (
                  <div className="ck-form-head">
                    {activeCat === 'IDCARDS' ? '🪪 ID card order' : activeCat === 'HOUSEKEEPING' ? '🧹 Housekeeping service' : '🩺 Health & safety order'}
                  </div>
                )}
                <div className="ck-form-body">
                  {activeCat === 'UNIFORMS' && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                        <div style={{ fontSize: 20 }}>👕</div>
                        <div><div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Uniform order</div><div className="ts">School uniforms, PE kit, blazers, ties — with school branding</div></div>
                      </div>
                      <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                        <div className="ck-form-head">Order details</div>
                        <div className="ck-form-body">
                          <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 18 }}>
                            <div className="field"><label>Academic year</label><select value={uniformForm.academicYear} onChange={(e) => setUniformForm((f) => ({ ...f, academicYear: e.target.value }))}><option>2024–25</option><option>2025–26</option></select></div>
                            <div className="field"><label>Required by date *</label><input type="date" value={uniformForm.requiredByDate} onChange={(e) => setUniformForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                            <div className="field"><label>Class group</label><select value={uniformForm.classGroup} onChange={(e) => setUniformForm((f) => ({ ...f, classGroup: e.target.value }))}><option>Class 1–5</option><option>Class 6–8</option><option>Class 9–10</option><option>Class 11–12</option><option>All classes</option></select></div>
                            <div className="field"><label>Logo on uniform</label><select value={uniformForm.logoOnUniform} onChange={(e) => setUniformForm((f) => ({ ...f, logoOnUniform: e.target.value }))}><option>Yes — school logo embroidered</option><option>Yes — printed logo</option><option>No logo</option></select></div>
                          </div>
                          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Items & quantities</div>
                          <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                            <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Size breakdown</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                            <tbody>{uniformForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item name" /></td><td style={tdStyle}><input value={row.sizeBreakdown || ''} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], sizeBreakdown: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="S:20 M:60 L:15 XL:5" /></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 90 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 100 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                          </table>
                          <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addUniformItem}>+ Add item</button></div>
                          <div className="field" style={{ marginTop: 18 }}><label>Special instructions</label><input value={uniformForm.specialInstructions} onChange={(e) => setUniformForm((f) => ({ ...f, specialInstructions: e.target.value }))} placeholder="e.g. school name on collar, colour spec, packaging..." /></div>
                        </div>
                      </div>
                    </>
                  )}
                  {activeCat === 'NOTEBOOKS' && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                        <div style={{ fontSize: 20 }}>📓</div>
                        <div><div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Notebooks order</div><div className="ts">Ruled, plain, graph, school diary — with school logo cover</div></div>
                      </div>
                      <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                        <div className="ck-form-head">Order details</div>
                        <div className="ck-form-body">
                          <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 18 }}>
                            <div className="field"><label>No. of students *</label><input type="number" value={notebookForm.numStudents} onChange={(e) => setNotebookForm((f) => ({ ...f, numStudents: +e.target.value || 0 }))} /></div>
                            <div className="field"><label>Notebooks per student</label><input type="number" value={notebookForm.notebooksPerStudent} onChange={(e) => setNotebookForm((f) => ({ ...f, notebooksPerStudent: +e.target.value || 0 }))} /></div>
                            <div className="field"><label>Required by</label><input type="date" value={notebookForm.requiredByDate} onChange={(e) => setNotebookForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                            <div className="field"><label>Cover logo</label><select value={notebookForm.coverLogo} onChange={(e) => setNotebookForm((f) => ({ ...f, coverLogo: e.target.value }))}><option>School logo — printed</option><option>School logo — embossed</option><option>No logo</option></select></div>
                            <div className="field"><label>Delivery</label><select value={notebookForm.delivery} onChange={(e) => setNotebookForm((f) => ({ ...f, delivery: e.target.value }))}><option>Deliver to school</option><option>Warehouse pickup</option></select></div>
                            <div className="field"><label>School name on spine</label><select value={notebookForm.schoolNameOnSpine} onChange={(e) => setNotebookForm((f) => ({ ...f, schoolNameOnSpine: e.target.value }))}><option>Yes</option><option>No</option></select></div>
                          </div>
                          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Notebook types</div>
                          <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                            <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Type</th><th style={thStyle}>Size</th><th style={thStyle}>Pages</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                            <tbody>{notebookForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.type} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], type: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 160 }} placeholder="Notebook type" /></td><td style={tdStyle}><select value={row.size} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], size: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 84 }}><option>A4</option><option>A5</option><option>Long</option></select></td><td style={tdStyle}><select value={row.pages} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], pages: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 92 }}><option>60 pg</option><option>80 pg</option><option>120</option><option>160</option><option>200 pg</option></select></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                          </table>
                          <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addNotebookType}>+ Add type</button></div>
                        </div>
                      </div>
                    </>
                  )}
                  {activeCat === 'STATIONERY' && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                        <div style={{ fontSize: 20 }}>🖊</div>
                        <div><div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Stationery order</div><div className="ts">Per-student kit or bulk supply — pens, pencils, rulers, craft supplies</div></div>
                      </div>
                      <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                        <div className="ck-form-head">Order details</div>
                        <div className="ck-form-body">
                          <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 18 }}>
                            <div className="field"><label>Pack type</label><select value={stationeryForm.packType} onChange={(e) => setStationeryForm((f) => ({ ...f, packType: e.target.value }))}><option>Per-student kit</option><option>Bulk classroom supply</option></select></div>
                            <div className="field"><label>Number of kits *</label><input type="number" value={stationeryForm.numKits} onChange={(e) => setStationeryForm((f) => ({ ...f, numKits: +e.target.value || 0 }))} /></div>
                            <div className="field"><label>Required by</label><input type="date" value={stationeryForm.requiredByDate} onChange={(e) => setStationeryForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                          </div>
                          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Kit contents</div>
                          <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                            <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Brand / spec</th><th style={thStyle}>Per kit</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Kit sub</th></tr></thead>
                            <tbody>{stationeryForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item" /></td><td style={tdStyle}><input value={row.brand} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], brand: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Brand / spec" /></td><td style={tdStyle}><input type="number" value={row.perKit} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], perKit: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 84 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 90 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.perKit || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                          </table>
                          <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addStationeryItem}>+ Add item</button></div>
                        </div>
                      </div>
                    </>
                  )}
                  {activeCat === 'IDCARDS' && (
                    <div className="ck-form-grid ck-fg-3">
                      <div className="field"><label>Student count</label><input type="number" value={idCardForm.studentCount} onChange={(e) => setIdCardForm((f) => ({ ...f, studentCount: +e.target.value || 0 }))} /></div>
                      <div className="field"><label>Staff count</label><input type="number" value={idCardForm.staffCount} onChange={(e) => setIdCardForm((f) => ({ ...f, staffCount: +e.target.value || 0 }))} /></div>
                      <div className="field"><label>Spare cards</label><input type="number" value={idCardForm.spareCards} onChange={(e) => setIdCardForm((f) => ({ ...f, spareCards: +e.target.value || 0 }))} /></div>
                      <div className="field"><label>Lanyard included</label><select value={idCardForm.lanyardIncluded} onChange={(e) => setIdCardForm((f) => ({ ...f, lanyardIncluded: e.target.value }))}><option>Yes — with hook</option><option>No</option></select></div>
                      <div className="field"><label>Required by</label><input type="date" value={idCardForm.requiredByDate} onChange={(e) => setIdCardForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                    </div>
                  )}
                  {activeCat === 'HOUSEKEEPING' && (
                    <div className="ck-form-grid ck-fg-2">
                      <div className="field"><label>Duration</label><select value={housekeepingForm.duration} onChange={(e) => setHousekeepingForm((f) => ({ ...f, duration: e.target.value }))}><option>1 month</option><option>3 months</option><option>6 months</option><option>Academic year</option></select></div>
                      <div className="field"><label>Staff required</label><input type="number" value={housekeepingForm.staffRequired} onChange={(e) => setHousekeepingForm((f) => ({ ...f, staffRequired: +e.target.value || 0 }))} /></div>
                      <div className="field"><label>Start date</label><input type="date" value={housekeepingForm.startDate} onChange={(e) => setHousekeepingForm((f) => ({ ...f, startDate: e.target.value }))} /></div>
                    </div>
                  )}
                  {activeCat === 'EVENTS' && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                        <div style={{ fontSize: 20 }}>🏆</div>
                        <div><div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Events &amp; print</div><div className="ts">Trophies, certificates, banners, backdrops for school events</div></div>
                      </div>
                      <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                        <div className="ck-form-head">Event details</div>
                        <div className="ck-form-body">
                          <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 18 }}>
                            <div className="field"><label>Event name</label><input value={eventsForm.eventName} onChange={(e) => setEventsForm((f) => ({ ...f, eventName: e.target.value }))} placeholder="e.g. Annual Day 2025" /></div>
                            <div className="field"><label>Event date</label><input type="date" value={eventsForm.eventDate} onChange={(e) => setEventsForm((f) => ({ ...f, eventDate: e.target.value }))} /></div>
                            <div className="field"><label>Delivery deadline</label><input type="date" value={eventsForm.deliveryDeadline} onChange={(e) => setEventsForm((f) => ({ ...f, deliveryDeadline: e.target.value }))} /></div>
                          </div>
                          <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                            <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Spec</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                            <tbody>{eventsForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item" /></td><td style={tdStyle}><input value={row.spec} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], spec: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 220 }} placeholder="Specification" /></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 88 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                          </table>
                          <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addEventItem}>+ Add item</button></div>
                          <div style={{ marginTop: 18, background: 'var(--bg)', borderRadius: 12, padding: '16px 18px', display: 'flex', justifyContent: 'space-between', fontSize: 16 }}><span>Event total</span><strong style={{ color: 'var(--g)' }}>₹{formatMoney(calcEvents().totalRs)}</strong></div>
                        </div>
                      </div>
                    </>
                  )}
                  {activeCat === 'HEALTH' && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                        <div style={{ fontSize: 20 }}>🩺</div>
                        <div><div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Health &amp; safety</div><div className="ts">First aid, sanitizers, fire equipment, PPE</div></div>
                      </div>
                      <div className="field" style={{ marginBottom: 12 }}><label>Required by date</label><input type="date" value={healthForm.requiredByDate} onChange={(e) => setHealthForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                      <table style={{ width: '100%', borderCollapse: 'collapse', border: '1px solid var(--border)' }}>
                        <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th></tr></thead>
                        <tbody>{healthForm.items.map((row, i) => <tr key={i}><td style={tdStyle}>{row.name}</td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setHealthForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 80 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setHealthForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 80 }} /></td></tr>)}</tbody>
                      </table>
                    </>
                  )}
                </div>
              </div>
            </div>
            <OrderSummaryPanel
              accentVar="var(--g)"
              borderVar="var(--g2)"
              lines={getSummaryLines(activeCat || '')}
              total={getCalcs(activeCat || '').totalRs}
              delivery={getDelivery(activeCat || '')}
              saving={catalogSaving}
              onPlace={() => submitCatalogOrder(activeCat || '', true)}
              onDraft={() => submitCatalogOrder(activeCat || '', false)}
            />
          </div>
        </div>
      )}
    </div>
  );
}
