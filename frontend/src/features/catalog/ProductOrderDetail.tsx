import { catalogClient } from '../../services/catalogApi';
import { useEffect, useState } from 'react';
import { ArrowLeft, Check, RefreshCw, Save, Truck } from 'lucide-react';
import api from '../../services/api';
import { usePermissions } from '../../hooks/usePermissions';
import { useAuth } from '../../contexts/AuthContext';
import { formatMoney, prettyOrderStatus } from '../../pages/workspace/utils';
import { errorMessage, getFormOrder } from './api';
import { ProductFormBuilder } from './ProductFormBuilder';
import { OrderAssetField } from './OrderAssetField';
import { selectionCodes, selectionLabel, selectionSpec, type AssetKind, type FormOrderDetail } from './types';
import { matches } from './productFormRules';
import './product-form.css';

export function ProductOrderDetail({ orderId, onBack, onChanged }: { orderId: string; onBack: () => void; onChanged?: () => void }) {
  const { can } = usePermissions();
  const { user } = useAuth();
  const superadmin = user?.role === 'SUPERADMIN';
  const canOperate = superadmin || user?.role === 'OPERATIONS';
  const [detail, setDetail] = useState<FormOrderDetail | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [prices, setPrices] = useState<Record<number, string>>({});
  const [gst, setGst] = useState('0');
  const load = async () => {
    const next = await getFormOrder(orderId); setDetail(next);
    setPrices(Object.fromEntries(next.lines.map((line) => [line.id, line.unitPricePaise === null ? '' : String(line.unitPricePaise / 100)])));
    setGst(String(Number(next.order.gst || 0) / 100)); return next;
  };
  useEffect(() => {
    let active = true; setLoading(true); setError('');
    getFormOrder(orderId).then((next) => { if (active) { setDetail(next); setPrices(Object.fromEntries(next.lines.map((line) => [line.id, line.unitPricePaise === null ? '' : String(line.unitPricePaise / 100)]))); setGst(String(Number(next.order.gst || 0) / 100)); } })
      .catch((e: unknown) => { if (active) setError(errorMessage(e)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [orderId]);
  const mutate = async (operation: () => Promise<unknown>, message: string) => {
    setBusy(true); setError(''); setNotice('');
    try { await operation(); await load(); setNotice(message); onChanged?.(); }
    catch (e) { setError(errorMessage(e)); try { await load(); } catch { /* Keep the visible details during a network failure. */ } }
    finally { setBusy(false); }
  };
  const upload = (kind: AssetKind, file?: File) => {
    if (!file) return;
    const form = new FormData(); form.append('file', file); form.append('assetKind', kind);
    void mutate(() => api.post(`/supply/orders/${orderId}/assets`, form), 'Attachment saved.');
  };
  const status = String(detail?.order.status || '').toUpperCase();
  const customization = detail ? selectionCodes(detail.orderSelections).CUSTOMIZATION : '';
  const currentAssets = detail?.assets.filter((a) => !a.supersededAt) || [];
  const design = currentAssets.find((asset) => asset.assetKind === 'DESIGN');
  const designApproved = customization !== 'CUSTOMIZED' || (!!detail?.approvedDesignAssetId && detail.approvedDesignAssetId === design?.id);
  const quoted = detail?.pricingStatus === 'QUOTED';
  const final = ['APPROVED', 'CUSTOKING_APPROVED', 'DELIVERED', 'FULFILLED', 'CANCELLED'].includes(status);
  const canQuote = superadmin && can('catalog:quote') && status !== 'DRAFT' && !final;
  const waitingDesign = status === 'DESIGN_APPROVAL';
  const readyForApproval = ['DESIGN_APPROVED_PROCESSING', 'PROCESSING', 'IN_PROGRESS', 'AWAITING_APPROVAL'].includes(status);
  const missingDeliveryAsset = detail?.formDefinition.rules.some((rule) => rule.active !== false && rule.ruleType === 'REQUIRE_ASSET' && rule.params.stage === 'BEFORE_DELIVERY' && detail.lines.some((line) => matches(rule.matchOptions, { ...selectionCodes(line.optionSelections), ...selectionCodes(detail.orderSelections) })) && !currentAssets.some((asset) => asset.assetKind === rule.params.assetKind));
  const pricing = (paise: number) => `Rs. ${formatMoney(Number(paise || 0) / 100)}`;
  return <div className="ck-content"><div className="ck-product-detail ck-product-form">
    <div className="ck-product-section-head"><button className="ck-btn ck-btn-ghost" onClick={onBack} disabled={busy}><ArrowLeft size={16} />Back to orders</button><button className="ck-btn ck-btn-ghost ck-product-icon" title="Refresh order" aria-label="Refresh order" disabled={busy} onClick={() => void mutate(async () => undefined, '')}><RefreshCw size={16} /></button></div>
    <div className="ck-product-section-head"><h1 className="ck-page-title">Order {orderId}</h1>{detail && <span className="ck-status">{prettyOrderStatus(status)}</span>}</div>
    {error && <div className="ck-alert ck-alert-re" role="alert">{error}</div>}
    {notice && <div className="ck-alert ck-alert-g" role="status">{notice}</div>}
    {loading ? <p role="status">Loading order...</p> : detail && (status === 'DRAFT' ? <ProductFormBuilder key={`${orderId}-${detail.version}`} definition={detail.formDefinition} initialOrder={detail} onSaved={(next, placed) => { setDetail(next); onChanged?.(); if (placed) void load(); }} /> : <>
      <div className="ck-product-section-head"><h2>{detail.formDefinition.category.label}</h2><span className={`ck-status ${quoted ? 'sg' : 'sam'}`}>{quoted ? 'Quoted' : 'Pending pricing'}</span></div>
      <p>{selectionLabel(detail.orderSelections, 'CUSTOMIZATION')}{detail.order.requiredByDate ? ` | Required by ${detail.order.requiredByDate}` : ''}</p>
      <p className="ck-product-muted">{customization === 'NON_CUSTOMIZED' ? 'No design approval required' : designApproved ? 'Design approval completed' : 'Design approval pending'}</p>
      <div className="ck-product-table-scroll"><table className="ck-product-order-lines"><thead><tr><th scope="col">Size</th><th scope="col">Ruling</th><th scope="col">Books</th><th scope="col">Printed pages</th>{(canQuote || quoted) && <><th scope="col">Unit price (Rs.)</th><th scope="col">Line total</th></>}</tr></thead><tbody>
        {detail.lines.map((line) => <tr key={line.id} aria-label={`Order line ${line.lineNo}`}><td data-label={`Line ${line.lineNo} - Size`}>{selectionLabel(line.optionSelections, 'SIZE')}<div className="ck-product-muted">{selectionSpec(line.optionSelections, 'SIZE')}</div></td><td data-label="Ruling">{selectionLabel(line.optionSelections, 'RULING')}</td><td data-label="Books">{line.bookCount}</td><td data-label="Printed pages">{line.pageCount}{line.requestedPageCount !== line.pageCount && <div className="ck-product-muted">Requested {line.requestedPageCount}</div>}</td>{(canQuote || quoted) && <><td data-label="Unit price (Rs.)">{canQuote ? <input aria-label={`Unit price for line ${line.lineNo}`} type="number" min="0" step="0.01" value={prices[line.id] ?? ''} disabled={busy} onChange={(event) => setPrices((current) => ({ ...current, [line.id]: event.target.value }))} /> : pricing(line.unitPricePaise || 0)}</td><td data-label="Line total">{line.lineTotalPaise === null ? 'Pending pricing' : pricing(line.lineTotalPaise)}</td></>}</tr>)}
      </tbody></table></div>
      {(quoted || canQuote) && <div className="ck-product-totals"><div><span>Subtotal</span><strong>{quoted ? pricing(detail.order.subtotal) : 'Pending pricing'}</strong></div><div><span>GST amount (Rs.)</span>{canQuote ? <label className="field"><input aria-label="GST amount in rupees" type="number" min="0" step="0.01" value={gst} disabled={busy} onChange={(event) => setGst(event.target.value)} /></label> : <strong>{pricing(detail.order.gst)}</strong>}</div><div><strong>Total</strong><strong>{quoted ? pricing(detail.order.totalAmount) : 'Pending pricing'}</strong></div>
        {canQuote && <button className="ck-btn ck-btn-g" disabled={busy} onClick={() => {
          if (detail.lines.some((line) => !prices[line.id]?.trim() || !Number.isFinite(Number(prices[line.id])) || Number(prices[line.id]) < 0) || !gst.trim() || !Number.isFinite(Number(gst)) || Number(gst) < 0) { setError('Enter a non-negative price for every line and a GST amount.'); return; }
          void mutate(() => api.put(`/supply/orders/${orderId}/quote`, { version: detail.version, lines: detail.lines.map((line) => ({ id: line.id, unitPricePaise: Math.round(Number(prices[line.id]) * 100) })), gstPaise: Math.round(Number(gst) * 100) }), 'Quote saved.');
        }}><Save size={16} />Save quote</button>}
      </div>}
      <div className="ck-product-assets">{(['DESIGN', 'PRE_DELIVERY_PHOTO'] as AssetKind[]).filter((kind) => currentAssets.some((asset) => asset.assetKind === kind) || detail.formDefinition.rules.some((rule) => rule.active !== false && rule.ruleType === 'REQUIRE_ASSET' && rule.params.assetKind === kind && detail.lines.some((line) => matches(rule.matchOptions, { ...selectionCodes(line.optionSelections), ...selectionCodes(detail.orderSelections) })))).map((kind) => <OrderAssetField assetKind={kind} key={kind} label={kind === 'DESIGN' ? 'Design artwork' : 'Pre-delivery photo'} requirement={kind === 'DESIGN' ? 'Approved artwork stays with this order' : 'Required before delivery'} orderId={orderId} asset={currentAssets.find((asset) => asset.assetKind === kind)} disabled={busy} onFile={can('order:update') && !['DELIVERED', 'FULFILLED', 'CANCELLED'].includes(status) && (kind !== 'DESIGN' || waitingDesign) ? (file) => upload(kind, file) : undefined} />)}</div>
      {detail.order.notes && <p>{detail.order.notes}</p>}
      <div className="ck-product-actions">
        {canOperate && waitingDesign && can('order:update') && <button className="ck-btn ck-btn-ghost" disabled={busy || !design} onClick={() => void mutate(() => api.post(`/catalog/orders/${orderId}/design-approved`, { assetId: design?.id }), 'Design approved.')}><Check size={16} />Approve design</button>}
        {superadmin && can('order:approve') && readyForApproval && <><span className="ck-product-muted">{quoted ? 'Pricing complete' : 'Quote required before approval'}{customization === 'CUSTOMIZED' ? designApproved ? ' | Design approval completed' : ' | Design approval required' : ''}</span><button className="ck-btn ck-btn-g" disabled={busy || !quoted || !designApproved} onClick={() => void mutate(() => api.post(`/catalog/orders/${orderId}/superadmin-approve`), 'Order approved.')}><Check size={16} />Approve order</button></>}
        {canOperate && (can('order:fulfill') || can('order:update')) && ['APPROVED', 'CUSTOKING_APPROVED'].includes(status) && <>{missingDeliveryAsset && <span className="ck-product-muted">Pre-delivery photo required</span>}<button className="ck-btn ck-btn-g" disabled={busy || missingDeliveryAsset} onClick={() => void mutate(() => catalogClient.markDelivered({ id: orderId }), 'Order marked delivered.')}><Truck size={16} />Mark delivered</button></>}
      </div>
    </>)}
  </div></div>;
}
