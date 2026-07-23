import { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import {
  fetchLowAttendanceSections,
  fetchLowAttendanceStudents,
  sendMeetingInvites,
} from '../../../../api/dashboardCommandCenterApi';
import type {
  LowAttendanceSectionItem,
  LowAttendanceStudentItem,
  LowAttendanceSectionsResponse,
} from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
}

const CHANNELS = ['SMS', 'WhatsApp', 'Email', 'Push'];

// ── Progress bar ──────────────────────────────────────────────────────────────

function AttendanceBar({ pct }: { pct: number }) {
  const color = pct < 60 ? '#c0312b' : pct < 75 ? '#b35c00' : '#1a6840';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{ flex: 1, background: '#e8eaf6', borderRadius: 4, height: 6, overflow: 'hidden' }}>
        <div style={{ width: `${Math.min(100, pct)}%`, height: '100%', background: color, transition: 'width .3s' }} />
      </div>
      <span style={{ fontSize: 12, fontWeight: 600, color, minWidth: 36 }}>{pct.toFixed(0)}%</span>
    </div>
  );
}

// ── Confirm modal ─────────────────────────────────────────────────────────────

interface ConfirmModalProps {
  selected: LowAttendanceStudentItem[];
  onClose: () => void;
  onSent: (sentCount: number) => void;
}

function ConfirmInviteModal({ selected, onClose, onSent }: ConfirmModalProps) {
  const [channel, setChannel] = useState('SMS');
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSend = async () => {
    if (!message.trim()) { setError('Message is required.'); return; }
    setSending(true);
    setError(null);
    try {
      const result = await sendMeetingInvites({
        studentIds: selected.map(s => s.studentId),
        channel,
        message: message.trim(),
      });
      onSent(result.sentCount);
    } catch {
      setError('Failed to send invites. Please try again.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', zIndex: 2000,
                  display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#fff', borderRadius: 12, padding: 24, width: 420, maxWidth: '90vw',
                    boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}>
        <h3 style={{ margin: '0 0 8px', fontSize: 16, fontWeight: 700, color: '#1a2352' }}>
          Send Meeting Invites
        </h3>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: '#555' }}>
          Sending to <strong>{selected.length}</strong> parent{selected.length !== 1 ? 's' : ''}.
        </p>

        <div style={{ marginBottom: 12 }}>
          <label style={{ fontSize: 12, fontWeight: 600, color: '#333', display: 'block', marginBottom: 4 }}>
            Channel
          </label>
          <div style={{ display: 'flex', gap: 8 }}>
            {CHANNELS.map(c => (
              <button key={c} onClick={() => setChannel(c)}
                style={{ padding: '5px 12px', borderRadius: 20, border: '1px solid #c5cae9',
                         background: channel === c ? '#3949ab' : '#fff',
                         color: channel === c ? '#fff' : '#3949ab',
                         cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
                {c}
              </button>
            ))}
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 12, fontWeight: 600, color: '#333', display: 'block', marginBottom: 4 }}>
            Message
          </label>
          <textarea
            value={message}
            onChange={e => setMessage(e.target.value)}
            rows={4}
            placeholder="Dear parent, your child's attendance is below 75%. Please schedule a meeting..."
            style={{ width: '100%', padding: '8px 10px', border: '1px solid #c5cae9', borderRadius: 6,
                     fontSize: 13, resize: 'vertical', boxSizing: 'border-box' }}
          />
        </div>

        {error && (
          <div style={{ marginBottom: 12, fontSize: 12, color: '#c0312b', background: '#fde8e8',
                        padding: '6px 10px', borderRadius: 4 }}>
            {error}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button onClick={onClose}
            style={{ padding: '8px 16px', border: '1px solid #c5cae9', borderRadius: 6,
                     cursor: 'pointer', fontSize: 13, background: '#fff' }}>
            Cancel
          </button>
          <button onClick={handleSend} disabled={sending || !message.trim()}
            style={{ padding: '8px 16px', border: 'none', borderRadius: 6,
                     background: sending || !message.trim() ? '#c5cae9' : '#b35c00',
                     color: '#fff', cursor: sending || !message.trim() ? 'default' : 'pointer',
                     fontWeight: 600, fontSize: 13 }}>
            {sending ? 'Sending…' : `Send ${selected.length} Invite${selected.length !== 1 ? 's' : ''}`}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Section detail panel ──────────────────────────────────────────────────────

function SectionDetail({
  section,
  canSend,
  onBack,
  onInvitesSent,
}: {
  section: LowAttendanceSectionItem;
  canSend: boolean;
  onBack: () => void;
  onInvitesSent: (count: number) => void;
}) {
  const [students, setStudents] = useState<LowAttendanceStudentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [showModal, setShowModal] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    fetchLowAttendanceStudents(section.sectionId)
      .then(data => { setStudents(data); setLoading(false); })
      .catch(() => { setError('Failed to load students.'); setLoading(false); });
  }, [section.sectionId]);

  const toggleAll = () => {
    if (selected.size === students.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(students.map(s => s.studentId)));
    }
  };

  const toggle = (id: number) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelected(next);
  };

  const handleInvitesSent = (count: number) => {
    setShowModal(false);
    setSelected(new Set());
    setToast(`${count} invite${count !== 1 ? 's' : ''} queued successfully.`);
    setTimeout(() => setToast(null), 3000);
    onInvitesSent(count);
  };

  const selectedStudents = students.filter(s => selected.has(s.studentId));

  return (
    <div>
      <button onClick={onBack}
        style={{ background: 'none', border: 'none', color: '#3949ab', cursor: 'pointer',
                 fontSize: 13, fontWeight: 600, padding: '0 0 16px', display: 'flex', alignItems: 'center', gap: 4 }}>
        ← Back to sections
      </button>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: '#1a2352' }}>
          {section.className} – {section.sectionName}
        </div>
        <div style={{ fontSize: 13, color: '#555', marginTop: 4 }}>
          {section.presentCount}/{section.totalEnrolled} present today ·{' '}
          <span style={{ color: '#b35c00', fontWeight: 600 }}>{section.attendancePct.toFixed(0)}% attendance</span>
        </div>
      </div>

      {loading && <div style={{ color: '#888', padding: '20px 0' }}>Loading students…</div>}
      {error && <div style={{ color: '#c0312b', padding: '12px 0' }}>{error}</div>}

      {!loading && !error && (
        <>
          {students.length === 0 ? (
            <div style={{ color: '#888', padding: '20px 0', textAlign: 'center' }}>
              No students with low attendance in this section.
            </div>
          ) : (
            <>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
                  <input type="checkbox" checked={selected.size === students.length && students.length > 0}
                    onChange={toggleAll} />
                  Select all ({students.length})
                </label>
                {canSend && selected.size > 0 && (
                  <button onClick={() => setShowModal(true)}
                    style={{ padding: '6px 14px', background: '#b35c00', color: '#fff',
                             border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 13 }}>
                    Invite {selected.size}
                  </button>
                )}
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {students.map(s => (
                  <div key={s.studentId}
                    style={{ border: '1px solid #e8eaf6', borderRadius: 8, padding: '10px 14px',
                             background: selected.has(s.studentId) ? '#f5f6ff' : '#fff',
                             display: 'flex', alignItems: 'center', gap: 12 }}>
                    {canSend && (
                      <input type="checkbox" checked={selected.has(s.studentId)}
                        onChange={() => toggle(s.studentId)}
                        style={{ width: 14, height: 14, flexShrink: 0 }} />
                    )}
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <div style={{ fontWeight: 600, fontSize: 14 }}>{s.studentName}</div>
                          <div style={{ fontSize: 12, color: '#888' }}>{s.admissionNo}</div>
                        </div>
                        <div style={{ textAlign: 'right', flexShrink: 0 }}>
                          <div style={{ fontSize: 13, fontWeight: 700,
                                         color: (s.attendancePercent ?? 0) < 60 ? '#c0312b' : '#b35c00' }}>
                            {s.attendancePercent != null ? `${s.attendancePercent.toFixed(0)}%` : '—'}
                          </div>
                          <div style={{ fontSize: 11, color: '#888' }}>attendance</div>
                        </div>
                      </div>
                      {s.fatherName && (
                        <div style={{ fontSize: 12, color: '#555', marginTop: 4 }}>
                          {s.fatherName}{s.fatherContact ? ` · ${s.fatherContact}` : ''}
                        </div>
                      )}
                      {s.lastInviteSentAt && (
                        <div style={{ fontSize: 11, color: '#888', marginTop: 2 }}>
                          Last invite: {new Date(s.lastInviteSentAt).toLocaleDateString('en-IN')}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}

      {toast && (
        <div style={{ position: 'fixed', bottom: 24, right: 24, background: '#1a6840', color: '#fff',
                      padding: '10px 18px', borderRadius: 8, fontWeight: 600, fontSize: 13, zIndex: 3000 }}>
          {toast}
        </div>
      )}

      {showModal && (
        <ConfirmInviteModal
          selected={selectedStudents}
          onClose={() => setShowModal(false)}
          onSent={handleInvitesSent}
        />
      )}
    </div>
  );
}

// ── Main drawer ───────────────────────────────────────────────────────────────

export function LowAttendanceDrawer({ open, onClose }: Props) {
  const { can } = usePermissions();
  const canSend = can('attendance:manage');

  const [data, setData] = useState<LowAttendanceSectionsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedSection, setSelectedSection] = useState<LowAttendanceSectionItem | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchLowAttendanceSections()
      .then(d => { setData(d); setLoading(false); })
      .catch(() => { setError('Failed to load attendance data.'); setLoading(false); });
  }, []);

  useEffect(() => {
    if (open) {
      setSelectedSection(null);
      load();
    }
  }, [open, load]);

  return (
    <CommandCenterDrawer
      open={open}
      onClose={onClose}
      title="Low Attendance Sections"
    >
      {loading && <div style={{ padding: 24, color: '#888', textAlign: 'center' }}>Loading…</div>}
      {error && <div style={{ padding: 24, color: '#c0312b' }}>{error}</div>}

      {!loading && !error && data && (
        selectedSection ? (
          <SectionDetail
            section={selectedSection}
            canSend={canSend}
            onBack={() => setSelectedSection(null)}
            onInvitesSent={() => {}}
          />
        ) : (
          <div>
            <div style={{ marginBottom: 16, padding: '10px 14px', background: '#fff8e1',
                          borderRadius: 8, border: '1px solid #ffe0a0' }}>
              <span style={{ fontSize: 13, color: '#856404', fontWeight: 600 }}>
                Threshold: {data.thresholdPercent}% attendance
              </span>
              <span style={{ fontSize: 12, color: '#856404', marginLeft: 8 }}>
                · {data.date}
              </span>
            </div>

            {data.sections.length === 0 ? (
              <div style={{ padding: '32px 0', textAlign: 'center', color: '#888' }}>
                <div style={{ fontSize: 32, marginBottom: 8 }}>✓</div>
                <div style={{ fontWeight: 600, fontSize: 14 }}>All sections above threshold today</div>
                <div style={{ fontSize: 12, marginTop: 4 }}>No sections below {data.thresholdPercent}%</div>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {data.sections.map(sec => (
                  <button key={sec.sectionId}
                    onClick={() => setSelectedSection(sec)}
                    style={{ textAlign: 'left', border: '1px solid #e8eaf6', borderRadius: 8,
                             padding: '12px 16px', background: '#fff', cursor: 'pointer',
                             transition: 'box-shadow .2s' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: 14, color: '#1a2352' }}>
                          {sec.className} – {sec.sectionName}
                        </div>
                        <div style={{ fontSize: 12, color: '#555', marginTop: 2 }}>
                          {sec.presentCount}/{sec.totalEnrolled} present ·{' '}
                          <span style={{ color: '#b35c00' }}>{sec.studentsBelowThreshold} students below threshold</span>
                        </div>
                      </div>
                      <span style={{ fontSize: 11, color: '#3949ab', fontWeight: 600, alignSelf: 'flex-start' }}>
                        View →
                      </span>
                    </div>
                    <AttendanceBar pct={sec.attendancePct} />
                  </button>
                ))}
              </div>
            )}
          </div>
        )
      )}
    </CommandCenterDrawer>
  );
}
