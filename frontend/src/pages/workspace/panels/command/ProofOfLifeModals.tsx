/**
 * ProofOfLifeModals — command-centre action modals.
 *
 * Wired modals call real backend endpoints; unlinked stubs carry a
 * "Proof of life · no server write" footer so reviewers can distinguish them.
 */

import React, { useState, useEffect } from 'react';
import { Modal } from '../../../../components/Modal';
import type { CommandCentreCard } from './commandCentreTypes';
import type { PolCode } from './commandCentreTypes';
import type { WorkspaceData } from '../../../../types/workspace';

import {
  sendFeeReminders,
  fetchFeeDefaulters,
  sendPhotographyPaymentReminders,
  fetchClassPhotographyPaymentStatus,
  fetchLowAttendanceSections,
  fetchLowAttendanceStudents,
  sendMeetingInvites,
  fetchVendorDues,
  markCatalogOrderVendorPaid,
  markFirefightingVendorPaid,
  initiateIdCardReview,
  initiateFullNameVerification,
} from '../../../../api/dashboardCommandCenterApi';

import type {
  SendFeeRemindersResult,
  LowAttendanceSectionItem,
  VendorDueItem,
  ClassPhotographyPaymentStatusResponse,
} from '../../../../types/dashboardCommandCenter';

export interface PolModalProps {
  polCode: PolCode;
  card: CommandCentreCard;
  workspace: WorkspaceData;
  onClose: () => void;
  showToast: (t: { ok: boolean; txt: string }) => void;
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Shown on stubs that are not yet wired to a backend endpoint. */
function PolNote() {
  return (
    <span style={{ flex: 1, fontSize: 11, color: 'var(--ink3)', marginRight: 'auto' }}>
      Proof of life · no server write
    </span>
  );
}

/** Inline error banner shown inside a modal (never closes on error). */
function InlineError({ msg }: { msg: string | null }) {
  if (!msg) return null;
  return (
    <div style={{
      background: '#fdecea',
      border: '1px solid #f5c6c4',
      borderRadius: 6,
      padding: '8px 12px',
      fontSize: 12,
      color: 'var(--re, #c0312b)',
      marginTop: 4,
    }}>
      {msg}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FEE_REMINDER — queue fee reminders for overdue families  [WIRED]
// endpoint: POST /dashboard/finance/fee-defaulters/reminders
// ─────────────────────────────────────────────────────────────────────────────

function FeeReminderModal({ card, onClose, showToast }: PolModalProps) {
  const [studentIds, setStudentIds] = useState<number[]>([]);
  const [loadingIds, setLoadingIds] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SendFeeRemindersResult | null>(null);
  const count = card.count ?? 0;

  // Fetch up to 500 defaulters on open to collect student IDs for the batch send.
  useEffect(() => {
    fetchFeeDefaulters({ size: 500 })
      .then(res => setStudentIds(res.items.map(i => i.studentId)))
      .catch(() => { /* non-fatal: fall back to empty list */ })
      .finally(() => setLoadingIds(false));
  }, []);

  async function handleLaunch() {
    setSending(true);
    setError(null);
    try {
      const res = await sendFeeReminders({
        studentIds,
        channel: 'ALL',
        message: 'Fee payment reminder — please clear your outstanding dues at the earliest.',
      });
      setResult(res);
      showToast({ ok: true, txt: `Fee reminders sent to ${res.sentCount} families` });
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to send reminders — please try again.');
      setSending(false);
    }
  }

  const busy = loadingIds || sending;
  const canSend = !busy && studentIds.length > 0;

  return (
    <Modal
      title="Send Fee Reminders"
      subtitle={`${count} students with overdue fees`}
      onClose={onClose}
      disabled={busy}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="ck-btn ck-btn-g" onClick={handleLaunch} disabled={!canSend || sending}>
            {loadingIds
              ? 'Loading…'
              : sending
              ? 'Sending…'
              : result
              ? `Sent to ${result.sentCount}`
              : `Launch reminder run · ${studentIds.length || count} families`}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div className="ck-info-row">
          <span className="ck-label">Channels</span>
          <span className="ck-value">SMS · WhatsApp · Email</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Timing</span>
          <span className="ck-value">Immediate dispatch · best before 6 PM</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Message</span>
          <span className="ck-value">Personalised with student name, outstanding amount, payment link</span>
        </div>
        {(card.amount ?? 0) > 0 && (
          <div className="ck-info-row">
            <span className="ck-label">Total outstanding</span>
            <span className="ck-value" style={{ color: 'var(--g)' }}>₹{((card.amount ?? 0) / 100).toLocaleString('en-IN')}</span>
          </div>
        )}
        <div style={{ background: 'var(--b1, #e8f0fe)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', lineHeight: 1.6, border: '1px solid var(--b3, #c5d8ff)' }}>
          Historical data shows 3× faster recovery when reminders are sent before 6 PM on the overdue date.
        </div>
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FEE_DOWNLOAD — export term collection summary  [no endpoint — deferred]
// ─────────────────────────────────────────────────────────────────────────────

function FeeDownloadModal({ workspace, onClose, showToast }: PolModalProps) {
  const [exporting, setExporting] = useState(false);
  const collected = workspace.fees?.summary?.collected ?? 0;
  const outstanding = workspace.fees?.summary?.outstanding ?? 0;
  const overdueCount = workspace.fees?.summary?.overdueCount ?? workspace.dashboard.feeOverdueCount ?? 0;

  function handleExport() {
    setExporting(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Fee summary export queued — check Downloads' });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Download Fee Summary"
      subtitle="Term-to-date collection report"
      onClose={onClose}
      disabled={exporting}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={exporting}>Cancel</button>
          <button className="ck-btn ck-btn-g" onClick={handleExport} disabled={exporting}>
            {exporting ? 'Preparing export…' : 'Export PDF'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="ck-info-row">
          <span className="ck-label">Collected this term</span>
          <span className="ck-value" style={{ color: 'var(--g)' }}>
            {collected > 0
              ? `₹${(collected / 100).toLocaleString('en-IN')}`
              : workspace.dashboard.feeCollectedLakh
                ? `₹${workspace.dashboard.feeCollectedLakh}L`
                : '—'}
          </span>
        </div>
        {outstanding > 0 && (
          <div className="ck-info-row">
            <span className="ck-label">Outstanding</span>
            <span className="ck-value" style={{ color: 'var(--am)' }}>₹{(outstanding / 100).toLocaleString('en-IN')}</span>
          </div>
        )}
        {overdueCount > 0 && (
          <div className="ck-info-row">
            <span className="ck-label">Overdue students</span>
            <span className="ck-value" style={{ color: 'var(--re)' }}>{overdueCount}</span>
          </div>
        )}
        <div className="ck-info-row">
          <span className="ck-label">Export format</span>
          <span className="ck-value">PDF · class-wise breakdown · payment mode split</span>
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PROFILE_UPLOAD — initiate full-name verification campaign  [WIRED]
// endpoint: POST /dashboard/student-lifecycle/full-name-verification/initiate
// ─────────────────────────────────────────────────────────────────────────────

function ProfileUploadModal({ card, onClose, showToast }: PolModalProps) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleUpload() {
    setSubmitting(true);
    setError(null);
    try {
      const res = await initiateFullNameVerification({ verifier: 'BOTH' });
      showToast({ ok: true, txt: `Full-name verification campaign started — ${res.totalStudents} students` });
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to initiate verification — please try again.');
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title="Initiate Full-Name Verification"
      subtitle={`Verify names for ${card.count ?? 0} student profiles`}
      onClose={onClose}
      disabled={submitting}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={submitting}>Cancel</button>
          <button className="ck-btn ck-btn-b" onClick={handleUpload} disabled={submitting}>
            {submitting ? 'Initiating…' : 'Start verification campaign'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div className="ck-info-row">
          <span className="ck-label">Verifier</span>
          <span className="ck-value">Teacher &amp; Parent (BOTH)</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Scope</span>
          <span className="ck-value">All students · all sections</span>
        </div>
        <div style={{ background: 'var(--b1, #e8f0fe)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', lineHeight: 1.6, border: '1px solid var(--b3, #c5d8ff)' }}>
          Once initiated, teachers and parents will receive requests to verify and correct student full names. Review progress in the Students panel.
        </div>
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PROMOTION_REVIEW — initiate ID card review campaign  [WIRED]
// endpoint: POST /dashboard/student-lifecycle/id-card-review/initiate
// ─────────────────────────────────────────────────────────────────────────────

function PromotionReviewModal({ card, onClose, showToast }: PolModalProps) {
  const [started, setStarted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const total = card.count ?? 0;

  async function handleBegin() {
    setStarted(true);
    setError(null);
    try {
      const res = await initiateIdCardReview({});
      showToast({ ok: true, txt: `ID card review started — ${res.totalStudents} students in campaign` });
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to start review session — please try again.');
      setStarted(false);
    }
  }

  return (
    <Modal
      title="Year-End Promotion Review"
      subtitle={`${total} students eligible for promotion`}
      onClose={onClose}
      disabled={started}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={started}>Cancel</button>
          <button className="ck-btn ck-btn-b" onClick={handleBegin} disabled={started}>
            {started ? 'Initiating…' : 'Begin review session'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="ck-info-row">
          <span className="ck-label">Eligible students</span>
          <span className="ck-value" style={{ color: 'var(--g)' }}>{total}</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Campaign type</span>
          <span className="ck-value">ID card data review · all sections</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Promotion criteria</span>
          <span className="ck-value">≥75% attendance · pass in core subjects · no pending dues</span>
        </div>
        <div style={{ background: 'var(--b1, #e8f0fe)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', border: '1px solid var(--b3, #c5d8ff)' }}>
          Once started, teachers can review each student's ID card data. Progress is visible in the Students panel.
        </div>
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PROMOTION_EXCEPTIONS — hardcoded fixture  [no real endpoint — deferred]
// ─────────────────────────────────────────────────────────────────────────────

function PromotionExceptionsModal({ onClose, showToast }: PolModalProps) {
  const [noted, setNoted] = useState(false);

  const exceptions = [
    { name: 'Aarav Sharma',  cls: '9-A',  reason: 'Attendance 68% · below threshold' },
    { name: 'Priya Patel',   cls: '8-B',  reason: 'Pending ₹12,000 dues' },
    { name: 'Rohan Gupta',   cls: '7-C',  reason: 'Failed Mathematics — needs re-exam' },
  ];

  function handleNote() {
    setNoted(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Exceptions flagged for class teacher review' });
      onClose();
    }, 700);
  }

  return (
    <Modal
      title="Promotion Exceptions"
      subtitle="Students who cannot be auto-promoted"
      onClose={onClose}
      disabled={noted}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={noted}>Close</button>
          <button className="ck-btn ck-btn-b" onClick={handleNote} disabled={noted}>
            {noted ? 'Noting…' : 'Flag for class teacher review'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {exceptions.map(ex => (
          <div key={ex.name} style={{ padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div style={{ fontWeight: 600, fontSize: 13 }}>{ex.name} <span style={{ color: 'var(--ink3)', fontWeight: 400 }}>· {ex.cls}</span></div>
            <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{ex.reason}</div>
          </div>
        ))}
        <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 4 }}>Showing 3 of 8. Open the Students panel to see all.</div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER_VALUE — show submitted order breakdown  [read-only view; no action]
// ─────────────────────────────────────────────────────────────────────────────

function OrderValueModal({ workspace, onClose }: PolModalProps) {
  const submitted = workspace.orders?.filter(o => o.status === 'SUBMITTED') ?? [];
  const total = submitted.reduce((sum, o) => sum + (o.totalAmount ?? o.subtotal ?? 0), 0);

  return (
    <Modal
      title="Pending Order Value"
      subtitle={`${submitted.length} orders awaiting approval`}
      onClose={onClose}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {submitted.length === 0 ? (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No submitted orders in current workspace data.</div>
        ) : (
          <>
            {submitted.map(o => (
              <div key={o.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
                <div>
                  <div style={{ fontWeight: 600, fontSize: 13 }}>{o.category}</div>
                  <div style={{ fontSize: 11, color: 'var(--ink3)' }}>{o.description ?? o.title ?? o.id}</div>
                </div>
                <div style={{ fontWeight: 600, color: 'var(--g)', fontSize: 13 }}>₹{(o.totalAmount ?? o.subtotal ?? 0).toLocaleString('en-IN')}</div>
              </div>
            ))}
            <div style={{ display: 'flex', justifyContent: 'space-between', paddingTop: 10, borderTop: '1px solid var(--border)', marginTop: 4 }}>
              <span style={{ fontWeight: 600, fontSize: 13 }}>Total</span>
              <span style={{ fontWeight: 700, color: 'var(--g)', fontSize: 14 }}>₹{total.toLocaleString('en-IN')}</span>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER_FOLLOWUP — schedule vendor follow-up  [no endpoint — deferred]
// ─────────────────────────────────────────────────────────────────────────────

function OrderFollowupModal({ workspace, onClose, showToast }: PolModalProps) {
  const [sending, setSending] = useState(false);
  const approved = workspace.orders?.filter(o => o.status === 'APPROVED') ?? [];

  function handleSend() {
    setSending(true);
    setTimeout(() => {
      showToast({ ok: true, txt: `Follow-up scheduled for ${approved.length} vendor${approved.length !== 1 ? 's' : ''}` });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Follow Up with Vendor"
      subtitle={`${approved.length} approved order${approved.length !== 1 ? 's' : ''} not yet fulfilled`}
      onClose={onClose}
      disabled={sending}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={sending}>Cancel</button>
          <button className="ck-btn ck-btn-b" onClick={handleSend} disabled={sending}>
            {sending ? 'Scheduling…' : 'Send follow-up emails'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="ck-info-row">
          <span className="ck-label">Orders to follow up</span>
          <span className="ck-value" style={{ color: 'var(--am)' }}>{approved.length}</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Email template</span>
          <span className="ck-value">Vendor dispatch request with order ref + SLA reminder</span>
        </div>
        <div style={{ background: 'var(--bg2)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', lineHeight: 1.6, border: '1px solid var(--border)' }}>
          <b>Preview:</b> "Dear Vendor, this is a follow-up for order [REF]. Kindly confirm the dispatch date. Our expected delivery window per SLA is [DATE]. — Custoking Supply Team"
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER_ESCALATE — escalate to Custoking ops  [no endpoint — deferred]
// ─────────────────────────────────────────────────────────────────────────────

function OrderEscalateModal({ onClose, showToast }: PolModalProps) {
  const [escalating, setEscalating] = useState(false);

  function handleEscalate() {
    setEscalating(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Order escalated to Custoking supply team' });
      onClose();
    }, 800);
  }

  return (
    <Modal
      title="Escalate Delayed Order"
      subtitle="Notify Custoking supply operations team"
      onClose={onClose}
      disabled={escalating}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={escalating}>Cancel</button>
          <button className="ck-btn ck-btn-re" onClick={handleEscalate} disabled={escalating}>
            {escalating ? 'Escalating…' : 'Escalate to Custoking ops'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="ck-info-row">
          <span className="ck-label">Escalation route</span>
          <span className="ck-value">Custoking Supply Operations · SLA breach alert</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Response SLA</span>
          <span className="ck-value">4 business hours</span>
        </div>
        <div style={{ background: '#fdecea', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--re, #c0312b)', border: '1px solid var(--re2, #f5c6c4)' }}>
          Use only after the standard follow-up has gone unanswered for 48h.
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER_VENDOR_PAID — mark catalog orders as vendor-paid  [WIRED]
// endpoint: POST /dashboard/vendor-dues/catalog-orders/{id}/mark-paid
// ─────────────────────────────────────────────────────────────────────────────

function OrderVendorPaidModal({ onClose, showToast }: PolModalProps) {
  const [items, setItems] = useState<VendorDueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<string | null>(null);

  useEffect(() => {
    fetchVendorDues()
      .then(res => setItems(res.items.filter(i => i.sourceType === 'CATALOG_ORDER')))
      .catch(() => setError('Failed to load vendor dues — please close and retry.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleMarkPaid(orderId: string) {
    setMarkingId(orderId);
    setError(null);
    try {
      await markCatalogOrderVendorPaid(orderId);
      setItems(prev => prev.filter(i => i.id !== orderId));
      showToast({ ok: true, txt: 'Order marked as vendor-paid' });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to mark order as paid — please try again.');
    } finally {
      setMarkingId(null);
    }
  }

  return (
    <Modal
      title="Mark Catalog Orders Vendor-Paid"
      subtitle={loading ? 'Loading…' : `${items.length} order${items.length !== 1 ? 's' : ''} with vendor payment due`}
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {loading && <div style={{ color: 'var(--ink3)', fontSize: 13 }}>Loading vendor dues…</div>}
        {!loading && items.length === 0 && !error && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No catalog orders with pending vendor payment.</div>
        )}
        {items.map(item => (
          <div key={item.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{item.title}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>
                {item.category}{item.vendorName ? ` · ${item.vendorName}` : ''}
              </div>
              <div style={{ fontSize: 12, color: 'var(--g)', fontWeight: 600, marginTop: 2 }}>
                ₹{(item.amountPaise / 100).toLocaleString('en-IN')}
              </div>
            </div>
            <button
              className="ck-btn ck-btn-g"
              style={{ fontSize: 12, padding: '5px 12px' }}
              onClick={() => handleMarkPaid(item.id)}
              disabled={markingId === item.id}
            >
              {markingId === item.id ? 'Marking…' : 'Mark Paid'}
            </button>
          </div>
        ))}
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FF_QUOTATION_ADD — upload quotation for an open request  [no endpoint — deferred]
// ─────────────────────────────────────────────────────────────────────────────

function FfQuotationAddModal({ workspace, onClose, showToast }: PolModalProps) {
  const [uploading, setUploading] = useState(false);
  const openReqs = workspace.firefighting?.requests?.filter(r => r.status === 'OPEN') ?? [];

  function handleSave() {
    setUploading(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Quotation saved as draft — request moved to In Review' });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Add Quotation"
      subtitle={`${openReqs.length} open request${openReqs.length !== 1 ? 's' : ''} awaiting quotation`}
      onClose={onClose}
      disabled={uploading}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={uploading}>Cancel</button>
          <button className="ck-btn ck-btn-re" onClick={handleSave} disabled={uploading}>
            {uploading ? 'Saving…' : 'Save quotation as draft'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {openReqs.length > 0 && (
          <div className="ck-field">
            <label htmlFor="ff-req-select">Request</label>
            <select id="ff-req-select">
              {openReqs.map(r => (
                <option key={r.code} value={r.code}>{r.code} · {r.title}</option>
              ))}
            </select>
          </div>
        )}
        <div className="ck-field">
          <label htmlFor="ff-vendor">Vendor name</label>
          <input id="ff-vendor" type="text" placeholder="e.g. SafeGuard Fire Solutions" />
        </div>
        <div className="ck-field">
          <label htmlFor="ff-amount">Quoted amount (₹)</label>
          <input id="ff-amount" type="number" placeholder="e.g. 84000" min={0} />
        </div>
        <div className="ck-field">
          <label htmlFor="ff-delivery">Estimated delivery</label>
          <input id="ff-delivery" type="text" placeholder="e.g. 7 working days from approval" />
        </div>
        <div style={{ border: '2px dashed var(--border)', borderRadius: 8, padding: '20px 16px', textAlign: 'center', color: 'var(--ink3)', fontSize: 12 }}>
          📎 Attach quotation document (PDF / image)
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FF_QUOTATION_VIEW — view quotations for in-review requests  [read-only view]
// ─────────────────────────────────────────────────────────────────────────────

function FfQuotationViewModal({ workspace, onClose }: PolModalProps) {
  const inReview = workspace.firefighting?.requests?.filter(r => r.status === 'IN_REVIEW') ?? [];

  return (
    <Modal
      title="View Quotations"
      subtitle={`${inReview.length} request${inReview.length !== 1 ? 's' : ''} in review`}
      onClose={onClose}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {inReview.length === 0 && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>
            No requests currently in review. Check the Firefighting panel for current status.
          </div>
        )}
        {inReview.map(req => {
          const q = req.quotations?.[0];
          return (
            <div key={req.code} style={{ padding: '12px 14px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{req.code} · {req.title}</div>
              {q ? (
                <>
                  <div style={{ fontSize: 12, color: 'var(--ink2)' }}>Vendor: <b>{q.vendorName}</b></div>
                  <div style={{ fontSize: 12, color: 'var(--g)', fontWeight: 600 }}>₹{Number(q.amount).toLocaleString('en-IN')} · {q.deliveryTimeline}</div>
                  {q.notes && <div style={{ fontSize: 11, color: 'var(--ink3)' }}>{q.notes}</div>}
                </>
              ) : (
                <div style={{ fontSize: 12, color: 'var(--am)' }}>No quotation attached yet</div>
              )}
            </div>
          );
        })}
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FF_VENDOR_PAID — mark firefighting requests as vendor-paid  [WIRED]
// endpoint: POST /dashboard/vendor-dues/firefighting/{code}/mark-paid
// Type note: VendorDueItem.id is assumed to carry the FF request code for
// FIREFIGHTING items — this matches the markFirefightingVendorPaid(code) param.
// ─────────────────────────────────────────────────────────────────────────────

function FfVendorPaidModal({ onClose, showToast }: PolModalProps) {
  const [items, setItems] = useState<VendorDueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<string | null>(null);

  useEffect(() => {
    fetchVendorDues()
      .then(res => setItems(res.items.filter(i => i.sourceType === 'FIREFIGHTING')))
      .catch(() => setError('Failed to load vendor dues — please close and retry.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleMarkPaid(code: string) {
    setMarkingId(code);
    setError(null);
    try {
      await markFirefightingVendorPaid(code);
      setItems(prev => prev.filter(i => i.id !== code));
      showToast({ ok: true, txt: 'Firefighting request marked as vendor-paid' });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to mark as paid — please try again.');
    } finally {
      setMarkingId(null);
    }
  }

  return (
    <Modal
      title="Mark FF Requests Vendor-Paid"
      subtitle={loading ? 'Loading…' : `${items.length} request${items.length !== 1 ? 's' : ''} with vendor payment due`}
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {loading && <div style={{ color: 'var(--ink3)', fontSize: 13 }}>Loading vendor dues…</div>}
        {!loading && items.length === 0 && !error && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No firefighting requests with pending vendor payment.</div>
        )}
        {items.map(item => (
          <div key={item.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{item.title}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>
                {item.category}{item.vendorName ? ` · ${item.vendorName}` : ''}
              </div>
              <div style={{ fontSize: 12, color: 'var(--g)', fontWeight: 600, marginTop: 2 }}>
                ₹{(item.amountPaise / 100).toLocaleString('en-IN')}
              </div>
            </div>
            <button
              className="ck-btn ck-btn-g"
              style={{ fontSize: 12, padding: '5px 12px' }}
              onClick={() => handleMarkPaid(item.id)}
              disabled={markingId === item.id}
            >
              {markingId === item.id ? 'Marking…' : 'Mark Paid'}
            </button>
          </div>
        ))}
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE_SECTIONS — sections with low attendance + per-section invite  [WIRED]
// data: GET /dashboard/attendance/low-sections
// action: GET /dashboard/attendance/sections/{id}/low-students
//         POST /dashboard/attendance/meeting-invites
// ─────────────────────────────────────────────────────────────────────────────

function AttendanceSectionsModal({ onClose, showToast }: PolModalProps) {
  const [sections, setSections] = useState<LowAttendanceSectionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sendingSection, setSendingSection] = useState<string | null>(null);

  useEffect(() => {
    fetchLowAttendanceSections()
      .then(res => setSections(res.sections))
      .catch(() => setError('Failed to load attendance data — please close and retry.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleSendInvites(sectionId: string) {
    setSendingSection(sectionId);
    setError(null);
    try {
      const students = await fetchLowAttendanceStudents(sectionId);
      const result = await sendMeetingInvites({
        studentIds: students.map(s => s.studentId),
        channel: 'ALL',
        message: 'Please attend a meeting regarding attendance concerns.',
      });
      showToast({ ok: true, txt: `Meeting invites sent to ${result.sentCount} parents` });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to send invites — please try again.');
    } finally {
      setSendingSection(null);
    }
  }

  return (
    <Modal
      title="Sections with Low Attendance"
      subtitle="Classes below the attendance threshold today"
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {loading && <div style={{ color: 'var(--ink3)', fontSize: 13 }}>Loading attendance data…</div>}
        {!loading && sections.length === 0 && !error && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No sections below attendance threshold today.</div>
        )}
        {sections.map(s => (
          <div key={s.sectionId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{s.className} {s.sectionName}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>
                {s.presentCount}/{s.totalEnrolled} present · {s.studentsBelowThreshold} below threshold
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: s.attendancePct < 80 ? 'var(--re, #c0312b)' : 'var(--am, #b35c00)' }}>
                {s.attendancePct.toFixed(1)}%
              </span>
              <button
                className="ck-btn ck-btn-b"
                style={{ fontSize: 11, padding: '4px 10px' }}
                onClick={() => handleSendInvites(s.sectionId)}
                disabled={sendingSection === s.sectionId}
              >
                {sendingSection === s.sectionId ? 'Sending…' : 'Invite'}
              </button>
            </div>
          </div>
        ))}
        <InlineError msg={error} />
        {!loading && sections.length > 0 && (
          <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 4 }}>
            "Invite" sends meeting invites to parents of low-attendance students in that section.
          </div>
        )}
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE_LOW — low-attendance breakdown + per-section meeting invite  [WIRED]
// data: GET /dashboard/attendance/low-sections
// action: GET /dashboard/attendance/sections/{id}/low-students
//         POST /dashboard/attendance/meeting-invites
// ─────────────────────────────────────────────────────────────────────────────

function AttendanceLowModal({ workspace, onClose, showToast }: PolModalProps) {
  const [sections, setSections] = useState<LowAttendanceSectionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sendingSection, setSendingSection] = useState<string | null>(null);
  const pct = workspace.dashboard.attendancePercent ?? 0;

  useEffect(() => {
    fetchLowAttendanceSections()
      .then(res => setSections(res.sections))
      .catch(() => setError('Failed to load attendance data — please close and retry.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleScheduleMeeting(sectionId: string) {
    setSendingSection(sectionId);
    setError(null);
    try {
      const students = await fetchLowAttendanceStudents(sectionId);
      const result = await sendMeetingInvites({
        studentIds: students.map(s => s.studentId),
        channel: 'ALL',
        message: 'Please attend a meeting regarding low attendance concerns.',
      });
      showToast({ ok: true, txt: `Meeting invites sent to ${result.sentCount} parents` });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to schedule meeting — please try again.');
    } finally {
      setSendingSection(null);
    }
  }

  return (
    <Modal
      title="Low Attendance Report"
      subtitle={`School average: ${pct}% (threshold: 88%)`}
      onClose={onClose}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ background: '#fff8e1', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: '#7a5500', border: '1px solid #ffe082' }}>
          School attendance today is {pct}%. Sections below threshold are shown below.
        </div>
        {loading && <div style={{ color: 'var(--ink3)', fontSize: 13 }}>Loading…</div>}
        {!loading && sections.length === 0 && !error && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No sections below attendance threshold today.</div>
        )}
        {sections.map(s => (
          <div key={s.sectionId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{s.className} {s.sectionName}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>
                {s.totalEnrolled} enrolled · {s.studentsBelowThreshold} below threshold
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: s.attendancePct < 80 ? 'var(--re, #c0312b)' : 'var(--am, #b35c00)' }}>
                {s.attendancePct.toFixed(1)}%
              </span>
              <button
                className="ck-btn ck-btn-am"
                style={{ fontSize: 11, padding: '4px 10px' }}
                onClick={() => handleScheduleMeeting(s.sectionId)}
                disabled={sendingSection === s.sectionId}
              >
                {sendingSection === s.sectionId ? 'Sending…' : 'Meeting'}
              </button>
            </div>
          </div>
        ))}
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PARENT_NOTIFY — photography payment reminders  [WIRED]
// data: GET /dashboard/events/class-photography/payment-status
// action: POST /dashboard/events/{eventId}/payment-reminders
// ─────────────────────────────────────────────────────────────────────────────

function ParentNotifyModal({ onClose, showToast }: PolModalProps) {
  const [photoStatus, setPhotoStatus] = useState<ClassPhotographyPaymentStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchClassPhotographyPaymentStatus({ size: 500 })
      .then(res => setPhotoStatus(res))
      .catch(() => setError('Failed to load photography payment status — please close and retry.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleSend() {
    if (!photoStatus?.eventId) return;
    const pendingIds = photoStatus.students
      .filter(s => s.status !== 'PAID')
      .map(s => s.studentId);
    setSending(true);
    setError(null);
    try {
      const res = await sendPhotographyPaymentReminders(photoStatus.eventId, {
        studentIds: pendingIds,
        channel: 'ALL',
        message: 'Photography payment reminder — please complete your payment at the earliest.',
      });
      showToast({ ok: true, txt: `Photography payment reminders sent to ${res.sentCount} families` });
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to send reminders — please try again.');
      setSending(false);
    }
  }

  const pendingCount = photoStatus?.students.filter(s => s.status !== 'PAID').length ?? 0;
  const hasEvent = !!photoStatus?.eventId;
  const busy = loading || sending;

  return (
    <Modal
      title="Photography Payment Reminders"
      subtitle={loading ? 'Loading…' : `${pendingCount} families with pending photography payment`}
      onClose={onClose}
      disabled={busy}
      footer={
        <>
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={sending}>Cancel</button>
          <button
            className="ck-btn ck-btn-am"
            onClick={handleSend}
            disabled={busy || !hasEvent || pendingCount === 0}
          >
            {loading ? 'Loading…' : sending ? 'Sending…' : `Send to ${pendingCount} families`}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {!loading && !hasEvent && !error && (
          <div style={{ color: 'var(--ink3)', fontSize: 13 }}>No active photography event found.</div>
        )}
        {photoStatus && (
          <>
            <div className="ck-info-row">
              <span className="ck-label">Event</span>
              <span className="ck-value">{photoStatus.title ?? 'Class Photography'}</span>
            </div>
            <div className="ck-info-row">
              <span className="ck-label">Collected</span>
              <span className="ck-value" style={{ color: 'var(--g)' }}>
                ₹{(photoStatus.collectedAmount / 100).toLocaleString('en-IN')}
              </span>
            </div>
            <div className="ck-info-row">
              <span className="ck-label">Pending</span>
              <span className="ck-value" style={{ color: 'var(--am)' }}>
                ₹{(photoStatus.pendingAmount / 100).toLocaleString('en-IN')}
              </span>
            </div>
            <div className="ck-info-row">
              <span className="ck-label">Channels</span>
              <span className="ck-value">SMS · WhatsApp</span>
            </div>
          </>
        )}
        <InlineError msg={error} />
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Router — dispatches polCode to the correct modal component
// ─────────────────────────────────────────────────────────────────────────────

export function ProofOfLifeModal(props: PolModalProps) {
  switch (props.polCode) {
    case 'FEE_REMINDER':           return <FeeReminderModal {...props} />;
    case 'FEE_DOWNLOAD':           return <FeeDownloadModal {...props} />;
    case 'PROFILE_UPLOAD':         return <ProfileUploadModal {...props} />;
    case 'PROMOTION_REVIEW':       return <PromotionReviewModal {...props} />;
    case 'PROMOTION_EXCEPTIONS':   return <PromotionExceptionsModal {...props} />;
    case 'ORDER_VALUE':            return <OrderValueModal {...props} />;
    case 'ORDER_FOLLOWUP':         return <OrderFollowupModal {...props} />;
    case 'ORDER_ESCALATE':         return <OrderEscalateModal {...props} />;
    case 'ORDER_VENDOR_PAID':      return <OrderVendorPaidModal {...props} />;
    case 'FF_QUOTATION_ADD':       return <FfQuotationAddModal {...props} />;
    case 'FF_QUOTATION_VIEW':      return <FfQuotationViewModal {...props} />;
    case 'FF_VENDOR_PAID':         return <FfVendorPaidModal {...props} />;
    case 'ATTENDANCE_SECTIONS':    return <AttendanceSectionsModal {...props} />;
    case 'ATTENDANCE_LOW':         return <AttendanceLowModal {...props} />;
    case 'PARENT_NOTIFY':          return <ParentNotifyModal {...props} />;
    default:                        return null;
  }
}
