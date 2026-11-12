import { useState, useEffect, useCallback, useId } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import {
  fetchLowAttendanceSections,
  fetchLowAttendanceStudents,
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

// ── Progress bar ──────────────────────────────────────────────────────────────

function AttendanceBar({ pct }: { pct: number }) {
  const color = pct < 60 ? 'var(--ck-color-danger)' : pct < 75 ? 'var(--ck-color-warning)' : 'var(--ck-color-primary)';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{ flex: 1, background: 'var(--ck-color-indigo-soft)', borderRadius: 4, height: 6, overflow: 'hidden' }}>
        <div style={{ width: `${Math.max(0, Math.min(100, pct))}%`, height: '100%', background: color }} />
      </div>
      <span style={{ fontSize: 12, fontWeight: 600, color, minWidth: 36 }}>{pct.toFixed(0)}%</span>
    </div>
  );
}

// ── Section detail panel ──────────────────────────────────────────────────────

function SectionDetail({
  section,
  canSend,
  onBack,
}: {
  section: LowAttendanceSectionItem;
  canSend: boolean;
  onBack: () => void;
}) {
  const [students, setStudents] = useState<LowAttendanceStudentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const unavailableId = useId();

  useEffect(() => {
    setLoading(true);
    fetchLowAttendanceStudents(section.sectionId)
      .then(data => { setStudents(data); setLoading(false); })
      .catch(() => { setError('Failed to load students.'); setLoading(false); });
  }, [section.sectionId]);

  return (
    <div>
      <button onClick={onBack}
        style={{ background: 'none', border: 'none', color: 'var(--ck-color-indigo)', cursor: 'pointer',
                 fontSize: 13, fontWeight: 600, padding: '0 0 16px', display: 'flex', alignItems: 'center', gap: 4 }}>
        ← Back to sections
      </button>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--ck-color-indigo-deep)' }}>
          {section.className} – {section.sectionName}
        </div>
        <div style={{ fontSize: 13, color: 'var(--ck-text-secondary)', marginTop: 4 }}>
          {section.presentCount}/{section.totalEnrolled} present today ·{' '}
          <span style={{ color: 'var(--ck-color-warning)', fontWeight: 600 }}>{section.attendancePct.toFixed(0)}% attendance</span>
        </div>
      </div>

      {loading && <div style={{ color: 'var(--ck-text-muted)', padding: '20px 0' }}>Loading students…</div>}
      {error && <div style={{ color: 'var(--ck-color-danger)', padding: '12px 0' }}>{error}</div>}

      {!loading && !error && (
        <>
          {students.length === 0 ? (
            <div style={{ color: 'var(--ck-text-muted)', padding: '20px 0', textAlign: 'center' }}>
              No students with low attendance in this section.
            </div>
          ) : (
            <>
              {/* POST /dashboard/attendance/meeting-invites has had no handler since the microservice cutover, and
                  no other service sends these invites (docs/FRONTEND-AB-DECISIONS-2026-07-02.md). Say so rather than
                  offer a send that always fails. */}
              {canSend && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
                  <button disabled aria-describedby={unavailableId}
                    style={{ padding: '6px 14px', background: 'var(--ck-color-indigo-border)', color: 'var(--ck-text-inverse)',
                             border: 'none', borderRadius: 6, cursor: 'default', fontWeight: 600, fontSize: 13 }}>
                    Send meeting invites
                  </button>
                  <span id={unavailableId} style={{ fontSize: 12, color: 'var(--ck-text-muted)' }}>
                    Sending meeting invites from the dashboard isn't available yet.
                  </span>
                </div>
              )}

              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {students.map(s => (
                  <div key={s.studentId}
                    style={{ border: '1px solid var(--ck-color-indigo-soft)', borderRadius: 8, padding: '10px 14px',
                             background: 'var(--ck-bg-surface)',
                             display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <div style={{ fontWeight: 600, fontSize: 14 }}>{s.studentName}</div>
                          <div style={{ fontSize: 12, color: 'var(--ck-text-muted)' }}>{s.admissionNo}</div>
                        </div>
                        <div style={{ textAlign: 'right', flexShrink: 0 }}>
                          <div style={{ fontSize: 13, fontWeight: 700,
                                         color: (s.attendancePercent ?? 0) < 60 ? 'var(--ck-color-danger)' : 'var(--ck-color-warning)' }}>
                            {s.attendancePercent != null ? `${s.attendancePercent.toFixed(0)}%` : '—'}
                          </div>
                          <div style={{ fontSize: 11, color: 'var(--ck-text-muted)' }}>attendance</div>
                        </div>
                      </div>
                      {s.fatherName && (
                        <div style={{ fontSize: 12, color: 'var(--ck-text-secondary)', marginTop: 4 }}>
                          {s.fatherName}{s.fatherContact ? ` · ${s.fatherContact}` : ''}
                        </div>
                      )}
                      {s.lastInviteSentAt && (
                        <div style={{ fontSize: 11, color: 'var(--ck-text-muted)', marginTop: 2 }}>
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
      {loading && <div style={{ padding: 24, color: 'var(--ck-text-muted)', textAlign: 'center' }}>Loading…</div>}
      {error && <div style={{ padding: 24, color: 'var(--ck-color-danger)' }}>{error}</div>}

      {!loading && !error && data && (
        selectedSection ? (
          <SectionDetail
            section={selectedSection}
            canSend={canSend}
            onBack={() => setSelectedSection(null)}
          />
        ) : (
          <div>
            <div style={{ marginBottom: 16, padding: '10px 14px', background: 'var(--ck-color-warning-soft)',
                          borderRadius: 8, border: '1px solid var(--ck-color-warning-border)' }}>
              <span style={{ fontSize: 13, color: 'var(--ck-color-warning)', fontWeight: 600 }}>
                Threshold: {data.thresholdPercent}% attendance
              </span>
              <span style={{ fontSize: 12, color: 'var(--ck-color-warning)', marginLeft: 8 }}>
                · {data.date}
              </span>
            </div>

            {data.sections.length === 0 ? (
              <div style={{ padding: '32px 0', textAlign: 'center', color: 'var(--ck-text-muted)' }}>
                <div style={{ fontSize: 32, marginBottom: 8 }}>✓</div>
                <div style={{ fontWeight: 600, fontSize: 14 }}>All sections above threshold today</div>
                <div style={{ fontSize: 12, marginTop: 4 }}>No sections below {data.thresholdPercent}%</div>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {data.sections.map(sec => (
                  <button key={sec.sectionId}
                    onClick={() => setSelectedSection(sec)}
                    style={{ textAlign: 'left', border: '1px solid var(--ck-color-indigo-soft)', borderRadius: 8,
                             padding: '12px 16px', background: 'var(--ck-bg-surface)', cursor: 'pointer',
                             transition: 'box-shadow .2s' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--ck-color-indigo-deep)' }}>
                          {sec.className} – {sec.sectionName}
                        </div>
                        <div style={{ fontSize: 12, color: 'var(--ck-text-secondary)', marginTop: 2 }}>
                          {sec.presentCount}/{sec.totalEnrolled} present ·{' '}
                          <span style={{ color: 'var(--ck-color-warning)' }}>{sec.studentsBelowThreshold} students below threshold</span>
                        </div>
                      </div>
                      <span style={{ fontSize: 11, color: 'var(--ck-color-indigo)', fontWeight: 600, alignSelf: 'flex-start' }}>
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
