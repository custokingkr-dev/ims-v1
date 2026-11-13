import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ExternalLink, MoreHorizontal, Plus, RefreshCw, Search } from 'lucide-react';
import { Modal } from '../../../components/Modal';
import { Field } from '../../../pages/workspace/ui';
import { safeInviteHref } from '../shared/invite';
import { setupApi, workflowError, type VendorCategoryView, type VendorRequest, type VendorView } from './api';
import { VendorForm } from './VendorForm';
import { vendorStatus } from './vendorRules';
import './setup.css';

const ALL = '__ALL__';
const CATEGORY_NAME_MAX = 100;

function useLoaded<T>(load: () => Promise<T>, failure: string) {
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState('');
  const latest = useRef(0);
  const reload = useCallback(async () => {
    const request = ++latest.current;
    try { const next = await load(); if (request === latest.current) { setValue(next); setError(''); } }
    catch (e) { if (request === latest.current) setError(workflowError(e, failure)); }
  }, [load, failure]);
  useEffect(() => { void reload(); }, [reload]);
  return { value, error, reload, setValue };
}

function LoadFailure({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="ck-alert ck-alert-re">
      <span role="alert">{message}</span>
      <button type="button" className="ck-btn ck-btn-ghost" onClick={onRetry}><RefreshCw size={15} aria-hidden="true" /> Retry</button>
    </div>
  );
}

function matches(vendor: VendorView, term: string): boolean {
  if (!term) return true;
  return [vendor.name, vendor.contactPerson, vendor.phone, vendor.altPhone, vendor.city, vendor.email]
    .some((value) => value?.toLowerCase().includes(term));
}

function byOrderThenName(left: VendorCategoryView, right: VendorCategoryView) {
  return left.sortOrder - right.sortOrder || left.name.localeCompare(right.name);
}

/** The row menu of one category: Rename, and Deactivate or Activate. Escape or a click elsewhere closes it. */
function CategoryMenu({ category, disabled, onRename, onToggle }: {
  category: VendorCategoryView; disabled: boolean; onRename: () => void; onToggle: () => void;
}) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const first = useRef<HTMLButtonElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return undefined;
    first.current?.focus();
    const close = (event: MouseEvent) => { if (!wrap.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
  };
  const choose = (action: () => void) => { setOpen(false); action(); };
  return (
    <div className="ck-vd-menu-wrap" ref={wrap} onKeyDown={onKeyDown}>
      <button ref={trigger} type="button" className="ck-btn ck-btn-ghost ck-vd-icon" aria-label={`Actions for ${category.name}`}
        aria-haspopup="menu" aria-expanded={open} disabled={disabled} onClick={() => setOpen((value) => !value)}>
        <MoreHorizontal size={16} aria-hidden="true" />
      </button>
      {open && (
        <div className="ck-vd-menu" role="menu" aria-label={`${category.name} actions`}>
          <button ref={first} type="button" role="menuitem" onClick={() => choose(onRename)}>Rename</button>
          <button type="button" role="menuitem" onClick={() => choose(onToggle)}>{category.active ? 'Deactivate' : 'Activate'}</button>
        </div>
      )}
    </div>
  );
}

/**
 * Workflow set-up → Vendors (design section 4.5, D31–D32): the 18 supplier categories with their vendor counts, and the
 * vendors with their contacts and WhatsApp groups. Superadmin only; operators see vendors inside the order's stage cards.
 */
export function VendorDirectory() {
  const loadCategories = useCallback(() => setupApi.vendorCategories(), []);
  const loadVendors = useCallback(() => setupApi.vendors(), []);
  const categories = useLoaded(loadCategories, 'Unable to load the vendor categories.');
  const vendors = useLoaded(loadVendors, 'Unable to load the vendors.');
  const [selected, setSelected] = useState(ALL);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<{ vendor: VendorView | null; preset: string | null } | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [renaming, setRenaming] = useState<VendorCategoryView | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renameError, setRenameError] = useState('');
  const [deactivating, setDeactivating] = useState<VendorCategoryView | null>(null);
  const [categoryBusy, setCategoryBusy] = useState(false);

  const sortedCategories = useMemo(() => [...(categories.value ?? [])].sort(byOrderThenName), [categories.value]);
  const names = useMemo(() => new Map(sortedCategories.map((category) => [category.code, category])), [sortedCategories]);
  const selectedCategory = selected === ALL ? null : names.get(selected) ?? null;
  const term = search.trim().toLowerCase();
  const shown = useMemo(() => (vendors.value ?? [])
    .filter((vendor) => (selected === ALL || vendor.categories.includes(selected)) && matches(vendor, term))
    .sort((left, right) => Number(right.active) - Number(left.active) || left.name.localeCompare(right.name)), [vendors.value, selected, term]);

  const openAdd = () => { setFormError(''); setEditing({ vendor: null, preset: selectedCategory?.code ?? null }); };
  const openEdit = (vendor: VendorView) => { setFormError(''); setEditing({ vendor, preset: null }); };

  const saveVendor = async (request: VendorRequest) => {
    if (!editing) return;
    setSaving(true); setFormError(''); setNotice(''); setError('');
    try {
      const saved = editing.vendor ? await setupApi.updateVendor(editing.vendor.id, request) : await setupApi.createVendor(request);
      setEditing(null);
      setNotice(editing.vendor ? (editing.vendor.active && !saved.active ? `${saved.name} deactivated.` : `${saved.name} saved.`) : `${saved.name} added.`);
      // Counts per category change with membership, so both lists are read again.
      await Promise.all([vendors.reload(), categories.reload()]);
    } catch (e) {
      setFormError(workflowError(e, 'Unable to save the vendor.'));
    } finally {
      setSaving(false);
    }
  };

  const updateCategory = async (category: VendorCategoryView, request: { name?: string; active?: boolean }, done: string) => {
    setCategoryBusy(true); setError(''); setNotice('');
    try {
      await setupApi.updateVendorCategory(category.code, request);
      await categories.reload();
      setNotice(done);
      return true;
    } catch (e) {
      const message = workflowError(e, 'Unable to update the category.');
      if (request.name !== undefined) setRenameError(message); else setError(message);
      return false;
    } finally {
      setCategoryBusy(false);
    }
  };

  const startRename = (category: VendorCategoryView) => { setRenaming(category); setRenameValue(category.name); setRenameError(''); };
  const submitRename = async () => {
    if (!renaming) return;
    const name = renameValue.trim();
    if (!name) { setRenameError('Enter a category name.'); return; }
    if (name.length > CATEGORY_NAME_MAX) { setRenameError(`Use at most ${CATEGORY_NAME_MAX} characters.`); return; }
    if (await updateCategory(renaming, { name }, `Category renamed to ${name}.`)) setRenaming(null);
  };
  const toggleCategory = (category: VendorCategoryView) => {
    if (category.active) setDeactivating(category);
    else void updateCategory(category, { active: true }, `${category.name} activated.`);
  };

  const emptyText = term
    ? `No vendors match “${search.trim()}”.`
    : selectedCategory ? `No vendors in ${selectedCategory.name} yet.` : 'No vendors yet.';

  return (
    <section className="ck-vd" aria-labelledby="ck-vd-title">
      <div className="ck-vd-head">
        <div>
          <h3 id="ck-vd-title">Vendor directory</h3>
          <p className="ck-setup-muted">Suppliers for every item, with their contacts and WhatsApp groups. Operators choose from these on each order.</p>
        </div>
      </div>
      {error && <p className="ck-setup-error" role="alert">{error}</p>}
      {notice && <p className="ck-setup-notice" role="status">{notice}</p>}
      <div className="ck-vd-layout">
        <nav className="ck-vd-cats" aria-label="Vendor categories">
          {categories.error ? <LoadFailure message={categories.error} onRetry={() => void categories.reload()} />
            : categories.value === null ? <p className="ck-setup-muted" role="status">Loading categories…</p> : (
              <ul>
                <li className={`ck-vd-cat${selected === ALL ? ' is-selected' : ''}`}>
                  <button type="button" className="ck-vd-cat-pick" aria-current={selected === ALL ? 'true' : undefined} onClick={() => setSelected(ALL)}>
                    <span>All vendors</span><span className="ck-vd-count">{vendors.value?.length ?? '–'}<span className="ck-sr-only"> vendors</span></span>
                  </button>
                </li>
                {sortedCategories.map((category) => (
                  <li key={category.code} className={`ck-vd-cat${selected === category.code ? ' is-selected' : ''}`}>
                    <button type="button" className="ck-vd-cat-pick" aria-current={selected === category.code ? 'true' : undefined}
                      onClick={() => setSelected(category.code)}>
                      <span>{category.name}{!category.active && <span className="ck-vd-tag">Inactive</span>}</span>
                      <span className="ck-vd-count">{category.vendorCount}<span className="ck-sr-only"> vendors</span></span>
                    </button>
                    <CategoryMenu category={category} disabled={categoryBusy} onRename={() => startRename(category)} onToggle={() => toggleCategory(category)} />
                  </li>
                ))}
              </ul>
            )}
        </nav>
        <div className="ck-vd-main">
          <div className="ck-vd-toolbar">
            <label className="ck-vd-search">
              <Search size={16} aria-hidden="true" />
              <input type="search" aria-label="Search vendors" placeholder="Search name, contact, phone or city" value={search}
                onChange={(e) => setSearch(e.target.value)} />
            </label>
            <button type="button" className="ck-btn ck-btn-g" disabled={!categories.value} onClick={openAdd}><Plus size={16} aria-hidden="true" /> Add vendor</button>
          </div>
          {vendors.error ? <LoadFailure message={vendors.error} onRetry={() => void vendors.reload()} />
            : vendors.value === null ? <p className="ck-setup-muted" role="status">Loading vendors…</p>
              : shown.length === 0 ? (
                <div className="ck-vd-empty">
                  <p>{emptyText}</p>
                  {!term && <button type="button" className="ck-btn ck-btn-ghost" disabled={!categories.value} onClick={openAdd}><Plus size={16} aria-hidden="true" /> Add vendor</button>}
                </div>
              ) : (
                <div className="ck-table-wrap">
                  <table className="ck-table ck-vd-table">
                    <caption className="ck-sr-only">{selectedCategory ? `Vendors in ${selectedCategory.name}` : 'All vendors'}</caption>
                    <thead><tr><th scope="col">Name</th><th scope="col">Categories</th><th scope="col">Contact person</th><th scope="col">Phone</th>
                      <th scope="col">WhatsApp group</th><th scope="col">City</th><th scope="col">Status</th><th scope="col"><span className="ck-sr-only">Edit</span></th></tr></thead>
                    <tbody>
                      {shown.map((vendor) => {
                        const status = vendorStatus(vendor);
                        const invite = safeInviteHref(vendor.whatsappGroupUrl);
                        return (
                          <tr key={vendor.id}>
                            <td><strong>{vendor.name}</strong></td>
                            <td>
                              <ul className="ck-vd-chips" aria-label={`${vendor.name} categories`}>
                                {vendor.categories.map((code) => <li key={code} className="ck-vd-chip">{names.get(code)?.name ?? code}</li>)}
                              </ul>
                            </td>
                            <td>{vendor.contactPerson || '—'}</td>
                            <td className="ck-vd-nowrap">
                              {vendor.phone ? <a href={`tel:${vendor.phone.replace(/[^\d+]/g, '')}`}>{vendor.phone}</a> : '—'}
                              {vendor.altPhone && <div className="ck-setup-muted">Alt: {vendor.altPhone}</div>}
                            </td>
                            <td>{invite
                              ? <a href={invite} target="_blank" rel="noopener noreferrer" aria-label={`Open ${vendor.name} WhatsApp group`}>Open <ExternalLink size={13} aria-hidden="true" /></a>
                              : '—'}</td>
                            <td>{vendor.city || '—'}</td>
                            <td><span className={`ck-vd-status is-${status.tone}`}>{status.label}</span></td>
                            <td><button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-label={`Edit ${vendor.name}`} onClick={() => openEdit(vendor)}>Edit</button></td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
        </div>
      </div>

      {editing && categories.value && (
        <VendorForm vendor={editing.vendor} categories={sortedCategories} presetCategory={editing.preset} busy={saving} serverError={formError}
          onSave={(request) => void saveVendor(request)} onClose={() => { if (!saving) setEditing(null); }} />
      )}

      {renaming && (
        <Modal title={`Rename ${renaming.name}`} subtitle="The code stays the same, so vendors and orders keep this category." disabled={categoryBusy}
          onClose={() => setRenaming(null)} footer={<>
            <button type="button" className="ck-btn ck-btn-ghost" disabled={categoryBusy} onClick={() => setRenaming(null)}>Cancel</button>
            <button type="button" className="ck-btn ck-btn-g" disabled={categoryBusy} onClick={() => void submitRename()}>{categoryBusy ? 'Saving…' : 'Save name'}</button>
          </>}>
          <div className="ck-vd-form">
            <Field label="Category name" error={renameError}>
              <input value={renameValue} onChange={(e) => { setRenameValue(e.target.value); setRenameError(''); }} />
            </Field>
          </div>
        </Modal>
      )}

      {deactivating && (
        <Modal title={`Deactivate ${deactivating.name}?`} disabled={categoryBusy} onClose={() => setDeactivating(null)} footer={<>
          <button type="button" className="ck-btn ck-btn-ghost" disabled={categoryBusy} onClick={() => setDeactivating(null)}>Cancel</button>
          <button type="button" className="ck-btn ck-btn-re" disabled={categoryBusy}
            onClick={() => void updateCategory(deactivating, { active: false }, `${deactivating.name} deactivated.`).then(() => setDeactivating(null))}>
            {categoryBusy ? 'Saving…' : 'Deactivate category'}
          </button>
        </>}>
          <p>{deactivating.name} will no longer be offered when vendors are chosen for an order. Its vendors stay in the directory, and orders that already chose them keep them.</p>
        </Modal>
      )}
    </section>
  );
}
