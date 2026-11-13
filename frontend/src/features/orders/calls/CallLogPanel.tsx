import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Phone, RefreshCw } from 'lucide-react';
import type { VendorSummary } from '../../../generated/orderWorkflowClient';
import { Field } from '../../../pages/workspace/ui';
import { workflowError } from '../shared/errors';
import { CALL_NOTES_MAX_WORDS, wordLimit } from '../shared/words';
import { callsApi, type CallLogEntry } from './api';
import {
  CONTACTED_OPTIONS, OTHER, OUTCOME_OPTIONS, callRequest, contactedLabel, defaultWho, followUpRequired, notesRequired,
  outcomeLabel, outcomeTone, validateCall, whoLabel, whoOptions, type CallDraft, type CallErrors, type Contact, type Contacted, type Outcome,
} from './callRules';
import './calls.css';

export interface CallLogPanelProps {
  orderId: string;
  currentStep: number;
  contacts: { school?: Contact; designer?: Contact; factory?: Contact };
  vendors: VendorSummary[];
  preset?: { contacted: Contacted; outcome: Outcome };
  canLog: boolean;
  onLogged(): void;
  /**
   * Where "Show all" goes. The order screen opens Activity history (design section 4.6), which lists every call with the
   * rest of the order's activity; without it, the panel lists every call itself.
   */
  onShowAll?(): void;
  /**
   * Shows the "Call logged" confirmation in the host's own toast (the order screen's, bottom right like its others).
   * Without it, the panel shows the confirmation in a status region of its own.
   */
  onToast?(text: string): void;
}

/** History shows this many calls until "Show all" is pressed. */
const RECENT = 10;
const ALL = 200;

const STAMP = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true });

/** "13 Nov, 4:00 pm" in the viewer's own zone, or '' for an unreadable time. */
export function formatCallTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : STAMP.format(date).replace(/\b(AM|PM)\b/, (half) => half.toLowerCase());
}

function newestFirst(entries: CallLogEntry[]): CallLogEntry[] {
  return [...entries].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt) || right.id - left.id);
}

/** "Log a call or discussion" and the order's call history (design section 4.6, D35), shown in the School & vendor tab. */
export function CallLogPanel({ orderId, currentStep, contacts, vendors, preset, canLog, onLogged, onShowAll, onToast }: CallLogPanelProps) {
  const ids = useId();
  const notesRef = useRef<HTMLTextAreaElement>(null);
  const draftFor = useCallback((start?: CallLogPanelProps['preset']): CallDraft => {
    const contacted = start?.contacted ?? 'SCHOOL';
    return { contacted, who: defaultWho(contacted, contacts, vendors), otherName: '', outcome: start?.outcome ?? 'CONNECTED', notes: '', nextFollowUpAt: '' };
  }, [contacts, vendors]);
  // Depends on the preset's values, not its identity: a host may pass a fresh object with the same values on each render.
  const initial = useCallback(() => draftFor(preset), [draftFor, preset?.contacted, preset?.outcome]); // eslint-disable-line react-hooks/exhaustive-deps
  const [draft, setDraft] = useState<CallDraft>(initial);
  const [tried, setTried] = useState(false);
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState('');
  const [toast, setToast] = useState('');
  const [history, setHistory] = useState<CallLogEntry[] | null>(null);
  const [historyError, setHistoryError] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [attempt, setAttempt] = useState(0);

  // A preset (School Feedback's "+ Add") fills the form and puts the cursor in the notes. It applies again whenever the host
  // offers one anew, including the same one after the host cleared it.
  const presetKey = preset ? `${preset.contacted}:${preset.outcome}` : '';
  const appliedPreset = useRef('');
  useEffect(() => {
    if (!presetKey) { appliedPreset.current = ''; return; }
    if (presetKey === appliedPreset.current) return;
    appliedPreset.current = presetKey;
    setDraft(initial()); setTried(false); setServerError('');
    notesRef.current?.focus();
  }, [presetKey, initial]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(''), 3500);
    return () => clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    let active = true;
    setHistoryError('');
    callsApi.list(orderId, showAll ? ALL : RECENT + 1).then(
      (entries) => { if (active) setHistory(newestFirst(entries)); },
      (error: unknown) => { if (active) setHistoryError(workflowError(error, 'Unable to load the call history.')); },
    );
    return () => { active = false; };
  }, [orderId, showAll, attempt]);

  const options = useMemo(() => whoOptions(draft.contacted, contacts, vendors), [draft.contacted, contacts, vendors]);
  // Contacts arrive with the order, so a pick that is no longer offered falls back to the first choice.
  const current: CallDraft = options.some((option) => option.key === draft.who) ? draft : { ...draft, who: options[0].key };
  // Errors show once Log call has been pressed, then follow the typing so a fixed field clears straight away.
  const shownErrors: CallErrors = tried ? validateCall(current, Date.now()) : {};
  const limit = wordLimit(draft.notes, CALL_NOTES_MAX_WORDS);

  const update = (patch: Partial<CallDraft>) => { setDraft((value) => ({ ...value, ...patch })); setServerError(''); };
  const changeContacted = (contacted: Contacted) => update({ contacted, who: defaultWho(contacted, contacts, vendors), otherName: '' });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setTried(true);
    if (Object.keys(validateCall(current, Date.now())).length > 0) return;
    setSaving(true); setServerError('');
    try {
      await callsApi.log(orderId, callRequest(current, { contacts, vendors, currentStep }));
      // A preset is for one call: the form starts clean for the next.
      setDraft(draftFor()); setTried(false);
      if (onToast) onToast('Call logged'); else setToast('Call logged');
      setAttempt((value) => value + 1);
      onLogged();
    } catch (error) {
      setServerError(workflowError(error, 'Unable to log the call.'));
    } finally {
      setSaving(false);
    }
  };

  const visible = history ? (showAll ? history : history.slice(0, RECENT)) : [];
  const more = !!history && !showAll && history.length > RECENT;
  const counterId = `${ids}-notes-counter`;

  return (
    <div className="ck-call">
      {canLog ? (
        <form className="ck-call-card" aria-labelledby={`${ids}-title`} onSubmit={(event) => void submit(event)} noValidate>
          <h3 id={`${ids}-title`}><Phone size={16} aria-hidden="true" /> Log a call or discussion</h3>
          <Field label="Contacted">
            <select value={draft.contacted} disabled={saving} onChange={(e) => changeContacted(e.target.value as Contacted)}>
              {CONTACTED_OPTIONS.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </Field>
          <Field label="Who">
            <select value={current.who} disabled={saving} onChange={(e) => update({ who: e.target.value })}>
              {options.map((option) => <option key={option.key} value={option.key}>{option.text}</option>)}
            </select>
          </Field>
          {current.who === OTHER && (
            <Field label="Name of the person *" error={shownErrors.otherName}>
              <input value={draft.otherName} disabled={saving} autoComplete="off" onChange={(e) => update({ otherName: e.target.value })} />
            </Field>
          )}
          <Field label="Outcome">
            <select value={draft.outcome} disabled={saving} onChange={(e) => update({ outcome: e.target.value as Outcome })}>
              {OUTCOME_OPTIONS.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </Field>
          <div className="ck-call-notes">
            <Field label={notesRequired(draft.outcome) ? 'Call notes *' : 'Call notes'} error={shownErrors.notes}>
              <textarea ref={notesRef} rows={4} value={draft.notes} disabled={saving}
                placeholder="Summary, commitment, next follow-up date..." aria-describedby={counterId}
                onChange={(e) => update({ notes: e.target.value })} />
            </Field>
            <span id={counterId} className={`ck-call-counter${limit.over ? ' is-over' : ''}`}>{limit.label}</span>
          </div>
          {followUpRequired(draft.outcome) && (
            <Field label="Next follow-up *" error={shownErrors.nextFollowUpAt}>
              <input type="datetime-local" value={draft.nextFollowUpAt} disabled={saving} onChange={(e) => update({ nextFollowUpAt: e.target.value })} />
            </Field>
          )}
          {serverError && <div className="ck-alert ck-alert-re" role="alert">{serverError}</div>}
          <div><button type="submit" className="ck-btn ck-btn-g" disabled={saving}>{saving ? 'Logging…' : 'Log call'}</button></div>
        </form>
      ) : (
        <p className="ck-call-muted">Calls on this order are listed below. Only superadmin or an operator working on the order can log one.</p>
      )}

      <section className="ck-call-card" aria-labelledby={`${ids}-history`}>
        <h3 id={`${ids}-history`}>Call history</h3>
        {historyError ? (
          <div className="ck-alert ck-alert-re">
            <span role="alert">{historyError}</span>
            <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> Retry</button>
          </div>
        ) : history === null ? <p className="ck-call-muted" role="status">Loading calls…</p>
          : visible.length === 0 ? <p className="ck-call-muted">No calls logged yet.</p> : (
            <ol className="ck-call-list" aria-label="Logged calls">
              {visible.map((entry) => {
                const who = whoLabel(entry, contacts, vendors);
                return (
                  <li key={entry.id} className="ck-call-entry">
                    <div className="ck-call-entry-head">
                      <span className={`ck-call-chip is-${outcomeTone(entry.outcome)}`}>{outcomeLabel(entry.outcome)}</span>
                      <strong>{contactedLabel(entry.contacted)}{who ? ` · ${who}` : ''}</strong>
                    </div>
                    <div className="ck-call-meta">
                      {[entry.stepOrder ? `Step ${entry.stepOrder}` : '', formatCallTime(entry.createdAt), entry.createdByName ?? ''].filter(Boolean).join(' · ')}
                    </div>
                    {entry.notes && <p className="ck-call-text">{entry.notes}</p>}
                    {entry.nextFollowUpAt && <div className="ck-call-meta">Follow up {formatCallTime(entry.nextFollowUpAt)}</div>}
                  </li>
                );
              })}
            </ol>
          )}
        {more && <div><button type="button" className="ck-btn ck-btn-ghost" onClick={() => (onShowAll ? onShowAll() : setShowAll(true))}>Show all</button></div>}
      </section>

      {/* Mounted before it has anything to say, so assistive technology announces the message when it arrives. */}
      {!onToast && (
        <div role="status" aria-live="polite">
          {toast && <div className="ck-command-toast ok"><span className="ck-command-toast-text">{toast}</span></div>}
        </div>
      )}
    </div>
  );
}
