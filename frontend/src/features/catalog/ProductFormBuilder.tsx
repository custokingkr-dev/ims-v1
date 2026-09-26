import { useMemo, useRef, useState } from 'react';
import { Check, Minus, Plus, Save, Send, Trash2 } from 'lucide-react';
import api from '../../services/api';
import { usePermissions } from '../../hooks/usePermissions';
import { errorMessage, fieldErrorsFrom, getFormOrder, parseFormOrderDetail } from './api';
import { evaluateProductForm, matches } from './productFormRules';
import { estimateLine, estimateRatesFrom } from './reportCardEstimate';
import { OrderAssetField } from './OrderAssetField';
import { selectionCodes, type AssetKind, type FormDefinition, type FormInput, type FormLine, type FormOrderDetail, type ProductGroup, type ProductOption } from './types';
import './product-form.css';

interface Props {
  categoryCode?: string; definition: FormDefinition; schoolId?: number | null;
  initialOrder?: FormOrderDetail; preview?: boolean; onSaved?: (detail: FormOrderDetail, placed: boolean) => void;
}

const renderOf = (group: ProductGroup) => group.render || (group.inputType && group.inputType !== 'SELECT' ? 'FIELD' : 'SELECT');
const activeOptions = (group: ProductGroup) => group.options.filter((o) => o.active).sort((a, b) => a.sortOrder - b.sortOrder);
const firstOption = (group: ProductGroup) => activeOptions(group).find((o) => o.specStatus === 'CONFIRMED')?.code || '';

export function ProductFormBuilder({ categoryCode, definition: suppliedDefinition, schoolId, initialOrder, preview = false, onSaved }: Props) {
  const { can } = usePermissions();
  const [saved, setSaved] = useState(initialOrder);
  const savedRef = useRef(initialOrder);
  const inFlight = useRef(false);
  const definition = saved?.formDefinition || suppliedDefinition;
  // The definition names its own category, so an order is never filed under another one.
  const orderCategory = categoryCode || definition.category.code;
  const groups = definition.groups.filter((g) => g.active).sort((a, b) => a.level - b.level);
  const orderGroups = groups.filter((g) => g.scope === 'ORDER');
  const lineGroups = groups.filter((g) => g.scope === 'LINE');
  // The prototypes split into two shapes: a table whose rows are one group's options, each with its
  // own quantity (notebook rulings, belt lengths), or a builder that adds one line at a time.
  const matrixGroup = lineGroups.find((g) => renderOf(g) === 'MATRIX');
  const contextGroups = lineGroups.filter((g) => g !== matrixGroup);
  // Notebooks count books and printed pages; every other category counts units and has no pages.
  const paged = definition.category.paged !== false;
  // The prototypes call this Quantity on the notebook form and Count everywhere else.
  const countLabel = paged ? 'Quantity' : 'Count';
  const defaultPageCount = paged ? 196 : 1;

  const [input, setInput] = useState<FormInput>(() => initialOrder ? {
    orderSelections: selectionCodes(initialOrder.orderSelections),
    lines: initialOrder.lines.map((line) => ({ selections: selectionCodes(line.optionSelections), bookCount: line.requestedBookCount ?? line.bookCount, pageCount: line.requestedPageCount ?? line.pageCount })),
  } : {
    orderSelections: Object.fromEntries(orderGroups.map((g) => [g.code, renderOf(g) === 'SEGMENTED' ? firstOption(g) : ''])),
    lines: [],
  });
  // What the builder at the top is currently set to, before anything is added to the order.
  const [context, setContext] = useState<Record<string, string>>(() =>
    Object.fromEntries(contextGroups.map((g) => [g.code, renderOf(g) === 'SEGMENTED' ? firstOption(g) : ''])));
  const [draftCount, setDraftCount] = useState('');
  const [matrixQty, setMatrixQty] = useState<Record<string, string>>({});
  const [matrixPages, setMatrixPages] = useState<Record<string, string>>({});
  const [requiredByDate, setRequiredByDate] = useState(initialOrder?.order.requiredByDate || '');
  const [notes, setNotes] = useState(initialOrder?.order.notes || '');
  const [files, setFiles] = useState<Partial<Record<AssetKind, File>>>({});
  // Print references accumulate rather than replacing one another.
  const [referenceFiles, setReferenceFiles] = useState<File[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);

  const evaluation = useMemo(() => evaluateProductForm(definition.rules, input), [definition.rules, input]);
  const customized = input.orderSelections.CUSTOMIZATION === 'CUSTOMIZED';
  const assetRules = definition.rules.filter((r) => r.active !== false && r.ruleType === 'REQUIRE_ASSET'
    && input.lines.some((line) => matches(r.matchOptions, { ...line.selections, ...input.orderSelections })));
  // Every prototype also offers an optional upload, and bill books and fliers take several print
  // references. An offer is dropped when the same kind is already required, so a customised
  // notebook shows one design field rather than two.
  const offerRules = definition.rules.filter((r) => r.active !== false && r.ruleType === 'OFFER_ASSET'
    && !assetRules.some((required) => required.params.assetKind === r.params.assetKind)
    && matches(r.matchOptions, input.orderSelections));
  const referenceRule = offerRules.find((r) => r.params.assetKind === 'PRINT_REFERENCE');
  const singleOffers = offerRules.filter((r) => r !== referenceRule);
  const ruleAccept = (rule: { params: Record<string, string | number> }) => rule.params.accept ? String(rule.params.accept) : undefined;
  const ruleMaxBytes = (rule: { params: Record<string, string | number> }) => Number(rule.params.maxBytes) || undefined;
  const currentAsset = (kind: AssetKind) => saved?.assets.find((a) => a.assetKind === kind && !a.supersededAt);
  const canSave = preview || (saved ? can('order:update') : can('order:create'));
  const isPlaced = !!saved && saved.order.status !== 'DRAFT';
  const recordSaved = (detail: FormOrderDetail) => { savedRef.current = detail; setSaved(detail); };
  const totalUnits = input.lines.reduce((sum, line) => sum + (line.bookCount || 0), 0);

  const labelFor = (groupCode: string, optionCode: string) =>
    groups.find((g) => g.code === groupCode)?.options.find((o) => o.code === optionCode)?.label || optionCode;
  // What a line is called in the order list: its matrix option if there is one, otherwise the
  // selections that distinguish it.
  const lineLabel = (line: FormLine) => matrixGroup
    ? labelFor(matrixGroup.code, line.selections[matrixGroup.code])
    : contextGroups.map((g) => renderOf(g) === 'FIELD' ? `${line.selections[g.code] || ''}${g.unit || ''}` : labelFor(g.code, line.selections[g.code])).filter(Boolean).join(' · ');
  // Lines added together under the same context are shown as one block, as the prototypes do.
  const contextLabel = (line: FormLine) => contextGroups
    .map((g) => renderOf(g) === 'FIELD' ? '' : labelFor(g.code, line.selections[g.code]))
    .filter(Boolean).join(' · ');
  const blocks = useMemo(() => {
    const byContext = new Map<string, { label: string; lines: { line: FormLine; index: number }[] }>();
    input.lines.forEach((line, index) => {
      const key = contextGroups.map((g) => line.selections[g.code] || '').join('|');
      if (!byContext.has(key)) byContext.set(key, { label: contextLabel(line), lines: [] });
      byContext.get(key)!.lines.push({ line, index });
    });
    return [...byContext.values()];
  }, [input.lines]);

  const setLineCount = (index: number, value: number) => setInput((current) => ({
    ...current, lines: current.lines.map((item, i) => i === index ? { ...item, bookCount: Math.max(0, value) } : item),
  }));
  const removeLine = (index: number) => setInput((current) => ({ ...current, lines: current.lines.filter((_, i) => i !== index) }));

  const addMatrixRows = () => {
    const missing = contextGroups.find((g) => g.required && !context[g.code]);
    if (missing) { setErrors({ context: `Select ${missing.label.toLowerCase()}.` }); return; }
    const additions: FormLine[] = activeOptions(matrixGroup!)
      .filter((option) => Number(matrixQty[option.code]) > 0)
      .map((option) => ({
        selections: { ...context, [matrixGroup!.code]: option.code },
        bookCount: Number(matrixQty[option.code]),
        pageCount: Number(matrixPages[option.code]) || defaultPageCount,
      }));
    if (!additions.length) { setErrors({ context: `Enter a ${countLabel.toLowerCase()} on at least one row.` }); return; }
    setErrors({});
    setInput((current) => {
      const lines = [...current.lines];
      for (const addition of additions) {
        const existing = lines.findIndex((line) => contextGroups.every((g) => line.selections[g.code] === addition.selections[g.code])
          && line.selections[matrixGroup!.code] === addition.selections[matrixGroup!.code]);
        if (existing >= 0) lines[existing] = { ...lines[existing], bookCount: lines[existing].bookCount + addition.bookCount, pageCount: addition.pageCount };
        else lines.push(addition);
      }
      return { ...current, lines };
    });
    setMatrixQty({});
  };

  const addSingleLine = () => {
    const missing = contextGroups.find((g) => g.required && !context[g.code]);
    if (missing) { setErrors({ context: `Enter ${missing.label.toLowerCase()}.` }); return; }
    if (!(Number(draftCount) > 0)) { setErrors({ context: `Enter a ${countLabel.toLowerCase()}.` }); return; }
    setErrors({});
    setInput((current) => ({ ...current, lines: [...current.lines, { selections: { ...context }, bookCount: Number(draftCount), pageCount: defaultPageCount }] }));
    setDraftCount('');
  };

  const save = async (place: boolean) => {
    if (preview || inFlight.current) return;
    const validation = { ...evaluation.fieldErrors };
    if (Object.values(files).some(Boolean) && !can('order:update')) validation.assets = 'You do not have permission to upload attachments.';
    if (!input.lines.length) validation.lines = 'Add at least one line to the order.';
    for (const group of orderGroups.filter((g) => g.required)) {
      if (!input.orderSelections[group.code]) validation[`orderSelections.${group.code}`] = `Select ${group.label.toLowerCase()}.`;
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
        const created = (await api.post('/catalog/orders', { ...body, category: orderCategory, status: 'DRAFT', ...(schoolId ? { schoolId } : {}) })).data;
        if (!created || typeof created.id !== 'string') throw new Error('The saved order could not be read. Check your orders before retrying.');
        // Keep the id even if the subsequent detail request fails; retry must never create twice.
        const retained = { order: { id: created.id }, version: created.version, formDefinition: definition, assets: [], ...created } as FormOrderDetail;
        savedRef.current = retained;
        detail = await getFormOrder(created.id);
      }
      recordSaved(detail);
      const queued: [string, File][] = [
        ...Object.entries(files).filter((entry): entry is [string, File] => !!entry[1]),
        ...referenceFiles.map((file) => ['PRINT_REFERENCE', file] as [string, File]),
      ];
      for (const [kind, file] of queued) {
        const form = new FormData(); form.append('file', file); form.append('assetKind', kind);
        await api.post(`/supply/orders/${detail.order.id}/assets`, form);
        detail = await getFormOrder(detail.order.id); recordSaved(detail);
      }
      if (queued.length) { setFiles({}); setReferenceFiles([]); }
      if (place) { await api.post(`/catalog/orders/${detail.order.id}/place`); detail = await getFormOrder(detail.order.id); recordSaved(detail); }
      setNotice(place ? customized ? 'Order placed. Design approval and pricing are pending.' : 'Order placed. Processing and pricing are pending; no design approval is required.' : `Draft ${detail.order.id} saved.`);
      onSaved?.(detail, place);
    } catch (error) {
      setErrors({ ...fieldErrorsFrom(error), _api: errorMessage(error) });
      if (savedRef.current?.order.id) {
        try { recordSaved(await getFormOrder(savedRef.current.order.id)); } catch { /* Retain the known draft id for retry. */ }
      }
    } finally { setSaving(false); inFlight.current = false; }
  };

  const optionDisabled = (option: ProductOption, selections: Record<string, string>) => option.specStatus === 'PENDING_SPEC'
    || definition.dependencies.some((dependency) => !dependency.allowed && dependency.childOptionId === option.id
      && groups.some((parent) => parent.options.some((o) => o.id === dependency.parentOptionId && selections[parent.code] === o.code)));

  // A group is presented the way its own prototype presents it: buttons, a dropdown or a typed entry.
  const groupControl = (group: ProductGroup, value: string, change: (value: string) => void, selections: Record<string, string>) => {
    const mode = renderOf(group);
    if (mode === 'FIELD') {
      const numeric = group.inputType === 'INTEGER' || group.inputType === 'DECIMAL';
      return <span className="ck-product-typed-field">
        <input aria-label={group.label} type={numeric ? 'number' : 'text'}
          {...(numeric ? { min: group.inputType === 'INTEGER' ? '1' : '0.01', step: group.inputType === 'INTEGER' ? '1' : '0.01' } : { maxLength: 120 })}
          value={value || ''} disabled={saving} onChange={(e) => change(e.target.value)} />
        {group.unit ? <span className="ck-product-unit">{group.unit}</span> : null}
      </span>;
    }
    if (mode === 'SEGMENTED') {
      return <span className="ck-product-seg" role="group" aria-label={group.label}>
        {activeOptions(group).map((option) => <button key={option.code} type="button"
          className={`ck-product-seg-btn${value === option.code ? ' is-on' : ''}`}
          aria-pressed={value === option.code} disabled={saving || optionDisabled(option, selections)}
          onClick={() => change(option.code)}>{option.label}</button>)}
      </span>;
    }
    return <select aria-label={group.label} value={value || ''} onChange={(e) => change(e.target.value)} disabled={saving}>
      <option value="">Select {group.label.toLowerCase()}</option>
      {activeOptions(group).map((option) => <option key={option.code} value={option.code} disabled={optionDisabled(option, selections)}>
        {option.label}{option.specStatus === 'PENDING_SPEC' ? ' - Specification pending from Custoking' : option.specText ? ` - ${option.specText}` : ''}
      </option>)}
    </select>;
  };

  // A <label> takes its first labelable descendant, so wrapping a row of buttons in one folds the
  // group's name into every button's accessible name. Segmented groups carry their own aria-label.
  const groupField = (group: ProductGroup, value: string, change: (value: string) => void, selections: Record<string, string>) => {
    const control = groupControl(group, value, change, selections);
    if (renderOf(group) === 'SEGMENTED') {
      return <div className="field" key={group.code}><span>{group.label}</span>{control}</div>;
    }
    return <label className="field" key={group.code}><span>{group.label}</span>{control}</label>;
  };

  // Only report cards carry an estimate today. It is computed, never typed, and advisory.
  const estimateRates = estimateRatesFrom(definition.rules);
  const estimate = estimateRates && input.lines.length ? input.lines.reduce((total, line) => {
    const pages = Number(labelFor('INNER_PAGES', line.selections.INNER_PAGES)) || 0;
    return total + estimateLine(estimateRates, {
      size: labelFor('SIZE', line.selections.SIZE),
      pages,
      folding: line.selections.FOLDING === 'YES',
      quantity: line.bookCount || 0,
    }).lineTotal;
  }, 0) : null;

  const builderSelections = { ...context, ...input.orderSelections };
  return <div className="ck-product-form">
    <div className="ck-product-section-head"><h2>{definition.category.label} order</h2><span className="ck-status sam">Pending pricing</span></div>
    {notice && <div className="ck-alert ck-alert-g" role="status"><Check size={17} aria-hidden="true" />{notice}</div>}
    {!!Object.keys(errors).length && <div className="ck-alert ck-alert-re" role="alert"><div>{Object.entries(errors).map(([key, value]) => <div key={key}>{value}</div>)}</div></div>}

    <fieldset className="ck-product-fieldset" disabled={saving || isPlaced}>
      <div className="ck-product-fields">
        {orderGroups.map((group) => groupField(group, input.orderSelections[group.code],
          (value) => setInput((current) => ({ ...current, orderSelections: { ...current.orderSelections, [group.code]: value } })), builderSelections))}
        {contextGroups.map((group) => groupField(group, context[group.code],
          (value) => setContext((current) => ({ ...current, [group.code]: value })), builderSelections))}
        {!matrixGroup && <label className="field"><span>{countLabel}</span>
          <input aria-label={countLabel} type="number" min="1" step="1" value={draftCount} onChange={(e) => setDraftCount(e.target.value)} />
        </label>}
        <label className="field"><span>Required by date</span><input type="date" value={requiredByDate} onChange={(e) => setRequiredByDate(e.target.value)} /></label>
      </div>

      {/* The prototypes keep the upload inside the choices card, above the line builder: a school
          attaches the artwork while it is choosing, not after the order has been assembled. */}
      <div className="ck-product-assets">{assetRules.map((rule) => <OrderAssetField assetKind={rule.params.assetKind as AssetKind} key={rule.id ?? String(rule.params.assetKind)}
        label={rule.params.assetKind === 'DESIGN' ? 'Design artwork' : 'Pre-delivery photo'}
        requirement={rule.params.stage === 'ON_PLACE' ? 'Required to place order' : 'Required before delivery'}
        orderId={saved?.order.id} asset={currentAsset(rule.params.assetKind as AssetKind)} file={files[rule.params.assetKind as AssetKind]}
        disabled={saving || isPlaced || !can('order:update')} onFile={(file) => setFiles((current) => ({ ...current, [String(rule.params.assetKind)]: file }))} />)}

      {singleOffers.map((rule) => <OrderAssetField key={rule.id ?? `offer-${rule.params.assetKind}`}
        assetKind={rule.params.assetKind as AssetKind} label={String(rule.params.label || 'Attachment')} requirement="Optional"
        accept={ruleAccept(rule)} maxBytes={ruleMaxBytes(rule)}
        orderId={saved?.order.id} asset={currentAsset(rule.params.assetKind as AssetKind)} file={files[rule.params.assetKind as AssetKind]}
        disabled={saving || isPlaced || !can('order:update')} onFile={(file) => setFiles((current) => ({ ...current, [String(rule.params.assetKind)]: file }))} />)}

      {referenceRule && <div className="ck-product-references">
        {/* Several images may be shared for one order, so each new choice is added to the list. */}
        {[...(saved?.assets.filter((a) => a.assetKind === 'PRINT_REFERENCE' && !a.supersededAt) || []).map((asset, i) => ({ key: `saved-${asset.id}`, asset, file: undefined, index: i })),
          ...referenceFiles.map((file, i) => ({ key: `new-${i}`, asset: undefined, file, index: i }))].map((entry) => <OrderAssetField
            key={entry.key} assetKind="PRINT_REFERENCE" label={String(referenceRule.params.label || 'Print reference')} requirement="Optional"
            accept={ruleAccept(referenceRule)} maxBytes={ruleMaxBytes(referenceRule)}
            orderId={saved?.order.id} asset={entry.asset} file={entry.file}
            disabled={saving || isPlaced || !can('order:update')}
            onFile={entry.file ? (file) => setReferenceFiles((current) => current.filter((_, i) => file ? true : i !== entry.index)) : undefined} />)}
        <label className={`ck-btn ck-btn-ghost${saving || isPlaced ? ' ck-product-disabled' : ''}`} htmlFor="print-reference-add">Add an image</label>
        <input id="print-reference-add" className="ck-product-file-input" type="file" multiple
          accept={ruleAccept(referenceRule)} disabled={saving || isPlaced || !can('order:update')}
          aria-label={String(referenceRule.message || 'Share all the images')}
          onChange={(event) => {
            const chosen = [...(event.target.files || [])]; event.target.value = '';
            if (chosen.length) setReferenceFiles((current) => [...current, ...chosen]);
          }} />
        <p className="ck-product-muted">{String(referenceRule.message || '')}</p>
      </div>}</div>

      {matrixGroup ? <div className="ck-product-matrix">
        <table className="ck-product-matrix-table">
          <thead><tr><th>{matrixGroup.label}</th><th>{countLabel}</th>{paged && <th>Pages</th>}</tr></thead>
          <tbody>{activeOptions(matrixGroup).map((option) => <tr key={option.code}>
            <th scope="row">{option.label}</th>
            <td><input aria-label={`${countLabel} for ${option.label}`} type="number" min="0" step="1"
              value={matrixQty[option.code] || ''} onChange={(e) => setMatrixQty((current) => ({ ...current, [option.code]: e.target.value }))} /></td>
            {paged && <td><input aria-label={`Pages for ${option.label}`} type="number" min="1" step="1"
              value={matrixPages[option.code] ?? String(defaultPageCount)} onChange={(e) => setMatrixPages((current) => ({ ...current, [option.code]: e.target.value }))} /></td>}
          </tr>)}</tbody>
        </table>
        <div className="ck-product-matrix-foot">
          <span className="ck-product-muted">Rows left at 0 are skipped.</span>
          <button type="button" className="ck-btn ck-btn-ghost" onClick={addMatrixRows}><Plus size={16} aria-hidden="true" />Add to order</button>
        </div>
      </div> : <div className="ck-product-matrix-foot">
        <button type="button" className="ck-btn ck-btn-ghost" onClick={addSingleLine}><Plus size={16} aria-hidden="true" />Add</button>
      </div>}

      <section className="ck-product-order" role="region" aria-label="Order">
        <h3>Order</h3>
        {!input.lines.length && <p className="ck-product-muted">Nothing added yet.</p>}
        {blocks.map((block, blockIndex) => <div className="ck-product-order-block" key={blockIndex}>
          {block.label && <div className="ck-product-order-context">{block.label}</div>}
          <ul className="ck-product-order-lines">
            {block.lines.map(({ line, index }) => <li key={index}>
              <span className="ck-product-order-name">{lineLabel(line)}</span>
              <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" aria-label={`Decrease ${lineLabel(line)}`}
                onClick={() => setLineCount(index, (line.bookCount || 0) - 1)}><Minus size={15} /></button>
              {/* Distinct from the builder's own entry above, which carries the same option name. */}
              <input aria-label={`Ordered ${countLabel.toLowerCase()} for ${lineLabel(line)}`} type="number" min="0" step="1"
                value={line.bookCount || 0} onChange={(e) => setLineCount(index, Number(e.target.value))} />
              <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" aria-label={`Increase ${lineLabel(line)}`}
                onClick={() => setLineCount(index, (line.bookCount || 0) + 1)}><Plus size={15} /></button>
              {paged && <span className="ck-product-order-pages">{line.pageCount} pages</span>}
              {evaluation.lines[index] && evaluation.lines[index].bookCount !== line.bookCount
                && <span className="ck-product-adjustment">Raised to {evaluation.lines[index].bookCount.toLocaleString('en-IN')}</span>}
              {paged && evaluation.lines[index] && evaluation.lines[index].pageCount !== line.pageCount
                && <span className="ck-product-adjustment">Rounded from {line.pageCount} to {evaluation.lines[index].pageCount}</span>}
              <button type="button" className="ck-btn ck-btn-ghost ck-product-icon" aria-label={`Remove ${lineLabel(line)}`}
                onClick={() => removeLine(index)}><Trash2 size={15} /></button>
            </li>)}
          </ul>
        </div>)}
      </section>

      {evaluation.quantityResults.map((result, index) => <div key={index} className={`ck-product-quantity ${result.valid ? 'ck-product-quantity-complete' : ''}`} role="status">
        <div><strong>All sizes combined</strong><span>{result.actualTotal.toLocaleString('en-IN')} / {result.requiredTotal.toLocaleString('en-IN')} books</span></div>
        <span>{result.valid ? 'Required total met' : result.difference > 0 ? `${result.difference.toLocaleString('en-IN')} more books needed` : `${Math.abs(result.difference).toLocaleString('en-IN')} books over the required total`}</span>
      </div>)}

      {definition.category.notesEnabled && <label className="field"><span>Notes</span>
        <textarea rows={2} value={notes} maxLength={255} onChange={(e) => setNotes(e.target.value)} /></label>}
      {!customized && <p className="ck-product-muted">No design approval required</p>}
    </fieldset>

    {estimate !== null && <div className="ck-product-estimate" role="status">
      <span><strong>Estimated total</strong> {estimate.toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })}</span>
      <span className="ck-product-muted">Estimate only. The Custoking quote is the price of record.</span>
    </div>}
    {!preview && !isPlaced && <div className="ck-product-footbar">
      <span className="ck-product-foot-summary">{totalUnits.toLocaleString('en-IN')} {paged ? 'books' : 'units'}{saved?.order.id ? ` · Draft ${saved.order.id}` : ''}</span>
      <span className="ck-product-foot-actions">
        <button type="button" className="ck-btn ck-btn-ghost" disabled={saving || !canSave} onClick={() => void save(false)}><Save size={16} aria-hidden="true" />{saving ? 'Saving...' : 'Save draft'}</button>
        <button type="button" className="ck-btn ck-btn-g" disabled={saving || !canSave || !can('order:create')} onClick={() => void save(true)}><Send size={16} aria-hidden="true" />Place order</button>
      </span>
    </div>}
  </div>;
}
