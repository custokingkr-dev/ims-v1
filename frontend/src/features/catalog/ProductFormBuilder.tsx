import { useMemo, useRef, useState } from 'react';
import { Check, Plus, Save, Send, Trash2 } from 'lucide-react';
import api from '../../services/api';
import { usePermissions } from '../../hooks/usePermissions';
import { errorMessage, fieldErrorsFrom, getFormOrder, parseFormOrderDetail } from './api';
import { evaluateProductForm, matches } from './productFormRules';
import { OrderAssetField } from './OrderAssetField';
import { selectionCodes, type AssetKind, type FormDefinition, type FormInput, type FormOrderDetail, type ProductGroup } from './types';
import './product-form.css';

interface Props {
  categoryCode?: string; definition: FormDefinition; schoolId?: number | null;
  initialOrder?: FormOrderDetail; preview?: boolean; onSaved?: (detail: FormOrderDetail, placed: boolean) => void;
}
function initialSelections(groups: ProductGroup[], scope: string) {
  return Object.fromEntries(groups.filter((g) => g.active && g.scope === scope).map((group) => [group.code,
    group.options.filter((o) => o.active && o.specStatus === 'CONFIRMED').sort((a, b) => a.sortOrder - b.sortOrder)[0]?.code || '']));
}
export function ProductFormBuilder({ categoryCode, definition: suppliedDefinition, schoolId, initialOrder, preview = false, onSaved }: Props) {
  const { can } = usePermissions();
  const [saved, setSaved] = useState(initialOrder);
  const savedRef = useRef(initialOrder);
  const inFlight = useRef(false);
  const definition = saved?.formDefinition || suppliedDefinition;
  // The definition names its own category, so an order is never filed under another one.
  const orderCategory = categoryCode || definition.category.code;
  const [input, setInput] = useState<FormInput>(() => initialOrder ? {
    orderSelections: selectionCodes(initialOrder.orderSelections), lines: initialOrder.lines.map((line) => ({ selections: selectionCodes(line.optionSelections), bookCount: line.requestedBookCount ?? line.bookCount, pageCount: line.requestedPageCount ?? line.pageCount })),
  } : { orderSelections: initialSelections(definition.groups, 'ORDER'), lines: [{ selections: initialSelections(definition.groups, 'LINE'), bookCount: 0, pageCount: suppliedDefinition.category.paged === false ? 1 : 196 }] });
  const [requiredByDate, setRequiredByDate] = useState(initialOrder?.order.requiredByDate || '');
  const [notes, setNotes] = useState(initialOrder?.order.notes || '');
  const [files, setFiles] = useState<Partial<Record<AssetKind, File>>>({});
  const [blurred, setBlurred] = useState<Record<number, boolean>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const evaluation = useMemo(() => evaluateProductForm(definition.rules, input), [definition.rules, input]);
  const groups = definition.groups.filter((g) => g.active).sort((a, b) => a.level - b.level);
  // Notebooks count books and printed pages; every other category counts units and has no pages.
  const paged = definition.category.paged !== false;
  const countLabel = paged ? 'Books' : 'Count';
  const defaultPageCount = paged ? 196 : 1;
  const customized = input.orderSelections.CUSTOMIZATION === 'CUSTOMIZED';
  const assetRules = definition.rules.filter((r) => r.active !== false && r.ruleType === 'REQUIRE_ASSET' && input.lines.some((line) => matches(r.matchOptions, { ...line.selections, ...input.orderSelections })));
  const currentAsset = (kind: AssetKind) => saved?.assets.find((a) => a.assetKind === kind && !a.supersededAt);
  const canSave = preview || (saved ? can('order:update') : can('order:create'));
  const recordSaved = (detail: FormOrderDetail) => { savedRef.current = detail; setSaved(detail); };
  const save = async (place: boolean) => {
    if (preview || inFlight.current) return;
    const validation = { ...evaluation.fieldErrors };
    if (Object.values(files).some(Boolean) && !can('order:update')) validation.assets = 'You do not have permission to upload attachments.';
    for (const group of groups.filter((g) => g.required)) {
      if (group.scope === 'ORDER' && !input.orderSelections[group.code]) validation[`orderSelections.${group.code}`] = `Select ${group.label.toLowerCase()}.`;
      if (group.scope === 'LINE') input.lines.forEach((line, index) => { if (!line.selections[group.code]) validation[`lines[${index}].selections.${group.code}`] = `Select ${group.label.toLowerCase()}.`; });
    }
    if (place) {
      if (evaluation.quantityResults.some((r) => !r.valid)) validation.quantity = 'The combined book count must match the required total before placing.';
      for (const rule of assetRules.filter((r) => r.params.stage === 'ON_PLACE')) {
        const kind = rule.params.assetKind as AssetKind;
        if (!files[kind] && !currentAsset(kind)) validation[kind] = 'Attach design artwork before placing this order.';
      }
    }
    setErrors(validation); setNotice('');
    if (Object.keys(validation).length) return;
    setSaving(true); inFlight.current = true;
    try {
      const body = { orderData: input, requiredByDate: requiredByDate || null, notes };
      let detail: FormOrderDetail;
      if (savedRef.current) {
        detail = parseFormOrderDetail((await api.patch<unknown>(`/supply/orders/${savedRef.current.order.id}`, { ...body, version: savedRef.current.version })).data);
      } else {
        const created = (await api.post('/supply/orders', { ...body, category: orderCategory, status: 'DRAFT', ...(schoolId ? { schoolId } : {}) })).data;
        if (!created || typeof created.id !== 'string') throw new Error('The saved order could not be read. Check your orders before retrying.');
        // Keep the id even if the subsequent detail request fails; retry must never create twice.
        const retained = { order: { id: created.id }, version: created.version, formDefinition: definition, assets: [], ...created } as FormOrderDetail;
        savedRef.current = retained;
        detail = await getFormOrder(created.id);
      }
      recordSaved(detail);
      for (const [kind, file] of Object.entries(files)) {
        if (!file) continue;
        const form = new FormData(); form.append('file', file); form.append('assetKind', kind);
        await api.post(`/supply/orders/${detail.order.id}/assets`, form);
        setFiles((current) => ({ ...current, [kind]: undefined }));
        detail = await getFormOrder(detail.order.id); recordSaved(detail);
      }
      if (place) { await api.post(`/supply/orders/${detail.order.id}/place`); detail = await getFormOrder(detail.order.id); recordSaved(detail); }
      setNotice(place ? customized ? 'Order placed. Design approval and pricing are pending.' : 'Order placed. Processing and pricing are pending; no design approval is required.' : `Draft ${detail.order.id} saved.`);
      onSaved?.(detail, place);
    } catch (error) {
      setErrors({ ...fieldErrorsFrom(error), _api: errorMessage(error) });
      if (savedRef.current?.order.id) {
        try { recordSaved(await getFormOrder(savedRef.current.order.id)); } catch { /* Retain the known draft id for retry. */ }
      }
    } finally { setSaving(false); inFlight.current = false; }
  };
  // A group either selects from a fixed list or captures a typed value. Keeping both behind one
  // renderer means a new category needs seed data and no component change.
  const groupField = (group: ProductGroup, value: string, change: (value: string) => void, label: string, selections: Record<string, string>) => {
    const inputType = group.inputType || 'SELECT';
    if (inputType === 'SELECT') return optionSelect(group, value, change, label, selections);
    const numeric = inputType === 'INTEGER' || inputType === 'DECIMAL';
    return <span className="ck-product-typed-field">
      <input aria-label={label} type={numeric ? 'number' : 'text'}
        {...(numeric ? { min: inputType === 'INTEGER' ? '1' : '0.01', step: inputType === 'INTEGER' ? '1' : '0.01' } : { maxLength: 120 })}
        value={value || ''} disabled={saving}
        onChange={(e) => change(e.target.value)} />
      {group.unit ? <span className="ck-product-unit">{group.unit}</span> : null}
    </span>;
  };
  const optionSelect = (group: ProductGroup, value: string, change: (value: string) => void, label: string, selections: Record<string, string>) => <select aria-label={label} value={value || ''} onChange={(e) => change(e.target.value)} disabled={saving}>
    <option value="">Select {group.label.toLowerCase()}</option>
    {group.options.filter((option) => option.active).sort((a, b) => a.sortOrder - b.sortOrder).map((option) => {
      const forbidden = definition.dependencies.some((dependency) => !dependency.allowed && dependency.childOptionId === option.id && groups.some((parent) => parent.options.some((o) => o.id === dependency.parentOptionId && selections[parent.code] === o.code)));
      return <option key={option.code} value={option.code} disabled={option.specStatus === 'PENDING_SPEC' || forbidden}>{option.label}{option.specStatus === 'PENDING_SPEC' ? ' - Specification pending from Custoking' : option.specText ? ` - ${option.specText}` : ''}</option>;
    })}
  </select>;
  const isPlaced = !!saved && saved.order.status !== 'DRAFT';
  return <div className="ck-product-form">
    <div className="ck-product-section-head"><h2>{definition.category.label} order</h2><span className="ck-status sam">Pending pricing</span></div>
    {notice && <div className="ck-alert ck-alert-g" role="status"><Check size={17} aria-hidden="true" />{notice}</div>}
    {!!Object.keys(errors).length && <div className="ck-alert ck-alert-re" role="alert"><div>{Object.entries(errors).map(([key, value]) => <div key={key}>{value}</div>)}</div></div>}
    <fieldset className="ck-product-fieldset" disabled={saving || isPlaced}>
      <div className="ck-product-fields">
        {groups.filter((group) => group.scope === 'ORDER').map((group) => <label className="field" key={group.code}><span>{group.label}</span>{groupField(group, input.orderSelections[group.code], (value) => setInput((current) => ({ ...current, orderSelections: { ...current.orderSelections, [group.code]: value } })), group.label, input.orderSelections)}</label>)}
        <label className="field"><span>Required by date</span><input type="date" value={requiredByDate} onChange={(e) => setRequiredByDate(e.target.value)} /></label>
      </div>
      <div className="ck-product-lines">
        {input.lines.map((line, index) => <div className="ck-product-line" key={index}>
          <div className="ck-product-line-fields">{groups.filter((group) => group.scope === 'LINE').map((group) => <label className="field" key={group.code}><span>{group.label}</span>{groupField(group, line.selections[group.code], (value) => setInput((current) => ({ ...current, lines: current.lines.map((item, i) => i === index ? { ...item, selections: { ...item.selections, [group.code]: value } } : item) })), `${group.label} for line ${index + 1}`, { ...line.selections, ...input.orderSelections })}</label>)}
            <label className="field"><span>{countLabel}</span><input aria-label={`${countLabel} for line ${index + 1}`} type="number" min="1" step="1" value={line.bookCount || ''} onChange={(e) => setInput((current) => ({ ...current, lines: current.lines.map((item, i) => i === index ? { ...item, bookCount: Number(e.target.value) } : item) }))} /></label>
            {definition.category.paged !== false && <label className="field"><span>Printed pages</span><input aria-label={`Printed pages for line ${index + 1}`} type="number" min="1" step="1" value={line.pageCount || ''} onBlur={() => setBlurred((current) => ({ ...current, [index]: true }))} onChange={(e) => { setBlurred((current) => ({ ...current, [index]: false })); setInput((current) => ({ ...current, lines: current.lines.map((item, i) => i === index ? { ...item, pageCount: Number(e.target.value) } : item) })); }} />
              {blurred[index] && evaluation.lines[index]?.pageCount !== line.pageCount && <span className="ck-product-adjustment">Rounded from {line.pageCount} to {evaluation.lines[index].pageCount}</span>}
            </label>}
          </div><button type="button" className="ck-btn ck-btn-ghost ck-product-icon" disabled={input.lines.length === 1} aria-label={`Remove line ${index + 1}`} title="Remove line" onClick={() => { setInput((current) => ({ ...current, lines: current.lines.filter((_, i) => i !== index) })); setBlurred({}); }}><Trash2 size={17} /></button>
        </div>)}
      </div>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setInput((current) => ({ ...current, lines: [...current.lines, { selections: initialSelections(groups, 'LINE'), bookCount: 0, pageCount: defaultPageCount }] }))}><Plus size={16} aria-hidden="true" />Add line</button>
      {evaluation.quantityResults.map((result, index) => <div key={index} className={`ck-product-quantity ${result.valid ? 'ck-product-quantity-complete' : ''}`} role="status"><div><strong>All sizes combined</strong><span>{result.actualTotal.toLocaleString('en-IN')} / {result.requiredTotal.toLocaleString('en-IN')} books</span></div><span>{result.valid ? 'Required total met' : result.difference > 0 ? `${result.difference.toLocaleString('en-IN')} more books needed` : `${Math.abs(result.difference).toLocaleString('en-IN')} books over the required total`}</span></div>)}
      <div className="ck-product-assets">{assetRules.map((rule) => <OrderAssetField assetKind={rule.params.assetKind as AssetKind} key={rule.id ?? String(rule.params.assetKind)} label={rule.params.assetKind === 'DESIGN' ? 'Design artwork' : 'Pre-delivery photo'} requirement={rule.params.stage === 'ON_PLACE' ? 'Required to place order' : 'Required before delivery'} orderId={saved?.order.id} asset={currentAsset(rule.params.assetKind as AssetKind)} file={files[rule.params.assetKind as AssetKind]} disabled={saving || isPlaced || !can('order:update')} onFile={(file) => setFiles((current) => ({ ...current, [String(rule.params.assetKind)]: file }))} />)}</div>
      <label className="field"><span>Notes</span><textarea rows={2} value={notes} maxLength={255} onChange={(e) => setNotes(e.target.value)} /></label>
      {!customized && <p className="ck-product-muted">No design approval required</p>}
    </fieldset>
    {!preview && !isPlaced && <div className="ck-product-actions"><span className="ck-product-muted">{saved?.order.id ? `Draft ${saved.order.id}` : ''}</span><button type="button" className="ck-btn ck-btn-ghost" disabled={saving || !canSave} onClick={() => void save(false)}><Save size={16} aria-hidden="true" />{saving ? 'Saving...' : 'Save draft'}</button><button type="button" className="ck-btn ck-btn-g" disabled={saving || !canSave || !can('order:create')} onClick={() => void save(true)}><Send size={16} aria-hidden="true" />Place order</button></div>}
  </div>;
}
