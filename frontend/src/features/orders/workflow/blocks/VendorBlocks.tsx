import { useEffect, useMemo } from 'react';
import { ExternalLink, Phone } from 'lucide-react';
import { safeInviteHref } from '../../shared/invite';
import { workflowApi } from '../api';
import { useWorkflow } from '../context';
import { useServerDraft } from '../hooks';
import { initials } from '../model';
import type { BlockSpec, VendorCategoryView, VendorSummary } from '../types';
import { Badge, BlockCard, blockTitle, useStage, Warning } from './shared';

/** "BOARD_SUPPLIERS" → "Board Suppliers", for a category the directory has not named (or has not loaded yet). */
function humanise(code: string): string {
  return code.toLowerCase().split('_').filter(Boolean).map((word) => word[0].toUpperCase() + word.slice(1)).join(' ');
}

function useCategoryNames(): (code: string) => string {
  const { directory } = useWorkflow();
  const { request } = directory;
  useEffect(() => { request(); }, [request]);
  return (code) => directory.categories?.find((category) => category.code === code)?.name ?? humanise(code);
}

/** The stage that selects vendors, named for the "Select them in …" pointers. */
function useSelectionStageTitle(): string {
  const { stages } = useWorkflow();
  return stages.find((stage) => stage.spec.blocks.some((block) => block.type === 'VENDOR_SELECTION'))?.spec.title ?? 'Production Planning';
}

/** One vendor: initials, name, contact person and phone (a call link), its type, and its own group chat. */
export function VendorRow({ vendor, typeLabel }: { vendor: VendorSummary; typeLabel?: string }) {
  const href = safeInviteHref(vendor.whatsappGroupUrl);
  const phone = vendor.phone?.trim();
  return (
    <li className="ck-wf-vendor-row">
      <span className="ck-wf-avatar" aria-hidden="true">{initials(vendor.name)}</span>
      <div className="ck-wf-vendor-who">
        <div className="ck-wf-strong">
          {vendor.name}
          {vendor.sample && <Badge>Sample</Badge>}
          {!vendor.active && <Badge tone="warning">Inactive</Badge>}
        </div>
        <div className="ck-wf-muted">
          {[vendor.contactPerson?.trim(), typeLabel].filter(Boolean).join(' · ')}
          {phone && <>{(vendor.contactPerson?.trim() || typeLabel) ? ' · ' : ''}<a href={`tel:${phone.replace(/[^\d+]/g, '')}`}><Phone size={12} aria-hidden="true" /> {phone}</a></>}
        </div>
      </div>
      {href
        ? <a className="ck-btn ck-btn-ghost ck-btn-sm ck-wf-btn-link" href={href} target="_blank" rel="noopener noreferrer">Open group chat <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> {vendor.name} (opens in a new tab)</span></a>
        : <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" disabled title="This vendor has no group chat saved">Open group chat ↗<span className="ck-sr-only"> — no group chat saved</span></button>}
    </li>
  );
}

/** The categories a card shows: the template's list, else every active category with this item's suggestions first. */
function categoryPlan(block: BlockSpec, itemCode: string, categories: VendorCategoryView[] | null): { main: string[]; more: string[] } {
  if (block.categories?.length) return { main: block.categories, more: [] };
  const all = (categories ?? []).filter((category) => category.active).map((category) => category.code);
  const suggested = (block.suggestedByItem?.[itemCode] ?? []).filter((code) => all.includes(code) || !categories);
  if (!suggested.length) return { main: [], more: all };
  return { main: suggested, more: all.filter((code) => !suggested.includes(code)) };
}

function DirectoryStatus() {
  const { directory } = useWorkflow();
  if (directory.error) {
    return (
      <div className="ck-alert ck-alert-re" role="alert">
        <div>{directory.error} <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={directory.retry}>Retry</button></div>
      </div>
    );
  }
  if (!directory.vendors) return <p className="ck-wf-muted" role="status">Loading the vendor directory…</p>;
  return null;
}

/**
 * Supplier Contact Groups: every active vendor of the card's categories, one sub-card per category. A card with neither
 * its own categories nor suggestions (the 9-step templates) takes the item's suggestions from the stage's "Select Vendors"
 * card, so the suggested categories are listed first and open there too (D33, section 2.7 Step 5).
 */
export function VendorDirectoryBlock({ block }: { block: BlockSpec }) {
  const { view, directory } = useWorkflow();
  const { stage } = useStage();
  const name = useCategoryNames();
  const ownPlan = !!block.categories?.length || !!block.suggestedByItem;
  const suggestions = ownPlan ? block : {
    ...block,
    suggestedByItem: stage.spec.blocks.find((item) => item.type === 'VENDOR_SELECTION')?.suggestedByItem ?? null,
  };
  const plan = categoryPlan(suggestions, view.itemCode, directory.categories);
  const card = (code: string) => {
    const vendors = (directory.vendors ?? []).filter((vendor) => vendor.active && vendor.categories.includes(code));
    return (
      <div key={code} className="ck-wf-subcard">
        <h5>{name(code)}</h5>
        {vendors.length
          ? <ul className="ck-wf-vendor-list">{vendors.map((vendor) => <VendorRow key={vendor.id} vendor={vendor} typeLabel={name(code)} />)}</ul>
          : <p className="ck-wf-muted">No vendors in {name(code)} yet.</p>}
      </div>
    );
  };
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>Vendor directory</Badge>}>
      <DirectoryStatus />
      {directory.vendors && (
        <>
          {plan.main.length > 0 && <div className="ck-wf-subgrid">{plan.main.map(card)}</div>}
          {plan.more.length > 0 && (
            <details className="ck-wf-more">
              <summary>{plan.main.length ? 'Other categories' : 'All categories'} ({plan.more.length})</summary>
              <div className="ck-wf-subgrid">{plan.more.map(card)}</div>
            </details>
          )}
        </>
      )}
    </BlockCard>
  );
}

/** The later stages whose cards show only the selected vendors, named in the selection card's banner. */
function useDownstreamStages(): string[] {
  const { stages } = useWorkflow();
  const selection = stages.findIndex((stage) => stage.spec.blocks.some((block) => block.type === 'VENDOR_SELECTION'));
  return stages.slice(selection + 1).filter((stage) => stage.spec.blocks.some((block) => block.type === 'VENDOR_GROUPS')).map((stage) => stage.spec.title);
}

function selectionsFrom(source: Record<string, number[]>): Record<string, number[]> {
  return Object.fromEntries(Object.entries(source).map(([code, ids]) => [code, [...ids]]));
}

/** A selection's canonical form: categories with at least one vendor, in order, each with its ids in order. */
function selectionKey(source: Record<string, number[]>): string {
  return JSON.stringify(Object.entries(source)
    .filter(([, ids]) => ids.length)
    .map(([code, ids]) => [code, [...ids].sort((left, right) => left - right)])
    .sort(([left], [right]) => String(left).localeCompare(String(right))));
}

/**
 * Select Vendors for This Order (D33): checkboxes per category, the item's suggested categories first and open. Saving replaces
 * every selection at once; later stages then show only the vendors chosen here.
 */
export function VendorSelectionBlock({ block }: { block: BlockSpec }) {
  const { view, directory, mutate, busy } = useWorkflow();
  const name = useCategoryNames();
  const downstream = useDownstreamStages();
  // Unsaved ticks survive any other change to the order (a checklist tick saves at once); they give way only when the
  // saved selection itself changes, by this card's save or someone else's.
  const savedKey = selectionKey(view.vendorSelections);
  const [selected, setSelected] = useServerDraft(selectionsFrom(view.vendorSelections), savedKey);
  const plan = categoryPlan(block, view.itemCode, directory.categories);
  const fixed = !!block.categories?.length;
  const editable = view.can.canSelectVendors;
  const known = useMemo(() => new Map(view.groups.vendors.map((vendor) => [vendor.id, vendor])), [view.groups.vendors]);
  const dirty = selectionKey(selected) !== savedKey;

  const toggle = (code: string, id: number, on: boolean) => setSelected((current) => {
    const ids = current[code] ?? [];
    return { ...current, [code]: on ? [...new Set([...ids, id])] : ids.filter((item) => item !== id) };
  });
  const save = () => {
    const selections = Object.fromEntries(Object.entries(selected).filter(([, ids]) => ids.length));
    void mutate(() => workflowApi.setVendorSelections(view.orderId, selections, view.version), 'Vendor selections saved');
  };
  const options = (code: string) => {
    const active = (directory.vendors ?? []).filter((vendor) => vendor.active && vendor.categories.includes(code));
    // A vendor selected before it was deactivated stays on the order, marked Inactive (D32).
    const kept = (view.vendorSelections[code] ?? []).map((id) => known.get(id)).filter((vendor): vendor is VendorSummary => !!vendor && !active.some((item) => item.id === vendor.id));
    return [...active, ...kept];
  };
  const fieldset = (code: string) => {
    const vendors = options(code);
    const ids = selected[code] ?? [];
    return (
      <fieldset key={code} className="ck-wf-subcard">
        <legend>{name(code)} {ids.length > 0 && <span className="ck-wf-muted">· {ids.length} selected</span>}</legend>
        {vendors.length === 0 ? <p className="ck-wf-muted">No vendors in {name(code)} yet.</p> : (
          <ul className="ck-wf-options">
            {vendors.map((vendor) => (
              <li key={vendor.id}>
                <label>
                  <input type="checkbox" checked={ids.includes(vendor.id)} disabled={!editable} onChange={(e) => toggle(code, vendor.id, e.target.checked)} />
                  <span>{vendor.name}{vendor.sample ? ' (Sample)' : ''}{!vendor.active ? ' (Inactive)' : ''}</span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
    );
  };
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge tone="primary">Selections control later stages</Badge>}>
      <p className="ck-wf-muted">
        {fixed
          ? `Tick only the ${plan.main.map((code) => name(code).toLowerCase()).join(', ')} assigned to this order. Only selected vendors will appear in the relevant downstream stages.`
          : 'Tick only the vendors assigned to this order. Only selected vendors will appear in the relevant downstream stages.'}
      </p>
      <DirectoryStatus />
      {directory.vendors && (
        <>
          {plan.main.length > 0 && <div className="ck-wf-subgrid">{plan.main.map(fieldset)}</div>}
          {plan.more.length > 0 && (
            <details className="ck-wf-more">
              <summary>{plan.main.length ? 'Other categories' : 'All categories'} ({plan.more.length})</summary>
              <div className="ck-wf-subgrid">{plan.more.map(fieldset)}</div>
            </details>
          )}
        </>
      )}
      {editable
        ? <div className="ck-wf-block-actions"><button type="button" className="ck-btn ck-btn-g" disabled={busy || !dirty} onClick={save}>Save vendor selections</button></div>
        : <p className="ck-wf-muted">Vendors are chosen by this stage&apos;s assignee while it is in progress, or by a superadmin.</p>}
      {downstream.length > 0 && (
        <Warning>Selected vendors will be filtered in {downstream.slice(0, 3).join(', ')}{downstream.length > 3 ? ' and subsequent stages' : ''}.</Warning>
      )}
    </BlockCard>
  );
}

/** A later stage's vendor card: only the vendors this order selected, with the amber warning when none are. */
export function VendorGroupsBlock({ block }: { block: BlockSpec }) {
  const { view } = useWorkflow();
  const name = useCategoryNames();
  const selectionStage = useSelectionStageTitle();
  const byId = new Map(view.groups.vendors.map((vendor) => [vendor.id, vendor]));
  const pick = (code: string) => (view.vendorSelections[code] ?? []).map((id) => byId.get(id)).filter((vendor): vendor is VendorSummary => !!vendor);
  const fixed = block.categories ?? [];

  if (!fixed.length) {
    const groups = Object.keys(view.vendorSelections).map((code) => ({ code, vendors: pick(code) })).filter((group) => group.vendors.length);
    return (
      <BlockCard title={blockTitle(block)} badge={<Badge>From {selectionStage}</Badge>}>
        {groups.length === 0
          ? <Warning>No vendors selected for this order. Select them in {selectionStage} to show their group chats here.</Warning>
          : (
            <div className="ck-wf-subgrid">
              {groups.map((group) => (
                <div key={group.code} className="ck-wf-subcard">
                  <h5>{name(group.code)}</h5>
                  <ul className="ck-wf-vendor-list">{group.vendors.map((vendor) => <VendorRow key={vendor.id} vendor={vendor} typeLabel={name(group.code)} />)}</ul>
                </div>
              ))}
            </div>
          )}
      </BlockCard>
    );
  }
  const single = fixed.length === 1;
  return (
    <BlockCard title={blockTitle(block)} badge={<Badge>From {selectionStage}</Badge>}>
      <div className={single ? undefined : 'ck-wf-subgrid'}>
        {fixed.map((code) => {
          const vendors = pick(code);
          const body = vendors.length
            ? <ul className="ck-wf-vendor-list">{vendors.map((vendor) => <VendorRow key={vendor.id} vendor={vendor} typeLabel={name(code)} />)}</ul>
            : <Warning>No {name(code)} selected for this order. Select them in {selectionStage} to show their group chats here.</Warning>;
          return single ? <div key={code}>{body}</div> : <div key={code} className="ck-wf-subcard"><h5>{name(code)}</h5>{body}</div>;
        })}
      </div>
    </BlockCard>
  );
}
