import { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ClipboardCheck, Image, LoaderCircle, RefreshCw, XCircle } from 'lucide-react';
import {
  completeReviewCampaign,
  fetchCampaignItems,
  fetchPhotoVerificationStatus,
  fetchProfileVerificationStatus,
  initiatePhotoVerification,
  initiateProfileVerification,
  updateReviewItem,
} from '../../../api/dashboardCommandCenterApi';
import type { ReviewItemDetail, StudentVerificationStatusResponse } from '../../../types/dashboardCommandCenter';
import { StudentPhotoAvatar } from '../../../features/students';

type VerificationMode = 'profile' | 'photo';

interface Props {
  open: boolean;
  mode: VerificationMode;
  schoolId: number;
  onClose: () => void;
  onChanged?: () => void;
}

const PAGE_SIZE = 25;

function emptyStatus(): StudentVerificationStatusResponse {
  return {
    campaignId: null,
    totalStudents: 0,
    completed: 0,
    pending: 0,
    needsCorrection: 0,
    completionPercent: 0,
  };
}

function statusLabel(status: ReviewItemDetail['status']) {
  if (status === 'COMPLETED') return 'Verified';
  if (status === 'NEEDS_CORRECTION') return 'Needs correction';
  return 'Pending';
}

function drawerTitle(mode: VerificationMode) {
  return mode === 'profile' ? 'Profile verification' : 'Photo verification';
}

function itemPatch(mode: VerificationMode) {
  if (mode === 'photo') {
    return { verifiedPhoto: true, status: 'COMPLETED', correctionNotes: null };
  }
  return {
    verifiedFullName: true,
    verifiedAdmissionNo: true,
    verifiedClassSection: true,
    verifiedRollNo: true,
    verifiedFatherName: true,
    verifiedFatherContact: true,
    verifiedAddress: true,
    status: 'COMPLETED',
    correctionNotes: null,
  };
}

export function StudentVerificationDrawer({ open, mode, schoolId, onClose, onChanged }: Props) {
  const [status, setStatus] = useState<StudentVerificationStatusResponse>(emptyStatus());
  const [items, setItems] = useState<ReviewItemDetail[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [correctionNote, setCorrectionNote] = useState('');

  const selected = useMemo(
    () => items.find((item) => item.itemId === selectedId) || items[0] || null,
    [items, selectedId],
  );

  const loadStatus = useCallback(async () => {
    const next = mode === 'profile'
      ? await fetchProfileVerificationStatus(schoolId)
      : await fetchPhotoVerificationStatus(schoolId);
    setStatus(next);
    return next;
  }, [mode, schoolId]);

  const loadItems = useCallback(async (campaignId: string, statusFilter = filter) => {
    const page = await fetchCampaignItems(campaignId, {
      page: 0,
      size: PAGE_SIZE,
      status: statusFilter || undefined,
    });
    setItems(page.content || []);
    setSelectedId((current) => {
      if (current && page.content?.some((item) => item.itemId === current)) return current;
      return page.content?.[0]?.itemId || null;
    });
  }, [filter]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const next = await loadStatus();
      if (next.campaignId) {
        await loadItems(next.campaignId);
      } else {
        setItems([]);
        setSelectedId(null);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load verification.');
    } finally {
      setLoading(false);
    }
  }, [loadItems, loadStatus]);

  useEffect(() => {
    if (open) void refresh();
  }, [open, mode, refresh]);

  useEffect(() => {
    setCorrectionNote('');
  }, [selected?.itemId]);

  const startCampaign = async () => {
    setBusy('start');
    setError(null);
    try {
      const next = mode === 'profile'
        ? await initiateProfileVerification({ schoolId, dueDate: null })
        : await initiatePhotoVerification({ schoolId, dueDate: null });
      setStatus(next);
      if (next.campaignId) await loadItems(next.campaignId);
      onChanged?.();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not start verification.');
    } finally {
      setBusy('');
    }
  };

  const saveVerified = async () => {
    if (!selected) return;
    setBusy(`verify:${selected.itemId}`);
    setError(null);
    try {
      await updateReviewItem(selected.itemId, itemPatch(mode));
      await refresh();
      onChanged?.();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not save verification.');
    } finally {
      setBusy('');
    }
  };

  const saveCorrection = async () => {
    if (!selected || !correctionNote.trim()) return;
    setBusy(`correction:${selected.itemId}`);
    setError(null);
    try {
      await updateReviewItem(selected.itemId, {
        status: 'NEEDS_CORRECTION',
        correctionNotes: correctionNote.trim(),
      });
      await refresh();
      onChanged?.();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not request correction.');
    } finally {
      setBusy('');
    }
  };

  const complete = async () => {
    if (!status.campaignId) return;
    setBusy('complete');
    setError(null);
    try {
      await completeReviewCampaign(status.campaignId);
      await refresh();
      onChanged?.();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not complete campaign.');
    } finally {
      setBusy('');
    }
  };

  if (!open) return null;

  const Icon = mode === 'profile' ? ClipboardCheck : Image;

  return (
    <div className="ck-modal-bg" onClick={onClose}>
      <div className="ck-modal ck-verification-drawer" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="ck-modal-h">
          <div className="ck-verification-title">
            <Icon size={18} aria-hidden="true" />
            <div>
              <div className="ck-modal-title">{drawerTitle(mode)}</div>
              <div className="ts">{status.completed} verified, {status.pending} pending, {status.needsCorrection} need correction</div>
            </div>
          </div>
          <button className="ck-modal-x" aria-label="Close verification" onClick={onClose}>X</button>
        </div>

        <div className="ck-modal-body">
          {error ? <div className="ck-alert ck-alert-r"><span>!</span><div>{error}</div></div> : null}

          {loading ? (
            <div className="ck-verification-loading"><LoaderCircle className="pi-spin" size={18} /> Loading verification...</div>
          ) : !status.campaignId ? (
            <div className="ck-verification-empty">
              <Icon size={24} aria-hidden="true" />
              <strong>{drawerTitle(mode)} is not started</strong>
              <span>Start a school-wide review campaign for active students.</span>
              <button className="ck-btn ck-btn-g ck-icon-label" onClick={startCampaign} disabled={!!busy}>
                {busy === 'start' ? <LoaderCircle className="pi-spin" size={15} /> : <Check size={15} />}
                Start verification
              </button>
            </div>
          ) : (
            <>
              <div className="ck-verification-summary">
                <div><span>Total</span><strong>{status.totalStudents}</strong></div>
                <div><span>Verified</span><strong>{status.completed}</strong></div>
                <div><span>Pending</span><strong>{status.pending}</strong></div>
                <div><span>Needs correction</span><strong>{status.needsCorrection}</strong></div>
              </div>

              <div className="ck-verification-toolbar">
                <div className="ck-actions-inline" aria-label="Verification status filter">
                  {[
                    ['', 'All'],
                    ['PENDING', 'Pending'],
                    ['NEEDS_CORRECTION', 'Needs correction'],
                    ['COMPLETED', 'Verified'],
                  ].map(([value, label]) => (
                    <button
                      key={value}
                      type="button"
                      className={`ck-btn ck-btn-sm ${filter === value ? 'ck-btn-g' : 'ck-btn-ghost'}`}
                      onClick={() => {
                        setFilter(value);
                        if (status.campaignId) void loadItems(status.campaignId, value);
                      }}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <button className="ck-btn ck-btn-ghost ck-icon-label" onClick={refresh} disabled={!!busy}>
                  <RefreshCw size={15} aria-hidden="true" />Refresh
                </button>
              </div>

              <div className="ck-verification-workspace">
                <div className="ck-verification-list">
                  {items.map((item) => (
                    <button
                      key={item.itemId}
                      type="button"
                      className={selected?.itemId === item.itemId ? 'on' : ''}
                      onClick={() => setSelectedId(item.itemId)}
                    >
                      <span>
                        <strong>{item.studentName}</strong>
                        <small>{item.admissionNo} / {item.className} {item.sectionName}</small>
                      </span>
                      <span className={`ck-status ${item.status === 'COMPLETED' ? 'sgr' : item.status === 'NEEDS_CORRECTION' ? 'sam' : 'sr'}`}>
                        {statusLabel(item.status)}
                      </span>
                    </button>
                  ))}
                  {items.length === 0 ? <div className="ts" style={{ padding: 14 }}>No students in this filter.</div> : null}
                </div>

                <div className="ck-verification-detail">
                  {selected ? (
                    <>
                      <div className="ck-verification-student">
                        <StudentPhotoAvatar
                          photoUrl={selected.photoUrl}
                          name={selected.studentName}
                          className="ck-student-avatar ck-student-avatar-lg"
                          fallbackClassName="ck-student-avatar ck-student-avatar-fallback ck-student-avatar-lg"
                        />
                        <div>
                          <strong>{selected.studentName}</strong>
                          <span>{selected.admissionNo} / {selected.className} {selected.sectionName}</span>
                        </div>
                      </div>

                      {mode === 'profile' ? (
                        <div className="ck-verification-fields">
                          <div><span>Admission no.</span><strong>{selected.admissionNo || '-'}</strong></div>
                          <div><span>Class / section</span><strong>{selected.className} {selected.sectionName}</strong></div>
                          <div><span>Full name</span><strong>{selected.currentFullName || selected.studentName}</strong></div>
                          <div><span>Review status</span><strong>{statusLabel(selected.status)}</strong></div>
                        </div>
                      ) : (
                        <div className="ck-verification-photo-note">
                          <Image size={17} aria-hidden="true" />
                          <span>Confirm the portrait belongs to this student and is usable for ID cards and school views.</span>
                        </div>
                      )}

                      {selected.correctionNotes ? (
                        <div className="ck-alert ck-alert-am"><span>!</span><div>{selected.correctionNotes}</div></div>
                      ) : null}

                      <label className="ck-verification-note">
                        <span>Correction note</span>
                        <textarea
                          rows={3}
                          value={correctionNote}
                          onChange={(event) => setCorrectionNote(event.target.value)}
                          placeholder={mode === 'photo' ? 'Example: wrong student photo or unclear portrait' : 'Example: admission number or contact is wrong'}
                        />
                      </label>
                    </>
                  ) : (
                    <div className="ts">Select a student to review.</div>
                  )}
                </div>
              </div>
            </>
          )}
        </div>

        {status.campaignId ? (
          <div className="ck-modal-foot">
            <button className="ck-btn ck-btn-ghost ck-icon-label" onClick={saveCorrection} disabled={!selected || !correctionNote.trim() || !!busy}>
              <XCircle size={15} aria-hidden="true" />Needs correction
            </button>
            <button className="ck-btn ck-btn-g ck-icon-label" onClick={saveVerified} disabled={!selected || !!busy}>
              <Check size={15} aria-hidden="true" />Mark verified
            </button>
            <button className="ck-btn ck-btn-ghost" onClick={complete} disabled={!!busy || status.completed !== status.totalStudents || status.totalStudents === 0}>
              Complete campaign
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
