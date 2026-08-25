import { useEffect, useState } from 'react';
import { BadgeCheck, Pencil, Plus, Save, Trash2, X } from 'lucide-react';
import api from '../../../services/api';

interface GuardianRecord {
  id: string;
  fullName: string;
  phone?: string;
  email?: string;
  preferredLanguage?: string;
  contactVerifiedAt?: string;
  status: string;
  version: number;
  linkVersion: number;
  relationship: string;
  primary: boolean;
  receivesNotifications: boolean;
  canViewAcademic: boolean;
  canManageFees: boolean;
  pickupAuthorized: boolean;
}

interface ConsentRecord {
  id: string;
  guardianId?: string;
  guardianName?: string;
  purpose: string;
  status: string;
  noticeVersion: string;
  evidenceSource: string;
  effectiveAt: string;
}

interface GuardianOverview {
  guardians: GuardianRecord[];
  consents: ConsentRecord[];
  supportedPurposes: string[];
}

interface Props {
  studentId: number;
  canManage: boolean;
  onProfileChanged: () => void;
}

const emptyGuardian = (primary: boolean) => ({
  fullName: '', phone: '', email: '', preferredLanguage: 'en', relationship: 'GUARDIAN',
  primary, receivesNotifications: true, canViewAcademic: true, canManageFees: false,
  pickupAuthorized: false, contactVerified: false, version: 0, linkVersion: 0,
});

const purposeLabel = (value: string) => value.toLowerCase().split('_').map((part) =>
  part.charAt(0).toUpperCase() + part.slice(1)).join(' ');

const errorMessage = (error: unknown, fallback: string) =>
  (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  || (error instanceof Error ? error.message : fallback);

export function GuardianConsentCard({ studentId, canManage, onProfileChanged }: Props) {
  const [overview, setOverview] = useState<GuardianOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [guardianFormOpen, setGuardianFormOpen] = useState(false);
  const [editingGuardianId, setEditingGuardianId] = useState<string | null>(null);
  const [guardianForm, setGuardianForm] = useState(emptyGuardian(true));
  const [consentFormOpen, setConsentFormOpen] = useState(false);
  const [consentForm, setConsentForm] = useState({
    guardianId: '', purpose: 'STUDENT_PHOTO', status: 'GRANTED', noticeVersion: '',
    evidenceSource: 'SCHOOL_RECORD', evidenceReference: '', notes: '',
  });

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await api.get(`/students/${studentId}/guardians`);
      const payload = (response.data || {}) as Partial<GuardianOverview>;
      const next: GuardianOverview = {
        guardians: Array.isArray(payload.guardians) ? payload.guardians : [],
        consents: Array.isArray(payload.consents) ? payload.consents : [],
        supportedPurposes: Array.isArray(payload.supportedPurposes) ? payload.supportedPurposes : [],
      };
      setOverview(next);
      setConsentForm((current) => ({
        ...current,
        guardianId: current.guardianId || next.guardians.find((guardian) => guardian.primary)?.id || next.guardians[0]?.id || '',
      }));
    } catch (err) {
      setError(errorMessage(err, 'Could not load guardians and consent.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [studentId]);

  const startAdd = () => {
    setEditingGuardianId(null);
    setGuardianForm(emptyGuardian((overview?.guardians.length ?? 0) === 0));
    setGuardianFormOpen(true);
  };

  const startEdit = (guardian: GuardianRecord) => {
    setEditingGuardianId(guardian.id);
    setGuardianForm({
      fullName: guardian.fullName, phone: guardian.phone || '', email: guardian.email || '',
      preferredLanguage: guardian.preferredLanguage || 'en', relationship: guardian.relationship,
      primary: guardian.primary, receivesNotifications: guardian.receivesNotifications,
      canViewAcademic: guardian.canViewAcademic, canManageFees: guardian.canManageFees,
      pickupAuthorized: guardian.pickupAuthorized, contactVerified: Boolean(guardian.contactVerifiedAt),
      version: guardian.version, linkVersion: guardian.linkVersion,
    });
    setGuardianFormOpen(true);
  };

  const saveGuardian = async () => {
    if (!guardianForm.fullName.trim()) { setError('Guardian name is required.'); return; }
    setBusy(true);
    setError(null);
    try {
      const response = editingGuardianId
        ? await api.put(`/students/${studentId}/guardians/${encodeURIComponent(editingGuardianId)}`, guardianForm)
        : await api.post(`/students/${studentId}/guardians`, guardianForm);
      setOverview(response.data);
      setGuardianFormOpen(false);
      setEditingGuardianId(null);
      onProfileChanged();
    } catch (err) {
      setError(errorMessage(err, 'Could not save guardian.'));
    } finally {
      setBusy(false);
    }
  };

  const unlinkGuardian = async (guardian: GuardianRecord) => {
    if (!window.confirm(`Remove ${guardian.fullName} from this student?`)) return;
    setBusy(true);
    setError(null);
    try {
      const response = await api.delete(`/students/${studentId}/guardians/${encodeURIComponent(guardian.id)}`);
      setOverview(response.data);
      onProfileChanged();
    } catch (err) {
      setError(errorMessage(err, 'Could not remove guardian.'));
    } finally {
      setBusy(false);
    }
  };

  const recordConsent = async () => {
    if (!consentForm.noticeVersion.trim()) { setError('Notice version is required.'); return; }
    setBusy(true);
    setError(null);
    try {
      const idempotencyKey = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${studentId}`;
      const response = await api.post(`/students/${studentId}/consents`, {
        ...consentForm,
        guardianId: consentForm.guardianId || null,
      }, { headers: { 'Idempotency-Key': idempotencyKey } });
      setOverview(response.data);
      setConsentFormOpen(false);
      setConsentForm((current) => ({ ...current, noticeVersion: '', evidenceReference: '', notes: '' }));
    } catch (err) {
      setError(errorMessage(err, 'Could not record consent.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="ck-form-card" style={{ gridColumn: '1 / -1' }}>
      <div className="ck-form-head ck-card-h-wrap">
        <span>Guardians and consent</span>
        {canManage && !guardianFormOpen && !consentFormOpen ? (
          <div className="ck-inline-actions">
            <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm ck-icon-label" onClick={startAdd}>
              <Plus size={14} /> Add guardian
            </button>
            <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm ck-icon-label" onClick={() => setConsentFormOpen(true)}>
              <BadgeCheck size={14} /> Record consent
            </button>
          </div>
        ) : null}
      </div>
      <div className="ck-form-body">
        {error ? <div className="ck-alert ck-alert-r"><span>!</span><div>{error}</div></div> : null}
        {loading ? <div className="ts">Loading guardians...</div> : null}

        {!loading && !guardianFormOpen && !consentFormOpen ? (
          <>
            {(overview?.guardians.length ?? 0) === 0 ? <div className="ts">No guardians recorded.</div> : (
              <div className="ck-guardian-list">
                {overview?.guardians.map((guardian) => (
                  <div className="ck-guardian-row" key={guardian.id}>
                    <div>
                      <strong>{guardian.fullName}</strong>
                      <span>{purposeLabel(guardian.relationship)}{guardian.primary ? ' / Primary' : ''}</span>
                      <small>{guardian.phone || guardian.email || 'No contact recorded'}</small>
                    </div>
                    <div className="ck-inline-actions">
                      {guardian.contactVerifiedAt ? <span className="ck-status sg">Contact verified</span> : null}
                      {canManage ? (
                        <>
                          <button type="button" className="ck-icon-btn" title="Edit guardian" aria-label={`Edit ${guardian.fullName}`} onClick={() => startEdit(guardian)}><Pencil size={15} /></button>
                          <button type="button" className="ck-icon-btn" title="Remove guardian" aria-label={`Remove ${guardian.fullName}`} disabled={busy} onClick={() => void unlinkGuardian(guardian)}><Trash2 size={15} /></button>
                        </>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div className="ck-consent-grid">
              {(overview?.supportedPurposes || []).map((purpose) => {
                const consent = overview?.consents.find((item) => item.purpose === purpose);
                return (
                  <div key={purpose}>
                    <span>{purposeLabel(purpose)}</span>
                    <strong className={`ck-status ${consent?.status === 'GRANTED' ? 'sg' : consent?.status === 'DENIED' || consent?.status === 'WITHDRAWN' ? 'sr' : 'sam'}`}>
                      {consent?.status || 'NOT RECORDED'}
                    </strong>
                    {consent ? <small>{consent.guardianName || 'School record'} / {consent.noticeVersion}</small> : null}
                  </div>
                );
              })}
            </div>
          </>
        ) : null}

        {guardianFormOpen ? (
          <div className="ck-form-grid ck-fg-2">
            <label><span>Full name</span><input value={guardianForm.fullName} onChange={(e) => setGuardianForm({ ...guardianForm, fullName: e.target.value })} /></label>
            <label><span>Relationship</span><select value={guardianForm.relationship} onChange={(e) => setGuardianForm({ ...guardianForm, relationship: e.target.value })}><option value="FATHER">Father</option><option value="MOTHER">Mother</option><option value="GUARDIAN">Guardian</option><option value="GRANDPARENT">Grandparent</option><option value="SIBLING">Sibling</option><option value="OTHER">Other</option></select></label>
            <label><span>Phone</span><input inputMode="tel" value={guardianForm.phone} onChange={(e) => setGuardianForm({ ...guardianForm, phone: e.target.value })} /></label>
            <label><span>Email</span><input type="email" value={guardianForm.email} onChange={(e) => setGuardianForm({ ...guardianForm, email: e.target.value })} /></label>
            <label><span>Preferred language</span><input value={guardianForm.preferredLanguage} onChange={(e) => setGuardianForm({ ...guardianForm, preferredLanguage: e.target.value })} /></label>
            <div className="ck-check-grid">
              <label><input type="checkbox" checked={guardianForm.primary} onChange={(e) => setGuardianForm({ ...guardianForm, primary: e.target.checked })} /> Primary guardian</label>
              <label><input type="checkbox" checked={guardianForm.contactVerified} onChange={(e) => setGuardianForm({ ...guardianForm, contactVerified: e.target.checked })} /> Contact verified</label>
              <label><input type="checkbox" checked={guardianForm.receivesNotifications} onChange={(e) => setGuardianForm({ ...guardianForm, receivesNotifications: e.target.checked })} /> Notifications</label>
              <label><input type="checkbox" checked={guardianForm.canViewAcademic} onChange={(e) => setGuardianForm({ ...guardianForm, canViewAcademic: e.target.checked })} /> View academics</label>
              <label><input type="checkbox" checked={guardianForm.canManageFees} onChange={(e) => setGuardianForm({ ...guardianForm, canManageFees: e.target.checked })} /> Manage fees</label>
              <label><input type="checkbox" checked={guardianForm.pickupAuthorized} onChange={(e) => setGuardianForm({ ...guardianForm, pickupAuthorized: e.target.checked })} /> Pickup authorized</label>
            </div>
            <div className="ck-inline-actions" style={{ gridColumn: '1 / -1' }}>
              <button type="button" className="ck-btn ck-btn-g ck-icon-label" disabled={busy} onClick={() => void saveGuardian()}><Save size={15} /> Save guardian</button>
              <button type="button" className="ck-btn ck-btn-ghost ck-icon-label" disabled={busy} onClick={() => setGuardianFormOpen(false)}><X size={15} /> Cancel</button>
            </div>
          </div>
        ) : null}

        {consentFormOpen ? (
          <div className="ck-form-grid ck-fg-2">
            <label><span>Purpose</span><select value={consentForm.purpose} onChange={(e) => setConsentForm({ ...consentForm, purpose: e.target.value })}>{(overview?.supportedPurposes || []).map((purpose) => <option key={purpose} value={purpose}>{purposeLabel(purpose)}</option>)}</select></label>
            <label><span>Status</span><select value={consentForm.status} onChange={(e) => setConsentForm({ ...consentForm, status: e.target.value })}><option value="GRANTED">Granted</option><option value="DENIED">Denied</option><option value="WITHDRAWN">Withdrawn</option><option value="PENDING">Pending</option></select></label>
            <label><span>Guardian</span><select value={consentForm.guardianId} onChange={(e) => setConsentForm({ ...consentForm, guardianId: e.target.value })}><option value="">School record</option>{overview?.guardians.map((guardian) => <option key={guardian.id} value={guardian.id}>{guardian.fullName}</option>)}</select></label>
            <label><span>Notice version</span><input value={consentForm.noticeVersion} onChange={(e) => setConsentForm({ ...consentForm, noticeVersion: e.target.value })} placeholder="e.g. privacy-notice-2026-01" /></label>
            <label><span>Evidence source</span><select value={consentForm.evidenceSource} onChange={(e) => setConsentForm({ ...consentForm, evidenceSource: e.target.value })}><option value="SCHOOL_RECORD">School record</option><option value="SIGNED_FORM">Signed form</option><option value="GUARDIAN_PORTAL">Guardian portal</option><option value="EMAIL">Email</option><option value="SMS">SMS</option><option value="WHATSAPP">WhatsApp</option><option value="OTHER">Other</option></select></label>
            <label><span>Evidence reference</span><input value={consentForm.evidenceReference} onChange={(e) => setConsentForm({ ...consentForm, evidenceReference: e.target.value })} /></label>
            <label style={{ gridColumn: '1 / -1' }}><span>Notes</span><textarea value={consentForm.notes} onChange={(e) => setConsentForm({ ...consentForm, notes: e.target.value })} /></label>
            <div className="ck-inline-actions" style={{ gridColumn: '1 / -1' }}>
              <button type="button" className="ck-btn ck-btn-g ck-icon-label" disabled={busy} onClick={() => void recordConsent()}><Save size={15} /> Record consent</button>
              <button type="button" className="ck-btn ck-btn-ghost ck-icon-label" disabled={busy} onClick={() => setConsentFormOpen(false)}><X size={15} /> Cancel</button>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}
