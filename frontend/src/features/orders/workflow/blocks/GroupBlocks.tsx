import { useEffect, useRef, useState } from 'react';
import { Copy, ExternalLink, House } from 'lucide-react';
import { Field } from '../../../../pages/workspace/ui';
import { INVALID_INVITE_MESSAGE, fillIntroMessage, hasUnfilledPlaceholders, normaliseInvite, safeInviteHref } from '../../shared/invite';
import { orderHeadline } from '../../shared/orderLabel';
import { workflowApi } from '../api';
import { useWorkflow } from '../context';
import { checkedItems } from '../model';
import type { BlockSpec } from '../types';
import { BlockCard, blockTitle, useStage, Warning } from './shared';

function SchoolChip() {
  const { view } = useWorkflow();
  return (
    <div className="ck-wf-chip-row">
      <span className="ck-wf-avatar is-square" aria-hidden="true"><House size={16} /></span>
      <div>
        <div className="ck-wf-strong">{view.schoolName ?? `School ${view.schoolId}`}</div>
        <div className="ck-wf-muted">Order ID: {orderHeadline(view)}</div>
      </div>
    </div>
  );
}

/** "Open <School> Group Chat ↗": the order's resolved school group (item link, else school link), opened in a new tab. */
export function OpenSchoolGroup({ primary = true }: { primary?: boolean }) {
  const { view } = useWorkflow();
  const href = safeInviteHref(view.groups.school.url);
  const school = view.schoolName ?? 'school';
  const label = `Open ${school} Group Chat`;
  if (!href) return <button type="button" className="ck-btn ck-btn-ghost" disabled title="No group chat saved for this school">{label} ↗</button>;
  return (
    <a className={`ck-btn ${primary ? 'ck-btn-g' : 'ck-btn-ghost'} ck-wf-btn-link`} href={href} target="_blank" rel="noopener noreferrer">
      {label} <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> (opens in a new tab)</span>
    </a>
  );
}

/**
 * The item's own school group (D34). When the school has no group chat, superadmin or the stage's assignee may save one
 * for this order's item right here. When the item already has its own group, the same people may change or remove it;
 * it then applies to every order of this item at the school, and removing it shows the school's group again. School-wide
 * groups stay in Workflow set-up → Groups.
 */
function SchoolGroupLinkEditor() {
  const { view, user, run } = useWorkflow();
  const [text, setText] = useState('');
  const [touched, setTouched] = useState(false);
  const [editing, setEditing] = useState(false);
  const [removed, setRemoved] = useState(false);
  // Opening, closing, saving and removing each unmount the focused control; this says where the keyboard goes next.
  const [focusNext, setFocusNext] = useState<'input' | 'toggle' | 'afterRemove' | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const noteRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!focusNext) return;
    const target = focusNext === 'input' ? inputRef.current : focusNext === 'toggle' ? toggleRef.current : noteRef.current ?? inputRef.current;
    target?.focus();
    setFocusNext(null);
  }, [focusNext]);
  const resolved = safeInviteHref(view.groups.school.url);
  const itemOwn = resolved !== null && view.groups.school.source === 'ITEM';
  const canSet = view.can.canSetSchoolGroup;
  if (resolved && !itemOwn) {
    return removed
      ? <p ref={noteRef} tabIndex={-1} className="ck-wf-muted">The item&apos;s own group was removed; this order uses the school&apos;s group again.</p>
      : null;
  }
  if (!resolved && !canSet) return <Warning>No group chat saved for this school yet.</Warning>;

  const invite = normaliseInvite(text);
  const close = () => { setText(''); setTouched(false); setEditing(false); };
  const save = async () => {
    setTouched(true);
    if (!invite) return;
    if (await run(() => workflowApi.setSchoolGroup(view.orderId, invite), 'Group link saved')) { close(); setRemoved(false); setFocusNext('toggle'); }
  };
  const remove = async () => {
    if (await run(() => workflowApi.setSchoolGroup(view.orderId, ''), "Item's group removed")) { close(); setRemoved(true); setFocusNext('afterRemove'); }
  };
  const form = (
    <>
      <Field label={itemOwn ? 'New group link' : 'Save group link'} error={touched && !invite ? INVALID_INVITE_MESSAGE : undefined}>
        <input ref={inputRef} type="url" value={text} placeholder="https://chat.whatsapp.com/…" onChange={(e) => setText(e.target.value)} onBlur={() => setTouched(!!text)} />
      </Field>
      <div className="ck-wf-block-actions">
        <button type="button" className="ck-btn ck-btn-ghost" disabled={!text.trim()} onClick={() => void save()}>Save group link</button>
        {itemOwn && <button type="button" className="ck-btn ck-btn-ghost" onClick={() => void remove()}>Remove item&apos;s group</button>}
        {itemOwn && <button type="button" className="ck-btn ck-btn-ghost" onClick={() => { close(); setFocusNext('toggle'); }}>Cancel</button>}
      </div>
    </>
  );

  if (itemOwn) {
    return (
      <div className="ck-wf-inline-form">
        <p className="ck-wf-muted">This is the item&apos;s own group at this school; it replaces the school group on every order of this item here.</p>
        {canSet && !editing && (
          <button ref={toggleRef} type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => { setEditing(true); setFocusNext('input'); }}>
            Change item&apos;s group
          </button>
        )}
        {canSet && editing && form}
      </div>
    );
  }
  return (
    <div className="ck-wf-inline-form">
      <Warning>
        No group chat saved for this school yet. Paste the group&apos;s invite link to save it for this order&apos;s item
        {user.superadmin ? ', or add one for the whole school in Workflow set-up → Groups.' : '.'}
      </Warning>
      {form}
    </div>
  );
}

export function SchoolGroupBlock({ block }: { block: BlockSpec }) {
  return (
    <BlockCard title={blockTitle(block)}>
      <SchoolChip />
      <div className="ck-wf-block-actions"><OpenSchoolGroup /></div>
      <p className="ck-wf-muted">Opens the WhatsApp group mapped to this order&apos;s school in a new tab.</p>
      <SchoolGroupLinkEditor />
    </BlockCard>
  );
}

/**
 * The field that keeps the message as sent (D28: who, when and the final text): the one TEXT field the introduction card
 * names in its `fields`, as the server's template loader requires. A card that names none falls back to a stage field
 * whose key mentions the introduction (the templates before the card named it).
 */
function introTextField(block: BlockSpec, fields: Record<string, { kind: string }>): string | undefined {
  const named = block.fields?.find((key) => fields[key]?.kind === 'TEXT');
  return named ?? Object.keys(fields).find((key) => /intro/i.test(key) && ['TEXT', 'WORDS'].includes(fields[key].kind));
}

/**
 * The introduction message (D28): "Your name" fills {Name} as you type; the message stays editable, and once edited by hand
 * it is the operator's own until they reset it. Nothing is sent by the system: the operator copies the text, opens the
 * school's group and sends it there, then ticks the item that records it.
 */
export function IntroMessageBlock({ block }: { block: BlockSpec }) {
  const { view, template, user, toast } = useWorkflow();
  const { stage, editable, saveData } = useStage();
  const [name, setName] = useState(user.fullName);
  const [edited, setEdited] = useState<string | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const filled = fillIntroMessage(template.introMessage ?? '', { name, itemNoun: view.itemNoun });
  const message = edited ?? filled;
  const checklistKey = block.checklist ?? '';
  const checklist = stage.spec.checklists[checklistKey];
  const checked = checkedItems(stage.view.data, checklistKey);
  const textField = introTextField(block, stage.spec.fields);
  // The final text is saved into that field, so the box stops at its limit rather than have the tick refused.
  const textMax = textField && stage.spec.fields[textField].kind === 'TEXT' ? stage.spec.fields[textField].max ?? undefined : undefined;

  const copy = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('no clipboard');
      await navigator.clipboard.writeText(message);
      toast('Message copied');
    } catch {
      area.current?.focus();
      area.current?.select();
      toast('Message selected. Press Ctrl+C to copy it.', false);
    }
  };
  const tick = (index: number, on: boolean) => {
    const next = on ? [...checked, index].sort((a, b) => a - b) : checked.filter((item) => item !== index);
    const data: Record<string, unknown> = { [checklistKey]: next };
    if (on && textField) data[textField] = message;
    void saveData(data, on ? 'Introduction message recorded' : 'Checklist updated');
  };

  return (
    <BlockCard title={blockTitle(block)}>
      <SchoolChip />
      <div className="ck-wf-fields">
        <Field label="Your name">
          <input type="text" value={name} placeholder="Enter your name" maxLength={200} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Message template" hint={hasUnfilledPlaceholders(message) ? 'Enter your name to fill {Name} before copying.' : undefined}>
          <textarea ref={area} rows={4} className="ck-wf-resizable" value={message} maxLength={textMax} onChange={(e) => setEdited(e.target.value)} />
        </Field>
      </div>
      <div className="ck-wf-block-actions">
        <button type="button" className="ck-btn ck-btn-ghost" onClick={() => void copy()}><Copy size={14} aria-hidden="true" /> Copy message</button>
        <OpenSchoolGroup />
        {edited !== null && <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => setEdited(null)}>Reset to template</button>}
      </div>
      <p className="ck-wf-muted">Copy the message, then open the mapped school WhatsApp group in a new tab to send it.</p>
      <SchoolGroupLinkEditor />
      {checklist && (
        <fieldset className="ck-wf-checklist is-inline">
          <legend className="ck-sr-only">Introduction checklist</legend>
          <ul>
            {checklist.items.map((item, index) => (
              <li key={`${index}-${item}`}>
                <label>
                  <input type="checkbox" checked={checked.includes(index)} disabled={!editable} onChange={(e) => tick(index, e.target.checked)} />
                  <span>{item}</span>
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
      )}
    </BlockCard>
  );
}

/** The internal approval group (D34): one global link, overridden per school where one is saved. */
export function InternalApprovalGroupBlock({ block }: { block: BlockSpec }) {
  const { view } = useWorkflow();
  const href = safeInviteHref(view.groups.internalApproval.url);
  return (
    <BlockCard title={blockTitle(block)}>
      <div className="ck-wf-vendor-row">
        <span className="ck-wf-avatar" aria-hidden="true">IA</span>
        <div className="ck-wf-vendor-who">
          <div className="ck-wf-strong">Internal Approval Group</div>
          <div className="ck-wf-muted">Internal review and approval</div>
        </div>
        {href
          ? <a className="ck-btn ck-btn-ghost ck-btn-sm ck-wf-btn-link" href={href} target="_blank" rel="noopener noreferrer">Open group chat <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> Internal Approval Group (opens in a new tab)</span></a>
          : <span className="ck-wf-muted">No internal approval group saved yet.</span>}
      </div>
    </BlockCard>
  );
}
