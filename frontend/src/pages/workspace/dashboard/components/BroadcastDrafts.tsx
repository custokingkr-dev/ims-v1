import { useEffect, useRef, useState } from 'react';
import { Modal } from '../../../../components/Modal';
import api from '../../../../services/api';
import { createBroadcastClient, type BroadcastCapabilities, type QueueBroadcastRequest } from '../../../../generated/broadcastClient';
const broadcastClient = createBroadcastClient(api);
import { BroadcastOutcomes, readOutcomes, type BroadcastOutcome } from './BroadcastOutcomes';

export interface BroadcastRecord {
  id: string; module: string | null; title: string; message: string | null;
  audienceType: string; channels: string[]; status: string; schoolId?: number;
  communicationCategory?: string; scheduledAt: string | null; sentAt: string | null; createdAt: string;
  readonly approvalMode?: 'DRY_RUN' | 'LIVE' | null; readonly dispatchMode?: 'DRY_RUN' | 'LIVE' | null;
}
interface Preview {
  broadcastId: string; total: number; eligible: number; suppressed: number; duplicate: number;
  reasons: Record<string, number>; fingerprint: string; explanation: string;
}
function readRecord(value: unknown): BroadcastRecord {
  const record = value as Partial<BroadcastRecord> | null;
  if (!record || typeof record.id !== 'string' || typeof record.title !== 'string'
    || typeof record.status !== 'string' || typeof record.audienceType !== 'string'
    || !Array.isArray(record.channels) || !record.channels.every(channel => typeof channel === 'string')
    || ![record.approvalMode, record.dispatchMode].every(mode => mode == null || ['DRY_RUN', 'LIVE'].includes(mode))) throw new Error('Invalid broadcast response');
  return { ...record, module: typeof record.module === 'string' ? record.module : null,
    message: typeof record.message === 'string' ? record.message : null,
    createdAt: typeof record.createdAt === 'string' ? record.createdAt : '',
    scheduledAt: typeof record.scheduledAt === 'string' ? record.scheduledAt : null,
    sentAt: typeof record.sentAt === 'string' ? record.sentAt : null } as BroadcastRecord;
}
function readRecords(value: unknown): BroadcastRecord[] {
  if (!Array.isArray(value)) throw new Error('Invalid broadcast list');
  return value.map(readRecord);
}
function readCapabilities(value: unknown): BroadcastCapabilities {
  const record = value as BroadcastCapabilities | null;
  if (!record || !['canCreateDraft', 'canApprove', 'canPreview', 'canSend', 'canQueue'].every(key => typeof record[key as keyof BroadcastCapabilities] === 'boolean')
    || !['OFF', 'DRY_RUN', 'LIVE'].includes(record.mode) || typeof record.sendUnavailableReason !== 'string' || typeof record.queueUnavailableReason !== 'string'
    || ![record.supportedAudiences, record.supportedChannels, record.supportedCategories].every(items => Array.isArray(items) && items.every(item => typeof item === 'string'))) throw new Error('Capabilities unavailable');
  return record;
}
function readPreview(value: unknown, id: string): Preview {
  const record = value as Preview | null;
  if (!record || record.broadcastId !== id || typeof record.fingerprint !== 'string' || !record.fingerprint
    || ![record.total, record.eligible, record.suppressed, record.duplicate].every(count => Number.isInteger(count) && count >= 0)
    || record.total !== record.eligible + record.suppressed + record.duplicate
    || typeof record.explanation !== 'string' || !record.reasons || typeof record.reasons !== 'object' || Array.isArray(record.reasons)
    || !Object.values(record.reasons).every(count => Number.isInteger(count) && count >= 0)) throw new Error('Preview unavailable');
  return record;
}
const AUDIENCES: Record<string, string> = { ALL_PARENTS: 'Primary guardians', ALL_STAFF: 'All staff', WHOLE_SCHOOL: 'Whole school' };
export function broadcastStatus(status: string, mode?: 'DRY_RUN' | 'LIVE' | null) {
  if (status === 'QUEUED') return mode === 'DRY_RUN' ? 'Dry run queued' : mode === 'LIVE' ? 'Live messages queued' : 'Queued';
  if (status === 'COMPLETED_WITH_FAILURES') return mode === 'DRY_RUN' ? 'Dry run finished with failures' : 'Completed with failures';
  return ({ DRAFT: 'Draft', SCHEDULED: 'Legacy approval - review required', APPROVED: 'Recipients approved',
    DRY_RUN_COMPLETE: 'Dry run complete', SENT: 'Marked sent', SUBMITTING: 'Submission unconfirmed', ACCEPTED: 'Accepted by provider',
    UNKNOWN: 'Outcome unknown', DELIVERED: 'Delivery confirmed', DELIVERY_FAILED: 'Delivery failed', REJECTED: 'Rejected by provider',
    REPORT_CONFLICT: 'Conflicting delivery reports', COMPLETED: 'Processing complete', AWAITING_DELIVERY: 'Waiting for delivery reports',
    NEEDS_RECONCILIATION: 'Delivery needs reconciliation' } as Record<string, string>)[status] ?? 'Status unavailable';
}
export function policyReason(code: string) {
  return ({ SCHOOL_COMMUNICATIONS_NOT_GRANTED: 'Consent not granted', SCHOOL_COMMUNICATIONS_EXPIRED: 'Consent expired', SCHOOL_COMMUNICATIONS_DECISION_MISSING: 'Consent decision missing',
    NOTIFICATION_PREFERENCE_DISABLED: 'Notifications disabled', CONTACT_NOT_VERIFIED: 'Contact not verified', NO_PRIMARY_GUARDIAN: 'Primary guardian missing',
    DESTINATION_MISSING: 'Contact destination missing', GUARDIAN_INACTIVE: 'Guardian inactive', POLICY_BINDING_CHANGED: 'Approved contact changed',
    DUPLICATE_DESTINATION: 'Shared destination already included', POLICY_OR_DELIVERY_UNAVAILABLE: 'Policy or delivery check unavailable', DELIVERY_ATTEMPT_FAILED: 'Delivery check failed',
    STUDENT_NOT_FOUND: 'Student no longer available', CONSENT_GUARDIAN_UNSPECIFIED: 'Consent is not linked to a guardian', CONSENT_GUARDIAN_MISMATCH: 'Consent belongs to another guardian',
    LIVE_RECIPIENT_NOT_ADMITTED: 'Live sending is not enabled for this recipient', LIVE_PREPARATION_UNAVAILABLE: 'Sending preparation is unavailable; retry pending',
    CURRENT_POLICY_DENIED: 'Current communication policy excludes this recipient', SUBMISSION_ALREADY_RESERVED: 'Submission already started; awaiting reconciliation',
    PROVIDER_QUEUED: 'Provider accepted the message; delivery is unconfirmed', PROVIDER_HTTP_REJECTED: 'Provider rejected the message',
    LIVE_EMAIL_NOT_CONFIGURED: 'Live email configuration is unavailable', INVALID_PREPARED_EMAIL: 'Prepared email failed validation',
    PROVIDER_RESULT_UNCONFIRMED: 'Provider result is unconfirmed', PROVIDER_OUTCOME_UNCONFIRMED: 'Provider outcome is unconfirmed',
    PROVIDER_HTTP_UNCONFIRMED: 'Provider response did not confirm the outcome', PROVIDER_RESPONSE_UNCONFIRMED: 'Provider response did not confirm the outcome',
    PROVIDER_RESPONSE_INVALID: 'Provider response could not be verified', PROVIDER_INTERRUPTED: 'Provider request was interrupted; outcome is unconfirmed',
    PROVIDER_ID_CONFLICT: 'Provider reference conflicts with the saved submission', CONFLICTING_PROVIDER_REPORTS: 'Provider delivery reports conflict',
    CONFLICTING_PROVIDER_RESULTS: 'Provider submission results conflict' } as Record<string, string>)[code] ?? 'Outcome details are unavailable';
}

export function BroadcastDrafts({ module = 'fees', schoolId }: { module?: string; schoolId?: number }) {
  const [records, setRecords] = useState<BroadcastRecord[]>([]);
  const [capabilities, setCapabilities] = useState<BroadcastCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [unconfirmed, setUnconfirmed] = useState<Set<string>>(new Set());
  const actionInFlight = useRef(false);
  const [liveReview, setLiveReview] = useState<{ record: BroadcastRecord; preview: Preview } | null>(null);
  const [liveAcknowledged, setLiveAcknowledged] = useState(false);
  const [previews, setPreviews] = useState<Record<string, Preview>>({});
  const [outcomes, setOutcomes] = useState<Record<string, BroadcastOutcome>>({});
  const [composing, setComposing] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true); setLoadError(''); setActionError(''); setNotice(''); setCapabilities(null); setPreviews({}); setOutcomes({}); setLiveReview(null);
    Promise.all([
      broadcastClient.listBroadcasts({ schoolId }),
      broadcastClient.getCapabilities({ schoolId }),
    ]).then(([list, available]) => {
      const nextRecords = readRecords(list); const nextCapabilities = readCapabilities(available);
      if (active) { setRecords(nextRecords); setCapabilities(nextCapabilities); setUnconfirmed(new Set()); }
    }).catch(() => { if (active) setLoadError('Broadcast drafts and available actions could not be loaded. Retry to review or create a draft.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [retry, schoolId]);

  async function reviewLiveSend(record: BroadcastRecord) {
    if (actionInFlight.current || !record.schoolId || record.approvalMode !== 'LIVE' || record.dispatchMode === 'DRY_RUN') return;
    actionInFlight.current = true;
    setPendingId(record.id); setActionError(''); setNotice(''); setLiveReview(null); setLiveAcknowledged(false);
    try {
      const [available, reviewed] = await Promise.all([
        broadcastClient.getCapabilities({ schoolId: record.schoolId }),
        broadcastClient.previewBroadcast({ id: record.id }),
      ]);
      const scoped = readCapabilities(available);
      if (scoped.mode !== 'LIVE' || !scoped.canSend || !scoped.canQueue || !record.channels.every(channel => scoped.supportedChannels.includes(channel))) {
        setActionError(scoped.sendUnavailableReason || scoped.queueUnavailableReason || 'Live sending is unavailable for this school and channel. Refresh drafts after the configuration is verified.');
        return;
      }
      const preview = readPreview(reviewed, record.id);
      if (preview.eligible === 0) { setActionError('No recipients currently qualify for live sending. Create and review a new draft after the school records are updated.'); return; }
      setLiveReview({ record, preview });
    } catch {
      setActionError('Live sending could not be reviewed. No send request was made. Refresh drafts and review the approved recipients again.');
    } finally { actionInFlight.current = false; setPendingId(null); }
  }

  async function act(record: BroadcastRecord, action: 'preview' | 'approve' | 'send' | 'delivery-status' | 'retry', queueRequest: QueueBroadcastRequest = {}) {
    if (actionInFlight.current) return;
    actionInFlight.current = true;
    setPendingId(record.id); setActionError(''); setNotice('');
    try {
      const result = await ({
        preview: () => broadcastClient.previewBroadcast({ id: record.id }),
        approve: () => broadcastClient.approveBroadcast({ id: record.id }, { previewFingerprint: previews[record.id]?.fingerprint ?? '' }),
        send: () => broadcastClient.queueBroadcast({ id: record.id }, queueRequest),
        'delivery-status': () => broadcastClient.getDeliveryStatus({ id: record.id }),
        retry: () => broadcastClient.retryBroadcast({ id: record.id }),
      }[action])();
      if (action === 'preview') setPreviews(current => ({ ...current, [record.id]: readPreview(result, record.id) }));
      else if (action === 'approve') {
        const confirmed = readRecord(result);
        if (confirmed.id !== record.id || confirmed.status !== 'APPROVED') throw new Error('Approval not confirmed');
        setRecords(current => current.map(item => item.id === record.id ? confirmed : item));
        setNotice('Recipient approval recorded. No messages have been sent.');
      } else {
        const confirmed = readOutcomes(result, record.id);
        if (action === 'send' && confirmed.mode !== (queueRequest.mode ?? 'DRY_RUN')) throw new Error('Dispatch mode was not confirmed');
        setOutcomes(current => ({ ...current, [record.id]: confirmed }));
        setRecords(current => current.map(item => item.id === record.id ? { ...item, status: confirmed.status, dispatchMode: confirmed.mode } : item));
        if (action === 'send') setNotice(confirmed.mode === 'LIVE'
          ? 'Live sending is recorded for the approved recipients. See recipient outcomes for confirmed deliveries.'
          : 'Dry run queued. It checks the approved recipients without sending messages. Refresh outcomes to see the result.');
        if (action === 'retry') setNotice('Failed checks are eligible for another attempt. Completed and suppressed recipients will not be retried.');
        setUnconfirmed(current => { const next = new Set(current); next.delete(record.id); return next; });
        if (action === 'send') setLiveReview(null);
      }
    } catch {
      if (action === 'approve' || action === 'send' || action === 'retry') {
        setUnconfirmed(current => new Set(current).add(record.id));
        setLiveReview(null);
        setActionError(`${action === 'approve' ? 'Approval' : 'Queue update'} could not be confirmed. ${queueRequest.mode === 'LIVE' ? 'Messages may already be processing. Refresh outcomes for this broadcast; do not create a replacement or resend. A changed recipient review requires a newly reviewed draft only after this result is resolved.' : 'Refresh drafts to check its status before trying again.'}`);
      } else setActionError(action === 'preview' ? 'Recipient preview is unavailable. Check the school, communication policy connection and supported category, then preview again.' : 'Outcomes could not be loaded. Refresh outcomes to try again.');
      if (action === 'preview' || action === 'approve') setPreviews(current => { const next = { ...current }; delete next[record.id]; return next; });
    } finally { actionInFlight.current = false; setPendingId(null); }
  }

  return <section className="ck-command-broadcast" aria-label="Broadcast drafts">
    <div className="ck-command-broadcast-head">
      <h2 className="ck-command-broadcast-title">Broadcast drafts</h2>
      <button className="ck-btn ck-btn-ghost" disabled={loading || pendingId !== null} onClick={() => setRetry(value => value + 1)}>Refresh drafts</button>
      {capabilities?.canCreateDraft && <button className="ck-command-broadcast-compose" onClick={() => setComposing(true)}>Create draft</button>}
    </div>
    {loading && <p role="status" style={{ padding: 16 }}>Loading broadcast drafts...</p>}
    {loadError && <div role="alert" style={{ padding: 16 }}><p>{loadError}</p><button className="ck-btn ck-btn-ghost" onClick={() => setRetry(value => value + 1)}>Retry broadcasts</button></div>}
    {capabilities && <div id="broadcast-delivery-availability" style={{ padding: '0 16px', fontSize: 14 }}>
      <p>{capabilities.sendUnavailableReason}</p>
      {capabilities.queueUnavailableReason && <p>{capabilities.queueUnavailableReason}</p>}
      <p>{capabilities.mode === 'LIVE' ? 'Live sending can send actual messages. Preview eligibility, approve the recipient list, then review and confirm before sending.' : 'Preview eligibility, approve the recipient list, then run a delivery check when available.'} Saving and approval do not send messages.</p>
    </div>}
    {actionError && <p role="alert" style={{ padding: '0 16px' }}>{actionError}</p>}
    {notice && <p role="status" style={{ padding: '0 16px' }}>{notice}</p>}
    {!loading && !loadError && records.length === 0 && <p style={{ padding: 16 }}>No broadcast drafts to review.</p>}
    {!loadError && <div className="ck-command-broadcast-list">{records.map(record => {
      const preview = previews[record.id]; const outcome = outcomes[record.id];
      const busy = pendingId !== null; const blocked = busy || unconfirmed.has(record.id);
      const recordedMode = outcome?.mode ?? record.dispatchMode ?? record.approvalMode;
      const supported = record.schoolId && record.communicationCategory === 'SCHOOL_NOTICE' && record.audienceType === 'ALL_PARENTS';
      return <article key={record.id} className="ck-command-bc-item" aria-label={record.title}>
        <div className="ck-command-bc-tags"><span className="ck-command-bc-status">{broadcastStatus(record.status, recordedMode)}</span></div>
        <h3 className="ck-command-bc-title">{record.title}</h3>
        <div className="ck-command-bc-meta">{record.schoolId ? `School ${record.schoolId} | ` : ''}{AUDIENCES[record.audienceType] ?? record.audienceType} | Requested channels: {record.channels.join(', ') || 'Not specified'}</div>
        <p className="ck-command-bc-note" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{record.message}</p>
        {!supported && <p className="ck-command-bc-note">This legacy draft lacks supported school-notice details. Create a school notice to review recipients.</p>}
        {record.status === 'SENT' && <p className="ck-command-bc-note">A sent record does not confirm recipient delivery.</p>}
        {preview && record.status === 'DRAFT' && <div aria-label="Recipient preview">
          <p>{preview.eligible} eligible destinations; {preview.suppressed} excluded; {preview.duplicate} shared destinations skipped.</p>
          {Object.entries(preview.reasons).map(([reason, count]) => <p key={reason}>{policyReason(reason)}: {count}</p>)}
          <p>{preview.explanation}</p>
          {preview.eligible === 0 && <p>No eligible recipients. Update the school records before previewing again.</p>}
        </div>}
        <div className="ck-command-bc-actions">
          {record.status === 'DRAFT' && supported && capabilities?.canPreview && <button className="ck-btn ck-btn-ghost" disabled={blocked} onClick={() => void act(record, 'preview')}>Preview recipients</button>}
          {record.status === 'DRAFT' && supported && capabilities?.canApprove && <button className="ck-command-bc-btn-approve" disabled={blocked || !preview || preview.eligible === 0} onClick={() => void act(record, 'approve')}>Approve recipients</button>}
          {record.status === 'APPROVED' && capabilities?.mode === 'LIVE' && record.approvalMode === 'LIVE' && record.dispatchMode !== 'DRY_RUN'
            && <button className="ck-btn ck-btn-g" disabled={blocked || !capabilities.canPreview || (schoolId === record.schoolId && (!capabilities.canSend || !capabilities.canQueue))} aria-describedby="broadcast-delivery-availability" onClick={() => void reviewLiveSend(record)}>Review live send</button>}
          {record.status === 'APPROVED' && capabilities?.mode !== 'LIVE' && record.approvalMode !== 'LIVE'
            && <button className="ck-command-bc-btn-send" disabled={blocked || !capabilities?.canQueue || capabilities.mode !== 'DRY_RUN'} aria-describedby="broadcast-delivery-availability" onClick={() => void act(record, 'send')}>Queue dry run</button>}
          {!['DRAFT', 'SCHEDULED'].includes(record.status) && <button className="ck-btn ck-btn-ghost" disabled={busy} onClick={() => void act(record, 'delivery-status')}>Refresh outcomes</button>}
        </div>
        {record.status === 'APPROVED' && capabilities?.mode === 'LIVE' && record.approvalMode !== 'LIVE' && <p className="ck-command-bc-note">This approval belongs to a dry run. Create and review a new draft to send live messages.</p>}
        {record.status === 'APPROVED' && record.approvalMode === 'LIVE' && capabilities?.mode !== 'LIVE' && <p className="ck-command-bc-note">Live sending is currently unavailable. This live approval cannot be queued as a dry run.</p>}
        {pendingId === record.id && <p role="status">Checking broadcast...</p>}
        {outcome && <BroadcastOutcomes outcome={outcome} canRetry={Boolean(capabilities?.canQueue) && capabilities?.mode === 'DRY_RUN' && !blocked} onRetry={() => void act(record, 'retry')} />}
      </article>;
    })}</div>}
    {liveReview && <Modal disabled={pendingId !== null} title="Confirm live sending" subtitle="This sends actual messages. Review the content and approved recipients before continuing."
      onClose={() => { if (!pendingId) setLiveReview(null); }} footer={<>
        <button className="ck-btn ck-btn-ghost" disabled={pendingId !== null} onClick={() => setLiveReview(null)}>Cancel</button>
        <button className="ck-btn ck-btn-g" disabled={!liveAcknowledged || pendingId !== null} onClick={() => void act(liveReview.record, 'send', { mode: 'LIVE', previewFingerprint: liveReview.preview.fingerprint })}>{pendingId ? 'Confirming send...' : 'Send actual messages'}</button>
      </>}>
      <p><strong>{liveReview.record.title}</strong></p>
      <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{liveReview.record.message}</p>
      <p>School {liveReview.record.schoolId}. Channels: {liveReview.record.channels.join(', ')}.</p>
      <p>{liveReview.preview.eligible} eligible destinations; {liveReview.preview.suppressed} excluded; {liveReview.preview.duplicate} shared destinations skipped.</p>
      <p>{liveReview.preview.explanation}</p>
      <p>The approved audience is fixed. Changed consent or contacts will block sending or exclude recipients. Provider acceptance does not confirm delivery.</p>
      <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 16, lineHeight: 1.5 }}>
        <input type="checkbox" checked={liveAcknowledged} disabled={pendingId !== null} onChange={event => setLiveAcknowledged(event.target.checked)} style={{ marginBlockStart: 4, width: 18, height: 18, minWidth: 18, flex: '0 0 18px' }} />
        <span style={{ minWidth: 0 }}>I reviewed the message and recipients and want to send actual messages.</span>
      </label>
    </Modal>}
    {composing && capabilities?.canCreateDraft && <ComposeBroadcastDraft module={module} schoolId={schoolId} channels={capabilities.supportedChannels} onClose={() => setComposing(false)} onCreated={record => {
      setRecords(current => [record, ...current.filter(item => item.id !== record.id)]); setNotice('Broadcast draft saved. No messages have been sent.');
    }} />}
  </section>;
}

function ComposeBroadcastDraft({ module, schoolId, channels, onClose, onCreated }: {
  module: string; schoolId?: number; channels: string[]; onClose: () => void; onCreated: (record: BroadcastRecord) => void;
}) {
  const [title, setTitle] = useState('');
  const audience = 'ALL_PARENTS';
  const [selectedSchoolId, setSelectedSchoolId] = useState(schoolId ?? 0);
  const [schools, setSchools] = useState<Array<{ id: number; name: string }>>([]);
  const [schoolError, setSchoolError] = useState('');
  const [schoolRetry, setSchoolRetry] = useState(0);
  useEffect(() => {
    if (schoolId) return;
    let active = true; setSchoolError('');
    api.get('/schools').then(result => {
      if (!Array.isArray(result.data) || !result.data.every((school: { id?: unknown; name?: unknown }) => Number.isInteger(school.id) && typeof school.name === 'string')) throw new Error('School list unavailable');
      if (active) setSchools(result.data);
    }).catch(() => { if (active) setSchoolError('Schools could not be loaded. Retry before saving the draft.'); });
    return () => { active = false; };
  }, [schoolId, schoolRetry]);
  const [channel, setChannel] = useState('');
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [checking, setChecking] = useState(false);
  const [matches, setMatches] = useState<BroadcastRecord[]>([]);
  const valid = Boolean(selectedSchoolId > 0 && title.trim() && channels.includes(channel) && message.trim());

  async function save() {
    if (!valid || saving || unconfirmed) return;
    setSaving(true);
    setError('');
    try {
      const result = await broadcastClient.createBroadcast({
        title: title.trim(), message: message.trim(), audienceType: audience, channels: [channel], module,
        schoolId: selectedSchoolId, communicationCategory: 'SCHOOL_NOTICE',
      });
      onCreated(readRecord(result));
      onClose();
    } catch {
      setUnconfirmed(true);
      setError('Draft saving could not be confirmed. Your text is still here. Check saved drafts before trying again.');
    } finally {
      setSaving(false);
    }
  }

  async function checkSavedDrafts() {
    setChecking(true);
    try {
      const result = await broadcastClient.listBroadcasts({ schoolId: selectedSchoolId });
      const savedMatches = readRecords(result).filter(record => record.title === title.trim()
        && record.message === message.trim() && record.audienceType === audience
        && record.channels.includes(channel));
      setMatches(savedMatches);
      setUnconfirmed(savedMatches.length > 0);
      setError(savedMatches.length > 0
        ? 'A matching draft is already saved. Open that record to avoid creating a duplicate.'
        : 'No matching draft was found. You can retry saving with the text below.');
    } catch {
      setError('Saved drafts could not be checked. Your text is still here. Check again before retrying the save.');
    } finally {
      setChecking(false);
    }
  }

  return (
    <Modal title="Create broadcast draft" subtitle="Prepare a notice for review. Saving and approval do not send it." onClose={() => { if (!saving) onClose(); }} footer={<>
      <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={onClose}>Cancel</button>
      <button className="ck-btn ck-btn-g" disabled={!valid || saving || unconfirmed || checking} onClick={() => void save()}>{saving ? 'Saving draft…' : 'Save draft'}</button>
    </>}>
      {error && <p role="alert">{error}</p>}
      {schoolError && <div role="alert"><p>{schoolError}</p><button className="ck-btn ck-btn-ghost" onClick={() => setSchoolRetry(value => value + 1)}>Retry schools</button></div>}
      {unconfirmed && <button className="ck-btn ck-btn-ghost" disabled={checking} onClick={() => void checkSavedDrafts()}>{checking ? 'Checking drafts…' : 'Check saved drafts'}</button>}
      {matches.map(record => <button key={record.id} className="ck-btn ck-btn-ghost" onClick={() => { onCreated(record); onClose(); }}>Open saved draft: {record.title}</button>)}
      <fieldset disabled={saving || unconfirmed || checking} className="ck-form-grid" style={{ gap: 14, border: 0, padding: 0, margin: 0, minWidth: 0 }}>
        <div className="ck-field"><label htmlFor="bc-title">Title (required)</label><input id="bc-title" maxLength={255} value={title} onChange={event => setTitle(event.target.value)} disabled={saving} /></div>
        {!schoolId && <div className="ck-field"><label htmlFor="bc-school">School (required)</label><select id="bc-school" value={selectedSchoolId || ''} onChange={event => setSelectedSchoolId(Number(event.target.value))}>
          <option value="">Select school</option>{schools.map(school => <option key={school.id} value={school.id}>{school.name}</option>)}
        </select></div>}
        <p>School notice for eligible primary guardians. Consent, notification preferences and verified contacts determine the recipients.</p>
        <div className="ck-field"><label htmlFor="bc-channel">Requested channel (required)</label><select id="bc-channel" value={channel} onChange={event => setChannel(event.target.value)} disabled={saving}>
          <option value="">Select channel</option>{channels.filter(value => ['SMS', 'EMAIL', 'WHATSAPP'].includes(value)).map(value => <option key={value} value={value}>{value === 'WHATSAPP' ? 'WhatsApp' : value === 'EMAIL' ? 'Email' : value}</option>)}
        </select></div>
        <div className="ck-field"><label htmlFor="bc-message">Message (required)</label><textarea id="bc-message" rows={4} value={message} onChange={event => setMessage(event.target.value)} disabled={saving} /></div>
      </fieldset>
    </Modal>
  );
}
