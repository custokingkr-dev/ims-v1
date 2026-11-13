import { useId, useState } from 'react';
import { Modal } from '../../../components/Modal';
import { Field } from '../../../pages/workspace/ui';
import type { VendorCategoryView, VendorRequest, VendorView } from './api';
import {
  EMPTY_VENDOR_DRAFT, VENDOR_LIMITS, draftFromVendor, requestFromDraft, validateVendorDraft, type VendorDraft, type VendorErrors,
} from './vendorRules';

/**
 * Add or edit one vendor (design section 4.5). Errors show beside their fields; nothing is sent until the draft is valid.
 * Unticking Active on an active vendor turns Save into an explicit "Deactivate vendor" with what that means, so a vendor
 * is never deactivated by accident.
 */
export function VendorForm({ vendor, categories, presetCategory, busy, serverError, onSave, onClose }: {
  /** The vendor being edited, or null to add one. */
  vendor: VendorView | null;
  categories: VendorCategoryView[];
  /** Ticked when adding from an empty category. */
  presetCategory?: string | null;
  busy: boolean;
  serverError: string;
  onSave: (request: VendorRequest) => void;
  onClose: () => void;
}) {
  const ids = useId();
  const [draft, setDraft] = useState<VendorDraft>(() => vendor
    ? draftFromVendor(vendor)
    : { ...EMPTY_VENDOR_DRAFT, categories: presetCategory ? [presetCategory] : [] });
  const [errors, setErrors] = useState<VendorErrors>({});
  const [tried, setTried] = useState(false);
  const sample = vendor?.sample === true;
  const deactivating = vendor?.active === true && !draft.active;

  const update = (patch: Partial<VendorDraft>) => {
    const next = { ...draft, ...patch };
    setDraft(next);
    // Once Save has been pressed, errors follow the typing so a fixed field clears straight away.
    if (tried) setErrors(validateVendorDraft(next, { sample }));
  };
  const toggleCategory = (code: string, checked: boolean) =>
    update({ categories: checked ? [...draft.categories, code] : draft.categories.filter((item) => item !== code) });
  const save = () => {
    setTried(true);
    const found = validateVendorDraft(draft, { sample });
    setErrors(found);
    if (Object.keys(found).length === 0) onSave(requestFromDraft(draft));
  };

  // Inactive categories are offered only when the vendor already belongs to one, so saving never drops it silently.
  const offered = categories.filter((category) => category.active || draft.categories.includes(category.code));
  const categoryErrorId = `${ids}-categories-error`;
  const title = vendor ? `Edit ${vendor.name}` : 'Add vendor';

  return (
    <Modal title={title} subtitle={sample ? 'Sample vendor from the prototype. Add a phone number to make it a real supplier.' : undefined}
      disabled={busy} onClose={onClose} footer={<>
        <button type="button" className="ck-btn ck-btn-ghost" disabled={busy} onClick={onClose}>Cancel</button>
        <button type="button" className={`ck-btn ${deactivating ? 'ck-btn-re' : 'ck-btn-g'}`} disabled={busy} onClick={save}>
          {busy ? 'Saving…' : deactivating ? 'Deactivate vendor' : vendor ? 'Save vendor' : 'Add vendor'}
        </button>
      </>}>
      <div className="ck-vd-form">
        {serverError && <div className="ck-alert ck-alert-re" role="alert">{serverError}</div>}
        <div className="ck-vd-form-grid">
          <Field label="Name *" error={errors.name}>
            <input value={draft.name} autoComplete="organization" onChange={(e) => update({ name: e.target.value })} />
          </Field>
          <Field label="Contact person" error={errors.contactPerson}>
            <input value={draft.contactPerson} autoComplete="name" onChange={(e) => update({ contactPerson: e.target.value })} />
          </Field>
          <Field label={sample ? 'Phone' : 'Phone *'} error={errors.phone}>
            <input type="tel" value={draft.phone} autoComplete="tel" onChange={(e) => update({ phone: e.target.value })} />
          </Field>
          <Field label="Alternate phone" error={errors.altPhone}>
            <input type="tel" value={draft.altPhone} onChange={(e) => update({ altPhone: e.target.value })} />
          </Field>
          <Field label="Email" error={errors.email}>
            <input type="email" value={draft.email} autoComplete="email" onChange={(e) => update({ email: e.target.value })} />
          </Field>
          <Field label="City" error={errors.city}>
            <input value={draft.city} autoComplete="address-level2" onChange={(e) => update({ city: e.target.value })} />
          </Field>
          <Field label="WhatsApp group invite" error={errors.whatsappGroupUrl} hint="The vendor's own group, opened from the order's stage cards.">
            <input type="url" inputMode="url" placeholder="https://chat.whatsapp.com/…" value={draft.whatsappGroupUrl}
              onChange={(e) => update({ whatsappGroupUrl: e.target.value })} />
          </Field>
          <Field label="Notes" error={errors.notes} hint={`${draft.notes.length}/${VENDOR_LIMITS.notes} characters`}>
            <textarea rows={3} value={draft.notes} onChange={(e) => update({ notes: e.target.value })} />
          </Field>
        </div>
        <fieldset className="ck-vd-form-categories" aria-describedby={errors.categories ? categoryErrorId : undefined}
          aria-invalid={errors.categories ? true : undefined}>
          <legend>Categories *</legend>
          <div className="ck-vd-category-options">
            {offered.map((category) => (
              <label key={category.code} className="ck-vd-check">
                <input type="checkbox" checked={draft.categories.includes(category.code)}
                  onChange={(e) => toggleCategory(category.code, e.target.checked)} />
                <span>{category.name}{category.active ? '' : ' (inactive)'}</span>
              </label>
            ))}
          </div>
          {errors.categories && <p id={categoryErrorId} className="ck-vd-error" role="alert">{errors.categories}</p>}
        </fieldset>
        <label className="ck-vd-check">
          <input type="checkbox" checked={draft.active} onChange={(e) => update({ active: e.target.checked })} />
          <span>Active</span>
        </label>
        {deactivating && (
          <div className="ck-alert ck-alert-am" role="status">
            <div>
              <strong>Deactivate {vendor?.name}?</strong>
              It will no longer be offered when vendors are chosen. Orders that already chose it keep it, marked “Inactive”.
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
