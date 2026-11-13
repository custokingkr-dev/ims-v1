import { useRef, useState } from 'react';
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
 * What the screen offers when the school has no group chat (D34): the stage's assignee may save the item's group link
 * right here; superadmin is pointed at the set-up screen, where school-wide links live.
 */
function MissingSchoolGroup() {
  const { view, user, run } = useWorkflow();
  const [text, setText] = useState('');
  const [touched, setTouched] = useState(false);
  if (safeInviteHref(view.groups.school.url)) return null;
  if (user.superadmin) return <Warning>No group chat saved for this school. Add it in Workflow set-up → Groups.</Warning>;
  if (!view.can.canSetSchoolGroup) return <Warning>No group chat saved for this school yet.</Warning>;
  const invite = normaliseInvite(text);
  const save = async () => {
    setTouched(true);
    if (!invite) return;
    if (await run(() => workflowApi.setSchoolGroup(view.orderId, invite), 'Group link saved')) { setText(''); setTouched(false); }
  };
  return (
    <div className="ck-wf-inline-form">
      <Warning>No group chat saved for this school yet. Paste the group's invite link to save it for this order's item.</Warning>
      <Field label="Save group link" error={touched && !invite ? INVALID_INVITE_MESSAGE : undefined}>
        <input type="url" value={text} placeholder="https://chat.whatsapp.com/…" onChange={(e) => setText(e.target.value)} onBlur={() => setTouched(!!text)} />
      </Field>
      <button type="button" className="ck-btn ck-btn-ghost" disabled={!text.trim()} onClick={() => void save()}>Save group link</button>
    </div>
  );
}

export function SchoolGroupBlock({ block }: { block: BlockSpec }) {
  return (
    <BlockCard title={blockTitle(block)}>
      <SchoolChip />
      <div className="ck-wf-block-actions"><OpenSchoolGroup /></div>
      <p className="ck-wf-muted">Opens the WhatsApp group mapped to this order&apos;s school in a new tab.</p>
      <MissingSchoolGroup />
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
      <MissingSchoolGroup />
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
