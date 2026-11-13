import { ExternalLink } from 'lucide-react';
import { Field } from '../../../../pages/workspace/ui';
import { EVIDENCE_LINK_MAX_CHARS, safeEvidenceHref } from '../../shared/invite';
import { WORD_LIMITED_MAX_CHARS, wordLimit } from '../../shared/words';
import { useWorkflow } from '../context';
import { valueText } from '../model';
import type { BlockSpec, FieldSpec } from '../types';
import { BlockCard, blockTitle, useStage } from './shared';

/** A typed value the server would refuse, said under the field before Save is offered. */
export function fieldError(spec: FieldSpec, text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';
  switch (spec.kind) {
    case 'URL':
      if (trimmed.length > EVIDENCE_LINK_MAX_CHARS) return `Use a link of at most ${EVIDENCE_LINK_MAX_CHARS.toLocaleString('en-IN')} characters.`;
      return safeEvidenceHref(trimmed) ? '' : 'Paste a full https:// link.';
    case 'WORDS': {
      const limit = wordLimit(text, spec.max ?? 100);
      if (limit.over) return `Use at most ${limit.max} words.`;
      return text.length > WORD_LIMITED_MAX_CHARS ? `Use at most ${WORD_LIMITED_MAX_CHARS.toLocaleString('en-IN')} characters.` : '';
    }
    case 'TEXT':
      return spec.max != null && text.length > spec.max ? `Use at most ${spec.max.toLocaleString('en-IN')} characters.` : '';
    case 'WHOLE':
    case 'NUMBER': {
      const number = Number(trimmed);
      if (!Number.isFinite(number) || (spec.kind === 'WHOLE' && !Number.isInteger(number))) return spec.kind === 'WHOLE' ? 'Enter a whole number.' : 'Enter a number.';
      if (spec.min != null && number < spec.min) return `Enter ${spec.min} or more.`;
      if (spec.max != null && number > spec.max) return `Enter ${spec.max} or less.`;
      return '';
    }
    case 'DATE':
      return /^\d{4}-\d{2}-\d{2}$/.test(trimmed) ? '' : 'Enter a date.';
    case 'CHOICE':
      return spec.choices?.some(([value]) => value === trimmed) ? '' : 'Choose one of the options.';
    default:
      return '';
  }
}

/** One template field, labelled and described as the template says, read-only unless the stage may be edited. */
export function FieldInput({ name, spec }: { name: string; spec: FieldSpec }) {
  const { stage, editable, draft, setDraft } = useStage();
  const saved = valueText(stage.view.data[name]);
  const text = draft[name] ?? saved;
  const error = fieldError(spec, text);
  const readOnly = !editable || spec.legacy;
  const onChange = (next: string) => setDraft(name, next);
  const placeholder = spec.placeholder ?? undefined;
  const savedHref = spec.kind === 'URL' ? safeEvidenceHref(saved) : null;
  const counter = spec.kind === 'WORDS' ? wordLimit(text, spec.max ?? 100).label : undefined;

  let control;
  switch (spec.kind) {
    case 'CHOICE':
      control = (
        <select value={text} disabled={readOnly} onChange={(e) => onChange(e.target.value)}>
          <option value="">Select…</option>
          {(spec.choices ?? []).map(([value, label]) => <option key={value} value={value}>{label ?? value}</option>)}
        </select>
      );
      break;
    case 'WORDS':
      control = <textarea rows={4} value={text} placeholder={placeholder} readOnly={readOnly} maxLength={WORD_LIMITED_MAX_CHARS} onChange={(e) => onChange(e.target.value)} />;
      break;
    case 'TEXT':
      control = (spec.max ?? 1000) > 200
        ? <textarea rows={3} value={text} placeholder={placeholder} readOnly={readOnly} maxLength={spec.max ?? undefined} onChange={(e) => onChange(e.target.value)} />
        : <input type="text" value={text} placeholder={placeholder} readOnly={readOnly} maxLength={spec.max ?? undefined} onChange={(e) => onChange(e.target.value)} />;
      break;
    case 'URL':
      control = <input type="url" inputMode="url" value={text} placeholder={placeholder} readOnly={readOnly} maxLength={EVIDENCE_LINK_MAX_CHARS} onChange={(e) => onChange(e.target.value)} />;
      break;
    case 'DATE':
      control = <input type="date" value={text} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} />;
      break;
    default:
      control = (
        <input type="number" inputMode={spec.kind === 'WHOLE' ? 'numeric' : 'decimal'} step={spec.kind === 'WHOLE' ? 1 : 'any'}
          min={spec.min ?? undefined} max={spec.max ?? undefined} value={text} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} />
      );
  }
  return (
    <div className="ck-wf-field">
      <Field label={spec.label} error={error || undefined} hint={counter}>{control}</Field>
      {savedHref && <a className="ck-wf-link" href={savedHref} target="_blank" rel="noopener noreferrer">Open <ExternalLink size={13} aria-hidden="true" /><span className="ck-sr-only"> {spec.label} (opens in a new tab)</span></a>}
    </div>
  );
}

/** The fields a FIELDS (or ORDER_PDF_LINK) card edits, and its card-level save button labelled as the template says. */
export function FieldsBlock({ block, defaultSave = 'Save details' }: { block: BlockSpec; defaultSave?: string }) {
  const { stage, editable, draft, saveFields } = useStage();
  const { busy } = useWorkflow();
  const keys = (block.fields ?? []).filter((key) => stage.spec.fields[key]);
  if (!keys.length) return null;
  const changed = keys.filter((key) => key in draft && draft[key] !== valueText(stage.view.data[key]));
  const invalid = keys.some((key) => fieldError(stage.spec.fields[key], draft[key] ?? valueText(stage.view.data[key])));
  const title = blockTitle(block);
  const label = block.save?.trim() || defaultSave;
  const save = () => void saveFields(changed, `${title ?? 'Details'} saved`);
  return (
    <BlockCard title={keys.length === 1 && title === stage.spec.fields[keys[0]].label ? null : title}>
      <div className={`ck-wf-fields${keys.length > 1 ? ' is-grid' : ''}`}>
        {keys.map((key) => <FieldInput key={key} name={key} spec={stage.spec.fields[key]} />)}
      </div>
      {editable && keys.some((key) => !stage.spec.fields[key].legacy) && (
        <div className="ck-wf-block-actions">
          <button type="button" className="ck-btn ck-btn-g" disabled={busy || !changed.length || invalid} onClick={save}>{label}</button>
        </div>
      )}
    </BlockCard>
  );
}
