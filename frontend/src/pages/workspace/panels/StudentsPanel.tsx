import { useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import Paginator from '../../../components/Paginator';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import {
  emptyStudentProfileForm,
  STUDENT_PHOTO_MAX_LABEL,
  studentDetailToProfileForm,
  studentProfileFormToUpdatePayload,
  type StudentClassOption,
  type StudentProfileFormState,
  type StudentSectionOption,
  validateStudentPhotoFile,
} from '../../../features/students';
import { ModuleShell, Info } from '../ui';
import { formatAddress, initials } from '../utils';
import type { PanelKey } from '../config';
import { StudentProfileForm } from './StudentProfileForm';

interface Props {
  setPanel: (key: PanelKey) => void;
  onRefresh: () => void;
}

export function StudentsPanel({ setPanel, onRefresh }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const schoolScopedParams = !can('platform:admin') && user?.branchId ? { schoolId: user.branchId } : undefined;

  const [studentFilters, setStudentFilters] = useState({ className: 'All', sectionName: 'All', feeStatus: 'All' });
  const [studentsPage, setStudentsPage] = useState(0);
  const PAGE_SIZE = 50;
  const [studentsView, setStudentsView] = useState<any>({ items: [], filteredCount: 0, filteredSections: 0, totalPages: 1, filters: { classes: [], sections: [], feeStatuses: ['Paid', 'Overdue', 'Pending', 'Partial'] } });
  const [studentsLoading, setStudentsLoading] = useState(false);
  const [studentDetail, setStudentDetail] = useState<any | null>(null);
  const [studentModalOpen, setStudentModalOpen] = useState(false);
  const [studentModalLoading, setStudentModalLoading] = useState(false);
  const [studentsError, setStudentsError] = useState<string | null>(null);
  const [studentDetailLimited, setStudentDetailLimited] = useState(false);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<StudentProfileFormState>(emptyStudentProfileForm());
  const [modalError, setModalError] = useState<string | null>(null);
  const [classOptions, setClassOptions] = useState<StudentClassOption[]>([]);
  const [sectionOptions, setSectionOptions] = useState<StudentSectionOption[]>([]);

  const loadStudents = async (filters = studentFilters, page = studentsPage) => {
    try {
      setStudentsLoading(true);
      setStudentsError(null);
      const params: Record<string, any> = { page, size: PAGE_SIZE };
      if (filters.className !== 'All') params.class = filters.className;
      if (filters.sectionName !== 'All') params.section = filters.sectionName;
      if (filters.feeStatus !== 'All') params.feeStatus = filters.feeStatus;
      const res = await api.get('/students', { params: { ...params, ...(schoolScopedParams || {}) } });
      setStudentsView(res.data);
    } catch {
      setStudentsError('Failed to load students. Please try again.');
    } finally {
      setStudentsLoading(false);
    }
  };

  /** When filters change, reset to page 0 and reload. */
  const applyFilters = (filters: typeof studentFilters) => {
    setStudentFilters(filters);
    setStudentsPage(0);
    loadStudents(filters, 0);
  };

  const handlePageChange = (page: number) => {
    setStudentsPage(page);
    loadStudents(studentFilters, page);
  };

  useEffect(() => {
    loadStudents(studentFilters, 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const enterEditMode = (detail: any) => {
    setModalError(null);
    setForm(studentDetailToProfileForm(detail));
    setEditing(true);
    void api.get('/classes', { params: schoolScopedParams })
      .then((r) => setClassOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setClassOptions([]));
    if (detail.classId) {
      void api.get(`/classes/${encodeURIComponent(detail.classId)}/sections`, { params: schoolScopedParams })
        .then((r) => setSectionOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setSectionOptions([]));
    } else {
      setSectionOptions([]);
    }
  };

  const openStudentModal = async (student: any, editMode = false) => {
    setStudentDetailLimited(false);
    setEditing(false);
    setModalError(null);
    let detail = student;
    try {
      setStudentModalOpen(true);
      setStudentModalLoading(true);
      const res = await api.get(`/students/${student.id}/workspace`);
      detail = res.data;
      setStudentDetail(detail);
    } catch {
      detail = student;
      setStudentDetail(detail);
      setStudentDetailLimited(true);
    } finally {
      setStudentModalLoading(false);
      if (editMode) enterEditMode(detail);
    }
  };

  const closeStudentModal = () => {
    setStudentModalOpen(false);
    setEditing(false);
    setModalError(null);
  };

  // NOTE: GET /api/v1/students/{id}/workspace returns the rich `workspaceStudentDetail`
  // shape — field names are `admissionNumber`, `boardRegistrationNumber`, `dateOfBirth`,
  // and address is a nested object `{houseNumber, street, locality, city, state, pinCode, full}`.
  // The edit form below is pre-filled/saved using these workspace keys; `updateStudent` on the
  // backend accepts both flat and workspace-shaped keys via `firstPresent(...)`.
  const editPhotoInputRef = useRef<HTMLInputElement | null>(null);
  const [editPhotoBusy, setEditPhotoBusy] = useState(false);

  const uploadEditPhoto = async (file: File) => {
    if (!studentDetail) return;
    const validationError = validateStudentPhotoFile(file);
    if (validationError) { setModalError(validationError); return; }
    setEditPhotoBusy(true);
    setModalError(null);
    try {
      const formData = new FormData();
      formData.append('file', file);
      await api.post(`/students/${studentDetail.id}/photo`, formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      const res = await api.get(`/students/${studentDetail.id}/workspace`);
      setStudentDetail(res.data);
      await loadStudents(studentFilters, studentsPage);
    } catch (err: unknown) {
      setModalError((err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : 'Could not update photo.'));
    } finally {
      setEditPhotoBusy(false);
      if (editPhotoInputRef.current) editPhotoInputRef.current.value = '';
    }
  };

  const onClassChange = (classId: string) => {
    setForm((f) => ({ ...f, classId, sectionId: '' }));
    if (!classId) {
      setSectionOptions([]);
      return;
    }
    void api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams })
      .then((r) => setSectionOptions(Array.isArray(r.data) ? r.data : [])).catch(() => setSectionOptions([]));
  };

  const updateStudentForm = (patch: Partial<StudentProfileFormState>) => {
    setForm((f) => ({ ...f, ...patch }));
  };

  const saveStudent = async () => {
    if (!studentDetail) return;
    setSaving(true);
    setModalError(null);
    try {
      await api.put(`/workspace/students/${studentDetail.id}`, { ...studentProfileFormToUpdatePayload(form), ...(schoolScopedParams || {}) });
      const res = await api.get(`/students/${studentDetail.id}/workspace`);
      setStudentDetail(res.data);
      setEditing(false);
      await loadStudents(studentFilters, studentsPage);
    } catch (err: unknown) {
      setModalError((err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : 'Could not save changes.'));
    } finally {
      setSaving(false);
    }
  };

  function feeStatusClass(status: string): string {
    switch ((status ?? '').toLowerCase()) {
      case 'paid':    return 'sg spaid';
      case 'overdue': return 'sr soverdue';
      case 'partial': return 'sor spartial';
      case 'pending': return 'sam spending';
      default:        return 'sgr sneutral';
    }
  }

  return (
    <>
      <ModuleShell
        title="Students"
        subtitle={`${studentsView.filteredCount || 0} enrolled · ${studentsView.filteredSections || 0} sections · Academic year 2024–25`}
        actions={
          <>
            <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('bulkimport')}>Bulk import</button>
            <button className="ck-btn ck-btn-g" onClick={() => setPanel('addstudent')}>+ Add student</button>
          </>
        }
      >
        {studentsError ? (
          <div className="ck-alert ck-alert-r">
            <span>!</span>
            <div>{studentsError}</div>
          </div>
        ) : null}

        {/* Change 1: Improved filter bar */}
        <div className="ck-card-h-wrap" style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', background: '#fbfaf8' }}>
          <div className="ck-card-inline-filters">
            <select
              value={studentFilters.className}
              onChange={e => applyFilters({ ...studentFilters, className: e.target.value, sectionName: 'All' })}
              style={{ minWidth: 120 }}
              aria-label="Filter by class"
            >
              <option value="All">All classes</option>
              {studentsView.filters?.classes?.map((c: string) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
            <select
              value={studentFilters.sectionName}
              onChange={e => applyFilters({ ...studentFilters, sectionName: e.target.value })}
              style={{ minWidth: 120 }}
              aria-label="Filter by section"
            >
              <option value="All">All sections</option>
              {studentsView.filters?.sections?.map((s: string) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
            <select
              value={studentFilters.feeStatus}
              onChange={e => applyFilters({ ...studentFilters, feeStatus: e.target.value })}
              style={{ minWidth: 140 }}
              aria-label="Filter by fee status"
            >
              <option value="All">All fee statuses</option>
              {(studentsView.filters?.feeStatuses ?? ['Paid', 'Overdue', 'Pending', 'Partial']).map((s: string) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>
          <div style={{ fontSize: 12, color: 'var(--ink3)', marginLeft: 'auto', flexShrink: 0 }}>
            {studentsLoading ? '…' : `${studentsView.filteredCount ?? studentsView.items?.length ?? 0} students`}
          </div>
        </div>

        <div className="ck-card">
          <div className="ck-table-wrap">
          <table className="ck-table">
            <thead>
              <tr>
                <th>Student</th>
                <th>Admission / Roll</th>
                <th>Father name</th>
                <th>Father contact</th>
                <th>Fee status</th>
                <th>Attendance</th>
                <th />
              </tr>
            </thead>
            {/* Change 2: Skeleton loading rows */}
            {studentsLoading && (
              <tbody>
                {[0,1,2,3,4].map(i => (
                  <tr key={i}>
                    <td colSpan={99} style={{ padding: 0, border: 'none' }}>
                      <div className="ck-skeleton-row" style={{ animationDelay: `${i * 0.06}s` }}>
                        <div className="ck-skeleton ck-skeleton-avatar" />
                        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 5 }}>
                          <div className="ck-skeleton ck-skeleton-text" style={{ width: '45%' }} />
                          <div className="ck-skeleton ck-skeleton-text" style={{ width: '30%', height: 11 }} />
                        </div>
                        <div className="ck-skeleton ck-skeleton-badge" />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            )}
            {!studentsLoading && (
              <tbody>
                {(studentsView.items || []).map((student: any) => {
                  return (
                    <tr key={student.id}>
                      <td>
                        <div className="ck-student-cell">
                          {student.photoUrl
                            ? <img src={student.photoUrl} alt={student.fullName} className="ck-student-avatar" />
                            : <div className="ck-student-avatar ck-student-avatar-fallback">{initials(student.fullName)}</div>
                          }
                          <div>
                            <div className="tb">{student.fullName}</div>
                            <div className="ts">{student.classSection} · {student.academicYear}</div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="tb">{student.admissionNumber}</div>
                        <div className="ts">Roll {student.rollNo}</div>
                      </td>
                      <td>
                        <div className="tb">{student.fatherName || '—'}</div>
                        <div className="ts">Contact {student.fatherContact || '—'}</div>
                      </td>
                      <td>{student.fatherContact || student.parentPhone || '—'}</td>
                      {/* Change 4: Improved fee status badge */}
                      <td>
                        <span className={`ck-status ${feeStatusClass(student.feeStatus ?? '')}`}>
                          {student.feeStatus ?? 'Unknown'}
                        </span>
                      </td>
                      <td>
                        <div className="ck-mini-progress-cell">
                          <div className="tb">{student.attendancePercent ?? 0}%</div>
                          <div className="ck-mini-progress">
                            <div className="ck-mini-progress-fill" style={{ width: `${student.attendancePercent ?? 0}%` }} />
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="ck-actions-inline">
                          <button className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => openStudentModal(student)}>View</button>
                          {can('student:update') && (
                            <button className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => openStudentModal(student, true)}>Edit</button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            )}
          </table>
          </div>

          {/* Change 3: Designed empty state */}
          {!studentsLoading && (studentsView.items?.length ?? 0) === 0 && (
            <div style={{ padding: '48px 24px', textAlign: 'center' }}>
              <div style={{ fontSize: 38, marginBottom: 10, lineHeight: 1 }}>🎓</div>
              <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 6 }}>
                {studentFilters.className !== 'All' || studentFilters.sectionName !== 'All' || studentFilters.feeStatus !== 'All'
                  ? 'No students match these filters'
                  : 'No students enrolled yet'}
              </div>
              <div style={{ fontSize: 13, color: 'var(--ink3)', marginBottom: 14, maxWidth: 320, margin: '0 auto 14px' }}>
                {studentFilters.className !== 'All' || studentFilters.sectionName !== 'All' || studentFilters.feeStatus !== 'All'
                  ? 'Try adjusting the class, section, or fee status filters above.'
                  : 'Add your first student to get started.'}
              </div>
              {can('student:write') && studentFilters.className === 'All' && (
                <button className="ck-btn ck-btn-g" onClick={() => setPanel('addstudent')}>
                  Add first student
                </button>
              )}
            </div>
          )}
        </div>

        <Paginator
          page={studentsPage}
          totalPages={studentsView.totalPages ?? 1}
          onPageChange={handlePageChange}
        />
      </ModuleShell>

      {studentModalOpen && (
        <div className="ck-modal-bg" onClick={closeStudentModal}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Student details</div>
              <button className="ck-modal-x" onClick={closeStudentModal}>×</button>
            </div>
            <div className="ck-modal-body">
              {studentModalLoading || !studentDetail ? (
                <div className="ts">Loading student details…</div>
              ) : editing ? (
                <div className="ck-student-modal-info">
                  {modalError && <div className="ck-alert ck-alert-r" style={{ gridColumn: '1/-1' }}><span>!</span><div>{modalError}</div></div>}
                  <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'center', gap: 12 }}>
                    {studentDetail.photoUrl
                      ? <img src={studentDetail.photoUrl} alt={studentDetail.fullName || studentDetail.name} className="ck-student-avatar" />
                      : <div className="ck-student-avatar ck-student-avatar-fallback">{initials(studentDetail.fullName || studentDetail.name)}</div>}
                    <input ref={editPhotoInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={(e) => { const f = e.target.files?.[0]; if (f) void uploadEditPhoto(f); }} />
                    <button type="button" className="ck-btn ck-btn-ghost" disabled={editPhotoBusy} onClick={() => editPhotoInputRef.current?.click()}>{editPhotoBusy ? 'Uploading…' : 'Change photo'}</button>
                    <div className="ts">JPG, PNG or WEBP · up to {STUDENT_PHOTO_MAX_LABEL}</div>
                  </div>
                  <div style={{ gridColumn: '1/-1' }}>
                    <StudentProfileForm
                      form={form}
                      classes={classOptions}
                      sections={sectionOptions}
                      onChange={updateStudentForm}
                      onClassChange={onClassChange}
                    />
                  </div>
                </div>
              ) : (
                <div className="ck-student-modal-grid">
                  {studentDetailLimited && (
                    <div className="ck-alert ck-alert-am">
                      <span>!</span>
                      <div>Showing limited data — full student details could not be loaded.</div>
                    </div>
                  )}
                  <div className="ck-student-modal-hero">
                    {studentDetail.photoUrl
                      ? <img src={studentDetail.photoUrl} alt={studentDetail.name} className="ck-student-avatar ck-student-avatar-lg" />
                      : <div className="ck-student-avatar ck-student-avatar-fallback ck-student-avatar-lg">{initials(studentDetail.name)}</div>
                    }
                    <div>
                      <div className="ck-modal-title" style={{ fontSize: 24 }}>{studentDetail.fullName || studentDetail.name}</div>
                      <div className="ts">{studentDetail.classSection} · {studentDetail.academicYear}</div>
                    </div>
                  </div>
                  <div className="ck-student-modal-info">
                    <Info label="Admission No" value={studentDetail.admissionNumber} />
                    <Info label="Roll No" value={studentDetail.rollNo} />
                    <Info label="Board Registration No" value={studentDetail.boardRegistrationNumber || '—'} />
                    <Info label="Father name" value={studentDetail.fatherName || '—'} />
                    <Info label="Father contact" value={studentDetail.fatherContact || studentDetail.parentPhone || '—'} />
                    <Info label="Mother name" value={studentDetail.motherName || '—'} />
                    <Info label="Date of birth" value={studentDetail.dateOfBirth || '—'} />
                  </div>
                  <div className="ck-form-card">
                    <div className="ck-form-head">Address</div>
                    <div className="ck-form-body">
                      <div className="ts">{formatAddress(studentDetail.address)}</div>
                    </div>
                  </div>
                  <div className="ck-form-card">
                    <div className="ck-form-head">Attendance &amp; fees</div>
                    <div className="ck-form-body">
                      <div className="ck-progress-wrap">
                        <div className="ck-progress-label">
                          <span>Attendance</span>
                          <strong>{studentDetail.attendancePercent ?? 0}%</strong>
                        </div>
                        <div className="ck-progress-bar">
                          <div className="ck-progress-fill" style={{ width: `${studentDetail.attendancePercent ?? 0}%` }} />
                        </div>
                      </div>
                      <span className={`ck-status ${feeStatusClass(studentDetail.feeStatus ?? '')}`}>
                        {studentDetail.feeStatus ?? 'Unknown'}
                      </span>
                    </div>
                  </div>
                </div>
              )}
            </div>
            <div className="ck-modal-foot">
              {editing ? (
                <>
                  <button className="ck-btn ck-btn-ghost" onClick={() => { setEditing(false); setModalError(null); }} disabled={saving}>Cancel</button>
                  <button className="ck-btn ck-btn-g" onClick={saveStudent} disabled={saving}>{saving ? 'Saving…' : 'Save changes'}</button>
                </>
              ) : (
                <button className="ck-btn ck-btn-ghost" onClick={closeStudentModal}>Close</button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
