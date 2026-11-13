import { useEffect, useMemo, useState } from 'react';
import { Phone } from 'lucide-react';
import { Field } from '../../../pages/workspace/ui';
import { CallLogPanel } from '../calls';
import { workflowApi, workflowError } from './api';
import { useWorkflow } from './context';
import { useServerDraft } from './hooks';
import type { DesignerRow, Stage } from './types';

function CallLink({ phone, who }: { phone: string | null | undefined; who: string }) {
  const number = phone?.trim();
  if (!number) return null;
  return (
    <a className="ck-btn ck-btn-ghost ck-btn-sm ck-wf-btn-link" href={`tel:${number.replace(/[^\d+]/g, '')}`}>
      <Phone size={13} aria-hidden="true" /> Call<span className="ck-sr-only"> {who} on {number}</span>
    </a>
  );
}

interface ContactForm { schoolContactName: string; schoolContactPhone: string; factoryContactName: string; factoryContactPhone: string }

/**
 * Contacts & follow-ups (D36): the order's school contact (its phone prefilled from the school record), the designer from the
 * directory, and the vendor or factory contact. Phones are call links.
 */
function ContactsCard() {
  const { view, summary, mutate } = useWorkflow();
  const saved: ContactForm = {
    schoolContactName: view.contacts.schoolContactName ?? '',
    schoolContactPhone: view.contacts.schoolContactPhone ?? summary?.schoolContactPhone ?? '',
    factoryContactName: view.contacts.factoryContactName ?? '',
    factoryContactPhone: view.contacts.factoryContactPhone ?? '',
  };
  const savedKey = JSON.stringify(saved);
  // Follows the saved contacts (another save, the school's phone arriving with the summary), but not a reload that leaves
  // them alone, such as the one after a call is logged or a designer is picked: typed, unsaved contacts stay.
  const [form, setForm] = useServerDraft<ContactForm>(saved, savedKey);
  const [designers, setDesigners] = useState<DesignerRow[] | null>(null);
  const [designerError, setDesignerError] = useState('');
  const canEdit = view.can.canSetContacts;
  const canPickDesigner = view.can.canSetDesigner;
  useEffect(() => {
    if (!canPickDesigner) return;
    workflowApi.designers().then((rows) => setDesigners(rows.filter((row) => row.active || row.id === view.designer?.id)),
      (e: unknown) => setDesignerError(workflowError(e, 'The designers could not be loaded.')));
  }, [canPickDesigner, view.designer?.id]);
  const set = (key: keyof ContactForm) => (value: string) => setForm((current) => ({ ...current, [key]: value }));
  const dirty = JSON.stringify(form) !== savedKey;
  const save = () => void mutate(() => workflowApi.setContacts(view.orderId, {
    schoolContactName: form.schoolContactName.trim() || null,
    schoolContactPhone: form.schoolContactPhone.trim() || null,
    factoryContactName: form.factoryContactName.trim() || null,
    factoryContactPhone: form.factoryContactPhone.trim() || null,
  }), 'Contacts saved');
  const pickDesigner = (id: string) => void mutate(() => workflowApi.setDesigner(view.orderId, id ? Number(id) : null), id ? 'Designer saved' : 'Designer cleared');

  return (
    <section className="ck-wf-block" aria-label="Contacts & follow-ups">
      <header className="ck-wf-block-h"><h4>Contacts &amp; follow-ups</h4></header>
      <div className="ck-wf-contacts">
        <fieldset className="ck-wf-subcard">
          <legend>School contact</legend>
          <Field label="School contact name"><input type="text" maxLength={200} value={form.schoolContactName} readOnly={!canEdit} onChange={(e) => set('schoolContactName')(e.target.value)} /></Field>
          <Field label="School contact phone"><input type="tel" maxLength={32} value={form.schoolContactPhone} readOnly={!canEdit} onChange={(e) => set('schoolContactPhone')(e.target.value)} /></Field>
          <CallLink phone={form.schoolContactPhone} who={form.schoolContactName || 'the school contact'} />
        </fieldset>
        <fieldset className="ck-wf-subcard">
          <legend>Designer</legend>
          {canPickDesigner ? (
            <Field label="Designer" error={designerError || undefined}>
              <select value={view.designer?.id ?? ''} disabled={!designers} onChange={(e) => pickDesigner(e.target.value)}>
                <option value="">{designers ? 'Not chosen' : 'Loading designers…'}</option>
                {(designers ?? []).map((designer) => <option key={designer.id} value={designer.id}>{designer.name}{designer.active ? '' : ' (inactive)'}</option>)}
              </select>
            </Field>
          ) : <p>{view.designer?.name ?? 'Not chosen'}</p>}
          {view.designer?.phone && <p className="ck-wf-muted">{view.designer.phone}</p>}
          <CallLink phone={view.designer?.phone} who={view.designer?.name ?? 'the designer'} />
        </fieldset>
        <fieldset className="ck-wf-subcard">
          <legend>Vendor / factory contact</legend>
          <Field label="Vendor / factory contact name"><input type="text" maxLength={200} value={form.factoryContactName} readOnly={!canEdit} onChange={(e) => set('factoryContactName')(e.target.value)} /></Field>
          <Field label="Vendor / factory contact phone"><input type="tel" maxLength={32} value={form.factoryContactPhone} readOnly={!canEdit} onChange={(e) => set('factoryContactPhone')(e.target.value)} /></Field>
          <CallLink phone={form.factoryContactPhone} who={form.factoryContactName || 'the vendor or factory'} />
        </fieldset>
      </div>
      {canEdit && <div className="ck-wf-block-actions"><button type="button" className="ck-btn ck-btn-g" disabled={!dirty} onClick={save}>Save contacts</button></div>}
    </section>
  );
}

/**
 * The School & vendor tab (section 4.3): contacts, then the call log with its history. A call belongs to the order's
 * current stage (D35), whichever stage is being viewed; a finished order's calls belong to the order.
 */
export function SchoolVendorTab({ stage }: { stage: Stage }) {
  const { view, callPreset, callLogged, goToStage, toast } = useWorkflow();
  const contacts = useMemo(() => {
    const contact = (name: string | null | undefined, phone: string | null | undefined, id?: number) =>
      (name?.trim() ? { name: name.trim(), phone: phone?.trim() || null, id: id ?? null } : undefined);
    return {
      school: contact(view.contacts.schoolContactName, view.contacts.schoolContactPhone),
      designer: contact(view.designer?.name, view.designer?.phone, view.designer?.id),
      factory: contact(view.contacts.factoryContactName, view.contacts.factoryContactPhone),
    };
  }, [view.contacts, view.designer]);
  return (
    <>
      <ContactsCard />
      {/* Not keyed by the preset: the panel applies a new preset itself, and a remount when the preset clears after a
          call is logged would drop its "Call logged" confirmation. */}
      <CallLogPanel
        orderId={view.orderId}
        currentStep={view.progress.currentStep ?? 0}
        contacts={contacts}
        vendors={view.groups.vendors}
        preset={callPreset ?? undefined}
        canLog={view.can.canLogCall}
        onLogged={callLogged}
        onToast={toast}
        onShowAll={() => goToStage(stage.view.step, 'ACTIVITY')}
      />
    </>
  );
}
