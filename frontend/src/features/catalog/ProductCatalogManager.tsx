import { useEffect, useState } from 'react';
import { ArrowDown, ArrowUp, Eye, Pencil, Plus, Save, Trash2, X } from 'lucide-react';
import api from '../../services/api';
import { usePermissions } from '../../hooks/usePermissions';
import { useAuth } from '../../contexts/AuthContext';
import { ProductFormBuilder } from './ProductFormBuilder';
import { errorMessage, parseProductCategories, parseProductFormDefinition } from './api';
import type { FormDefinition, ProductCategory, ProductGroup, ProductOption, ProductRule } from './types';
import './product-form.css';

type Tab = 'Products' | 'Form' | 'Conditions' | 'Preview';
type Entity = ProductCategory | ProductGroup | ProductOption | ProductRule;
type Editor = { kind: 'categories' | 'groups' | 'options' | 'rules'; original?: string | number; value: Entity };
const emptyCategory: ProductCategory = { code: '', label: '', emoji: '', description: '', orderType: 'One-time', formEnabled: false, sortOrder: 0, active: true };
const defaultParams: Record<string, Record<string, string | number>> = {
  REQUIRE_QUANTITY_TOTAL: { value: 1000, comparison: 'EQ', scope: 'ORDER', stage: 'ON_PLACE' },
  ROUND_TO_MULTIPLE: { multiple: 7, mode: 'NEAREST', minimum: 7 },
  MIN_VALUE: { value: 1 }, MAX_VALUE: { value: 10000 }, REQUIRE_ASSET: { assetKind: 'DESIGN', stage: 'ON_PLACE' },
};
function TextField({ label, value, onChange, disabled = false, type = 'text', min }: { label: string; value: string | number | null | undefined; onChange: (value: string) => void; disabled?: boolean; type?: string; min?: number }) {
  return <label className="field"><span>{label}</span><input type={type} min={min} value={value ?? ''} disabled={disabled} onChange={(event) => onChange(event.target.value)} /></label>;
}
function SelectField({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: [string, string][] }) {
  return <label className="field"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}>{options.map(([code, name]) => <option key={code} value={code}>{name}</option>)}</select></label>;
}
export function ProductCatalogManager() {
  const { can: hasPermission } = usePermissions();
  const { user } = useAuth();
  const can = (permission: string) => user?.role === 'SUPERADMIN' && hasPermission(permission);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [selected, setSelected] = useState('NOTEBOOKS');
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [tab, setTab] = useState<Tab>('Products');
  const [editor, setEditor] = useState<Editor | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [attempt, setAttempt] = useState(0);
  const loadCategories = async () => { const response = await api.get<unknown>('/supply/product-catalog/admin/categories'); const data = parseProductCategories(response.data); setCategories(data); return data; };
  const loadDefinition = async () => { const { data } = await api.get<unknown>(`/supply/product-catalog/admin/forms/${selected}`); setDefinition(parseProductFormDefinition(data)); };
  useEffect(() => { setLoading(true); setError(''); void loadCategories().catch((e: unknown) => setError(errorMessage(e))).finally(() => setLoading(false)); }, [attempt]);
  useEffect(() => { let active = true; setDefinition(null); api.get<unknown>(`/supply/product-catalog/admin/forms/${selected}`).then(({ data }) => { const parsed = parseProductFormDefinition(data); if (active) setDefinition(parsed); }).catch((e: unknown) => { if (active) setError(errorMessage(e)); }); return () => { active = false; }; }, [selected, attempt]);
  const mutate = async (action: () => Promise<unknown>, message: string) => {
    if (!can('catalog:manage')) return;
    setBusy(true); setError(''); setNotice('');
    try {
      await action(); const remaining = await loadCategories();
      if (remaining.some((category) => category.code === selected)) await loadDefinition();
      else if (remaining.length) setSelected(remaining[0].code);
      else setDefinition(null);
      setEditor(null); setNotice(message);
    }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  };
  const edit = (kind: Editor['kind'], value: Entity, original?: string | number) => { setEditor({ kind, value: structuredClone(value), original }); setError(''); };
  const change = (values: Record<string, unknown>) => setEditor((current) => current ? { ...current, value: { ...current.value, ...values } as Entity } : current);
  const remove = (kind: Editor['kind'], id: string | number, label: string, hard: boolean) => {
    if (hard && !window.confirm(`Permanently delete ${label}? Referenced records cannot be deleted.`)) return;
    void mutate(() => api.delete(`/supply/product-catalog/${kind}/${id}`, { params: { hard } }), hard ? `${label} deleted.` : `${label} deactivated.`);
  };
  const saveEditor = () => {
    if (!editor) return;
    const { id: _id, options: _options, ...body } = editor.value as Entity & { id?: number; options?: ProductOption[] };
    void mutate(() => editor.original !== undefined ? api.patch(`/supply/product-catalog/${editor.kind}/${editor.original}`, body) : api.post(`/supply/product-catalog/${editor.kind}`, body), 'Catalog changes saved.');
  };
  const category = editor?.kind === 'categories' ? editor.value as ProductCategory : null;
  const group = editor?.kind === 'groups' ? editor.value as ProductGroup : null;
  const option = editor?.kind === 'options' ? editor.value as ProductOption : null;
  const rule = editor?.kind === 'rules' ? editor.value as ProductRule : null;
  const preview = definition ? { ...definition,
    category: category?.code === selected ? category : definition.category,
    groups: definition.groups.map((item) => group && editor?.original === item.id ? { ...group, options: item.options } : { ...item, options: option && option.groupId === item.id ? editor?.original ? item.options.map((o) => o.id === editor.original ? option : o) : [...item.options, option] : item.options }),
    rules: rule ? editor?.original ? definition.rules.map((r) => r.id === editor.original ? rule : r) : [...definition.rules, rule] : definition.rules,
  } : null;
  const actions = (kind: Editor['kind'], item: Entity, id: string | number, label: string, active: boolean) => <div className="ck-product-admin-row-actions">
    <strong>{label}</strong><span className="ck-product-muted">{active ? 'Active' : 'Inactive'}</span>
    <button className="ck-btn ck-btn-ghost ck-product-icon" title={`Edit ${label}`} aria-label={`Edit ${label}`} disabled={busy || !can('catalog:manage')} onClick={() => edit(kind, item, id)}><Pencil size={16} /></button>
    {active ? <button className="ck-btn ck-btn-ghost" disabled={busy || !can('catalog:manage')} onClick={() => remove(kind, id, label, false)}>Deactivate</button> : <button className="ck-btn ck-btn-ghost" disabled={busy || !can('catalog:manage')} onClick={() => void mutate(() => api.patch(`/supply/product-catalog/${kind}/${id}`, { active: true }), `${label} activated.`)}>Activate</button>}
    <button className="ck-btn ck-btn-ghost ck-product-icon" title={`Permanently delete ${label}`} aria-label={`Permanently delete ${label}`} disabled={busy || !can('catalog:manage')} onClick={() => remove(kind, id, label, true)}><Trash2 size={16} /></button>
  </div>;
  const swap = (a: ProductOption, b: ProductOption) => void mutate(async () => {
    await api.patch(`/supply/product-catalog/options/${a.id}`, { sortOrder: b.sortOrder });
    await api.patch(`/supply/product-catalog/options/${b.id}`, { sortOrder: a.sortOrder });
  }, 'Option order updated.');
  return <div className="ck-product-admin">
    {error && <div className="ck-alert ck-alert-re" role="alert">{error}<button className="ck-btn ck-btn-ghost" disabled={busy || loading} onClick={() => setAttempt((current) => current + 1)}>Retry</button></div>}{notice && <div className="ck-alert ck-alert-g" role="status">{notice}</div>}
    <div className="ck-product-fields"><SelectField label="Product" value={selected} onChange={(code) => { setSelected(code); setEditor(null); }} options={categories.map((c) => [c.code, `${c.label}${c.active ? '' : ' (inactive)'}`])} /></div>
    <div className="ck-product-tabs" role="tablist" aria-label="Catalog views">{(['Products', 'Form', 'Conditions', 'Preview'] as Tab[]).map((name) => <button key={name} role="tab" aria-selected={tab === name} aria-controls={`catalog-tab-${name}`} id={`catalog-tab-button-${name}`} onClick={() => setTab(name)}>{name}</button>)}</div>
    {editor && <section className="ck-product-admin-editor" aria-label="Catalog editor"><div className="ck-product-section-head"><h2>{editor.original !== undefined ? 'Edit' : 'Add'} {editor.kind === 'categories' ? 'product' : editor.kind === 'groups' ? 'group' : editor.kind === 'options' ? 'option' : 'condition'}</h2><button className="ck-btn ck-btn-ghost ck-product-icon" aria-label="Close editor" title="Close editor" disabled={busy} onClick={() => setEditor(null)}><X size={16} /></button></div>
      <fieldset className="ck-product-fieldset" disabled={busy || !can('catalog:manage')}><div className="ck-product-fields">
        {(category || group || option) && <><TextField label="Code" value={(editor.value as ProductCategory).code} disabled={editor.original !== undefined} onChange={(code) => change({ code: code.toUpperCase().replace(/[^A-Z0-9_]/g, '_') })} /><TextField label="Label" value={(editor.value as ProductCategory).label} onChange={(label) => change({ label })} /></>}
        {category && <><TextField label="Description" value={category.description} onChange={(description) => change({ description })} /><SelectField label="Order type" value={category.orderType} onChange={(orderType) => change({ orderType })} options={['Recurring', 'One-time', 'Service'].map((value) => [value, value])} /><TextField label="Sort order" type="number" value={category.sortOrder} onChange={(value) => change({ sortOrder: Number(value) })} /><label className="ck-product-check"><input type="checkbox" checked={category.formEnabled} onChange={(event) => change({ formEnabled: event.target.checked })} />Form enabled</label></>}
        {group && <><TextField label="Group order" type="number" min={1} value={group.level} onChange={(value) => change({ level: Number(value) })} /><SelectField label="Selection scope" value={group.scope} onChange={(scope) => change({ scope })} options={[["ORDER", 'Once per order'], ['LINE', 'Each order line']]} /><label className="ck-product-check"><input type="checkbox" checked={group.required} onChange={(event) => change({ required: event.target.checked })} />Required</label></>}
        {option && <><TextField label="Specification" value={option.specText} onChange={(specText) => change({ specText })} /><SelectField label="Specification status" value={option.specStatus} onChange={(specStatus) => change({ specStatus })} options={[["CONFIRMED", 'Confirmed'], ['PENDING_SPEC', 'Pending specification']]} /><TextField label="Width (mm)" type="number" min={1} value={option.widthMm} onChange={(value) => change({ widthMm: value ? Number(value) : null })} /><TextField label="Height (mm)" type="number" min={1} value={option.heightMm} onChange={(value) => change({ heightMm: value ? Number(value) : null })} /><TextField label="Sort order" type="number" value={option.sortOrder} onChange={(value) => change({ sortOrder: Number(value) })} /></>}
        {rule && <><SelectField label="Condition" value={rule.ruleType} onChange={(ruleType) => change({ ruleType, params: { ...defaultParams[ruleType] }, targetField: ruleType === 'REQUIRE_ASSET' ? null : ruleType === 'REQUIRE_QUANTITY_TOTAL' ? 'BOOK_COUNT' : 'PAGE_COUNT' })} options={[["REQUIRE_QUANTITY_TOTAL", 'Exact total across all sizes'], ['ROUND_TO_MULTIPLE', 'Round to multiple'], ['MIN_VALUE', 'Minimum value'], ['MAX_VALUE', 'Maximum value'], ['REQUIRE_ASSET', 'Required attachment']]} />
          {definition?.groups.filter((g) => rule.ruleType !== 'REQUIRE_QUANTITY_TOTAL' || g.scope === 'ORDER').map((g) => <SelectField key={g.code} label={`When ${g.label.toLowerCase()} is`} value={rule.matchOptions[g.code] || ''} onChange={(value) => { const matchOptions = { ...rule.matchOptions }; if (value) matchOptions[g.code] = value; else delete matchOptions[g.code]; change({ matchOptions }); }} options={[["", 'Any'], ...g.options.map((o): [string, string] => [o.code, o.label])]} />)}
          {rule.ruleType !== 'REQUIRE_ASSET' && rule.ruleType !== 'REQUIRE_QUANTITY_TOTAL' && <SelectField label="Field" value={rule.targetField || 'PAGE_COUNT'} onChange={(targetField) => change({ targetField })} options={[["PAGE_COUNT", 'Printed pages'], ['BOOK_COUNT', 'Books']]} />}
          {['REQUIRE_QUANTITY_TOTAL', 'MIN_VALUE', 'MAX_VALUE'].includes(rule.ruleType) && <TextField label={rule.ruleType === 'REQUIRE_QUANTITY_TOTAL' ? 'Required books across the whole order' : 'Value'} value={Number(rule.params.value)} type="number" min={1} onChange={(value) => change({ params: { ...rule.params, value: Number(value) } })} />}
          {rule.ruleType === 'ROUND_TO_MULTIPLE' && <><TextField label="Multiple" type="number" min={1} value={Number(rule.params.multiple)} onChange={(value) => change({ params: { ...rule.params, multiple: Number(value) } })} /><SelectField label="Rounding mode" value={String(rule.params.mode)} onChange={(mode) => change({ params: { ...rule.params, mode } })} options={[["NEAREST", 'Nearest (midpoint down)'], ['UP', 'Always up'], ['DOWN', 'Always down']]} /><TextField label="Minimum after rounding" type="number" min={1} value={Number(rule.params.minimum)} onChange={(value) => change({ params: { ...rule.params, minimum: Number(value) } })} /></>}
          {rule.ruleType === 'REQUIRE_ASSET' && <><SelectField label="Attachment" value={String(rule.params.assetKind)} onChange={(assetKind) => change({ params: { ...rule.params, assetKind } })} options={[["DESIGN", 'Design artwork'], ['PRE_DELIVERY_PHOTO', 'Pre-delivery photo']]} /><SelectField label="Required at" value={String(rule.params.stage)} onChange={(stage) => change({ params: { ...rule.params, stage } })} options={[["ON_PLACE", 'Order placement'], ['BEFORE_DELIVERY', 'Before delivery']]} /></>}
          <TextField label="Priority" type="number" value={rule.priority} onChange={(value) => change({ priority: Number(value) })} /><TextField label="Validation message" value={rule.message} onChange={(message) => change({ message })} />
        </>}
        <label className="ck-product-check"><input type="checkbox" checked={editor.value.active !== false} onChange={(event) => change({ active: event.target.checked })} />Active</label>
      </div><div className="ck-product-actions"><button className="ck-btn ck-btn-ghost" onClick={() => setTab('Preview')}><Eye size={16} />Preview changes</button><button className="ck-btn ck-btn-g" onClick={saveEditor}><Save size={16} />{busy ? 'Saving...' : 'Save changes'}</button></div></fieldset>
    </section>}
    {loading ? <p role="status">Loading catalog...</p> : <section role="tabpanel" id={`catalog-tab-${tab}`} aria-labelledby={`catalog-tab-button-${tab}`}>
      {tab === 'Products' && <><div className="ck-product-section-head"><h2>Products</h2><button className="ck-btn ck-btn-g" disabled={!can('catalog:manage') || busy} onClick={() => edit('categories', emptyCategory)}><Plus size={16} />Add product</button></div>
        {categories.map((c) => <div className="ck-product-admin-row" key={c.code}>{actions('categories', c, c.code, c.label, c.active)}<p className="ck-product-muted">{c.description || c.code} | {c.formEnabled ? 'Form enabled' : 'Form disabled'}</p></div>)}
      </>}
      {tab === 'Form' && definition && <><div className="ck-product-section-head"><h2>Option groups</h2><button className="ck-btn ck-btn-g" disabled={!can('catalog:manage') || busy} onClick={() => edit('groups', { id: 0, categoryCode: selected, code: '', label: '', level: definition.groups.length + 1, selectionType: 'SINGLE', required: true, scope: 'LINE', active: true, options: [] })}><Plus size={16} />Add group</button></div>
        {definition.groups.map((g) => <section className="ck-product-admin-row" key={g.id}>{actions('groups', g, g.id, g.label, g.active)}<p className="ck-product-muted">{g.scope === 'ORDER' ? 'Once per order' : 'Each order line'}{g.required ? ' | Required' : ' | Optional'}</p>
          {g.options.slice().sort((a, b) => a.sortOrder - b.sortOrder).map((o, index, options) => <div className="ck-product-admin-row" key={o.id}>{actions('options', o, o.id, o.label, o.active)}<div className="ck-product-admin-row-actions"><span className="ck-product-muted">{o.specStatus === 'PENDING_SPEC' ? 'Specification pending' : o.specText || 'Specification confirmed'}</span>
            <button className="ck-btn ck-btn-ghost ck-product-icon" title={`Move ${o.label} up`} aria-label={`Move ${o.label} up`} disabled={index === 0 || busy || !can('catalog:manage')} onClick={() => swap(o, options[index - 1])}><ArrowUp size={15} /></button>
            <button className="ck-btn ck-btn-ghost ck-product-icon" title={`Move ${o.label} down`} aria-label={`Move ${o.label} down`} disabled={index === options.length - 1 || busy || !can('catalog:manage')} onClick={() => swap(o, options[index + 1])}><ArrowDown size={15} /></button>
          </div></div>)}
          <button className="ck-btn ck-btn-ghost" disabled={!can('catalog:manage') || busy} onClick={() => edit('options', { id: 0, groupId: g.id, code: '', label: '', specText: '', widthMm: null, heightMm: null, specStatus: 'CONFIRMED', sortOrder: Math.max(0, ...g.options.map((o) => o.sortOrder)) + 10, active: true })}><Plus size={16} />Add option to {g.label}</button>
        </section>)}
      </>}
      {tab === 'Conditions' && definition && <><div className="ck-product-section-head"><h2>Order conditions</h2><button className="ck-btn ck-btn-g" disabled={!can('catalog:manage') || busy} onClick={() => edit('rules', { categoryCode: selected, ruleType: 'REQUIRE_QUANTITY_TOTAL', targetField: 'BOOK_COUNT', matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { ...defaultParams.REQUIRE_QUANTITY_TOTAL }, priority: 10, message: '', active: true })}><Plus size={16} />Add condition</button></div>
        {definition.rules.map((r) => <div className="ck-product-admin-row" key={r.id}>{actions('rules', r, r.id!, r.ruleType === 'REQUIRE_QUANTITY_TOTAL' ? `Require exactly ${r.params.value} books across all sizes` : r.ruleType === 'ROUND_TO_MULTIPLE' ? `Round printed pages to ${r.params.multiple} (${String(r.params.mode).toLowerCase()})` : r.ruleType === 'REQUIRE_ASSET' ? `Require ${r.params.assetKind === 'DESIGN' ? 'design artwork' : 'pre-delivery photo'}` : `${r.ruleType === 'MIN_VALUE' ? 'Minimum' : 'Maximum'} ${r.targetField === 'PAGE_COUNT' ? 'printed pages' : 'books'}: ${r.params.value}`, r.active !== false)}<p className="ck-product-muted">{Object.entries(r.matchOptions).map(([key, value]) => `${definition.groups.find((g) => g.code === key)?.label || key}: ${definition.groups.flatMap((g) => g.options).find((o) => o.code === value)?.label || value}`).join(', ') || 'All orders'}{r.message ? ` | ${r.message}` : ''}</p></div>)}
      </>}
      {tab === 'Preview' && preview && <ProductFormBuilder key={JSON.stringify(preview)} definition={preview} preview />}
    </section>}
  </div>;
}
