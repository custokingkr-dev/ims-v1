/**
 * ProofOfLifeModals — isolated proof-of-life (POL) flows for the Command Centre.
 *
 * Each modal simulates a real admin workflow with meaningful UI, but does NOT
 * write to the backend. All state changes are local (React state + toast only).
 *
 * Every modal footer carries a "Proof of life · no server write" note so
 * reviewers can distinguish real from simulated actions at a glance.
 */

import React, { useState } from 'react';
import { Modal } from '../../../../components/Modal';
import type { CommandCentreCard } from './commandCentreTypes';
import type { PolCode } from './commandCentreTypes';
import type { WorkspaceData } from '../../../../types/workspace';

export interface PolModalProps {
  polCode: PolCode;
  card: CommandCentreCard;
  workspace: WorkspaceData;
  onClose: () => void;
  showToast: (t: { ok: boolean; txt: string }) => void;
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared footer note
// ─────────────────────────────────────────────────────────────────────────────

function PolNote() {
  return (
    <span style={{ flex: 1, fontSize: 11, color: 'var(--ink3)', marginRight: 'auto' }}>
      Proof of life · no server write
    </span>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FEE_REMINDER — queue fee reminders for overdue families
// ─────────────────────────────────────────────────────────────────────────────

function FeeReminderModal({ card, onClose, showToast }: PolModalProps) {
  const [sent, setSent] = useState(false);
  const count = card.count ?? 0;

  function handleLaunch() {
    setSent(true);
    setTimeout(() => {
      showToast({ ok: true, txt: `Fee reminders queued for ${count} families` });
      onClose();
    }, 800);
  }

  return (
    <Modal
      title="Send Fee Reminders"
      subtitle={`${count} students with overdue fees`}
      onClose={onClose}
      disabled={sent}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={sent}>Cancel</button>
          <button className="ck-btn ck-btn-g" onClick={handleLaunch} disabled={sent}>
            {sent ? 'Queuing…' : `Launch reminder run · ${count} families`}
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
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// FEE_DOWNLOAD — export term collection summary
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
// PROFILE_UPLOAD — batch photo upload stub
// ─────────────────────────────────────────────────────────────────────────────

function ProfileUploadModal({ card, onClose, showToast }: PolModalProps) {
  const [uploading, setUploading] = useState(false);

  function handleUpload() {
    setUploading(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Photo upload initiated — processing in background' });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Upload Student Photos"
      subtitle={`Batch upload for ${card.count ?? 23} incomplete profiles`}
      onClose={onClose}
      disabled={uploading}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={uploading}>Cancel</button>
          <button className="ck-btn ck-btn-b" onClick={handleUpload} disabled={uploading}>
            {uploading ? 'Initiating…' : 'Start upload'}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ border: '2px dashed var(--border)', borderRadius: 8, padding: '32px 16px', textAlign: 'center', color: 'var(--ink3)', fontSize: 13 }}>
          <div style={{ fontSize: 28, marginBottom: 8 }}>📷</div>
          <div>Drag &amp; drop photos here, or click to browse</div>
          <div style={{ fontSize: 11, marginTop: 6 }}>JPEG or PNG · max 2 MB per photo · named by admission number</div>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Missing photos</span>
          <span className="ck-value" style={{ color: 'var(--am)' }}>{card.count ?? 23} students</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Naming format</span>
          <span className="ck-value">ADMISSION_NO.jpg — e.g. 2024-0042.jpg</span>
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PROMOTION_REVIEW — initiate year-end promotion session
// ─────────────────────────────────────────────────────────────────────────────

function PromotionReviewModal({ card, onClose, showToast }: PolModalProps) {
  const [started, setStarted] = useState(false);
  const total = card.count ?? 412;

  function handleBegin() {
    setStarted(true);
    setTimeout(() => {
      showToast({ ok: true, txt: 'Promotion review session started — check Students panel' });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Year-End Promotion Review"
      subtitle={`${total} students eligible for promotion`}
      onClose={onClose}
      disabled={started}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={started}>Cancel</button>
          <button className="ck-btn ck-btn-b" onClick={handleBegin} disabled={started}>
            {started ? 'Opening session…' : 'Begin review session'}
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
          <span className="ck-label">Exceptions requiring review</span>
          <span className="ck-value" style={{ color: 'var(--am)' }}>8 students</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Promotion criteria</span>
          <span className="ck-value">≥75% attendance · pass in core subjects · no pending dues</span>
        </div>
        <div style={{ background: 'var(--b1, #e8f0fe)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', border: '1px solid var(--b3, #c5d8ff)' }}>
          Once all exceptions are reviewed, you can bulk-promote the eligible batch with one click.
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PROMOTION_EXCEPTIONS — review students that can't be auto-promoted
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
// ORDER_VALUE — show submitted order breakdown
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
// ORDER_FOLLOWUP — schedule vendor follow-up email
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
// ORDER_ESCALATE — escalate to Custoking ops
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
// FF_QUOTATION_ADD — upload quotation for an open request
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
// FF_QUOTATION_VIEW — view quotations for in-review requests
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
// ATTENDANCE_SECTIONS — list sections that haven't marked attendance
// ─────────────────────────────────────────────────────────────────────────────

function AttendanceSectionsModal({ onClose }: PolModalProps) {
  const sections = [
    { name: '8-A',  teacher: 'Ms. Priya Rao',    since: '9:15 AM' },
    { name: '9-C',  teacher: 'Mr. Kiran Mehta',   since: '9:20 AM' },
    { name: '10-B', teacher: 'Ms. Sunita Gupta',  since: '9:10 AM' },
    { name: '11-A', teacher: 'Mr. Rahul Das',     since: '9:05 AM' },
  ];

  return (
    <Modal
      title="Sections Pending Attendance"
      subtitle="Classes that have not marked today's attendance"
      onClose={onClose}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {sections.map(s => (
          <div key={s.name} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>Section {s.name}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>{s.teacher}</div>
            </div>
            <span style={{ fontSize: 11, color: 'var(--am)', fontWeight: 500 }}>Pending since {s.since}</span>
          </div>
        ))}
        <div style={{ fontSize: 11, color: 'var(--ink3)', marginTop: 4 }}>
          Go to the Attendance panel to mark or send a reminder to the class teacher.
        </div>
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE_LOW — low-attendance section breakdown
// ─────────────────────────────────────────────────────────────────────────────

function AttendanceLowModal({ workspace, onClose }: PolModalProps) {
  const pct = workspace.dashboard.attendancePercent ?? 0;

  const lowSections = [
    { name: '11-B', pct: 72, students: 38, teacher: 'Mr. Anil Kumar' },
    { name: '10-A', pct: 76, students: 42, teacher: 'Ms. Divya Nair' },
    { name: '8-C',  pct: 78, students: 35, teacher: 'Mr. Suresh Pillai' },
  ];

  return (
    <Modal
      title="Low Attendance Report"
      subtitle={`School average: ${pct}% (threshold: 88%)`}
      onClose={onClose}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose}>Close</button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ background: '#fff8e1', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: '#7a5500', border: '1px solid #ffe082' }}>
          School attendance today is {pct}%. Sections below 80% are highlighted in red.
        </div>
        {lowSections.map(s => (
          <div key={s.name} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', borderRadius: 6, background: 'var(--bg2)', border: '1px solid var(--border)' }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>Section {s.name} · {s.teacher}</div>
              <div style={{ fontSize: 11, color: 'var(--ink3)' }}>{s.students} students enrolled</div>
            </div>
            <span style={{ fontSize: 12, fontWeight: 700, color: s.pct < 80 ? 'var(--re, #c0312b)' : 'var(--am, #b35c00)' }}>{s.pct}%</span>
          </div>
        ))}
      </div>
    </Modal>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// PARENT_NOTIFY — queue absence notifications to parents
// ─────────────────────────────────────────────────────────────────────────────

function ParentNotifyModal({ workspace, onClose, showToast }: PolModalProps) {
  const [sending, setSending] = useState(false);
  const pct = workspace.dashboard.attendancePercent ?? 0;
  const total = workspace.dashboard.students ?? 0;
  const absent = Math.round(total * (1 - pct / 100));

  function handleSend() {
    setSending(true);
    setTimeout(() => {
      showToast({ ok: true, txt: `Attendance notifications queued for ${absent} parents` });
      onClose();
    }, 900);
  }

  return (
    <Modal
      title="Notify Parents — Absent Students"
      subtitle={`${absent} students absent today`}
      onClose={onClose}
      disabled={sending}
      footer={
        <>
          <PolNote />
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={sending}>Cancel</button>
          <button className="ck-btn ck-btn-am" onClick={handleSend} disabled={sending}>
            {sending ? 'Queueing…' : `Send to ${absent} parents`}
          </button>
        </>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="ck-info-row">
          <span className="ck-label">Today's attendance</span>
          <span className="ck-value" style={{ color: 'var(--am)' }}>{pct}%</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Absent students</span>
          <span className="ck-value" style={{ color: 'var(--re)' }}>{absent}</span>
        </div>
        <div className="ck-info-row">
          <span className="ck-label">Channels</span>
          <span className="ck-value">SMS · WhatsApp</span>
        </div>
        <div style={{ background: 'var(--bg2)', borderRadius: 6, padding: '10px 14px', fontSize: 12, color: 'var(--ink2)', lineHeight: 1.6, border: '1px solid var(--border)' }}>
          <b>Preview:</b> "Dear Parent, [Student Name] was marked absent today ({new Date().toLocaleDateString('en-IN')}). Please contact the school if this is an error. — {workspace.school.name}"
        </div>
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
    case 'FF_QUOTATION_ADD':       return <FfQuotationAddModal {...props} />;
    case 'FF_QUOTATION_VIEW':      return <FfQuotationViewModal {...props} />;
    case 'ATTENDANCE_SECTIONS':    return <AttendanceSectionsModal {...props} />;
    case 'ATTENDANCE_LOW':         return <AttendanceLowModal {...props} />;
    case 'PARENT_NOTIFY':          return <ParentNotifyModal {...props} />;
    default:                        return null;
  }
}
