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

interface AcademicYearOption {
  id: string;
  label: string;
  active?: boolean;
}

interface StudentHistoryPayload {
  enrollments?: Array<Record<string, any>>;
  imports?: Array<Record<string, any>>;
  promotions?: Array<Record<string, any>>;
}

interface PromotionBatchItem {
  id: string;
  studentName: string;
  admissionNumber: string;
  sourceClassName?: string;
  sourceSectionName?: string;
  targetClassName?: string;
  targetSectionName?: string;
  action?: string;
  status?: string;
  reason?: string;
}

interface PromotionBatch {
  id: string;
  status: string;
  promoted?: number;
  skipped?: number;
  items?: PromotionBatchItem[];
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
  const [studentHistory, setStudentHistory] = useState<StudentHistoryPayload | null>(null);
  const [studentHistoryLoading, setStudentHistoryLoading] = useState(false);
  const [studentHistoryError, setStudentHistoryError] = useState<string | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [promotionOpen, setPromotionOpen] = useState(false);
  const [promotionClasses, setPromotionClasses] = useState<StudentClassOption[]>([]);
  const [promotionSections, setPromotionSections] = useState<StudentSectionOption[]>([]);
  const [promotionYears, setPromotionYears] = useState<AcademicYearOption[]>([]);
  const [promotionForm, setPromotionForm] = useState({ sourceClassId: '', sourceSectionId: '', targetAcademicYearId: '' });
  const [promotionBatch, setPromotionBatch] = useState<PromotionBatch | null>(null);
  const [promotionLoading, setPromotionLoading] = useState(false);
  const [promotionError, setPromotionError] = useState<string | null>(null);

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
    setStudentHistory(null);
    setStudentHistoryError(null);
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
    setStudentHistory(null);
    setStudentHistoryError(null);
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

  const loadStudentHistory = async () => {
    if (!studentDetail?.id) return;
    try {
      setStudentHistoryLoading(true);
      setStudentHistoryError(null);
      const res = await api.get(`/students/${studentDetail.id}/history`);
      setStudentHistory(res.data || {});
    } catch (err: unknown) {
      setStudentHistoryError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load student history.'));
    } finally {
      setStudentHistoryLoading(false);
    }
  };

  const deleteStudent = async () => {
    if (!studentDetail?.id) return;
    const ok = window.confirm(`Delete ${studentDetail.fullName || studentDetail.name}? This keeps the history but removes the student from active lists.`);
    if (!ok) return;
    try {
      setDeleteBusy(true);
      setModalError(null);
      await api.delete(`/students/${studentDetail.id}`, { data: { reason: 'Deleted from Students tab' } });
      closeStudentModal();
      await loadStudents(studentFilters, studentsPage);
      onRefresh();
    } catch (err: unknown) {
      setModalError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not delete student.'));
    } finally {
      setDeleteBusy(false);
    }
  };

  const loadPromotionSections = async (classId: string) => {
    if (!classId) {
      setPromotionSections([]);
      setPromotionForm((f) => ({ ...f, sourceClassId: '', sourceSectionId: '' }));
      return;
    }
    setPromotionForm((f) => ({ ...f, sourceClassId: classId, sourceSectionId: '' }));
    try {
      const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
      setPromotionSections(Array.isArray(res.data) ? res.data : []);
    } catch {
      setPromotionSections([]);
    }
  };

  const openPromotionWizard = async () => {
    setPromotionOpen(true);
    setPromotionError(null);
    setPromotionBatch(null);
    setPromotionLoading(true);
    try {
      const [classesRes, yearsRes] = await Promise.all([
        api.get('/classes', { params: schoolScopedParams }),
        api.get('/academic-years'),
      ]);
      const classes = Array.isArray(classesRes.data) ? classesRes.data : [];
      const years = Array.isArray(yearsRes.data) ? yearsRes.data : [];
      setPromotionClasses(classes);
      setPromotionYears(years);
      const firstClassId = classes[0]?.id || '';
      const targetYearId = years.find((y: AcademicYearOption) => !y.active)?.id || years.find((y: AcademicYearOption) => y.active)?.id || years[0]?.id || '';
      setPromotionForm({ sourceClassId: firstClassId, sourceSectionId: '', targetAcademicYearId: targetYearId });
      if (firstClassId) {
        const sectionsRes = await api.get(`/classes/${encodeURIComponent(firstClassId)}/sections`, { params: schoolScopedParams });
        setPromotionSections(Array.isArray(sectionsRes.data) ? sectionsRes.data : []);
      } else {
        setPromotionSections([]);
      }
    } catch (err: unknown) {
      setPromotionError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not open promotion setup.'));
    } finally {
      setPromotionLoading(false);
    }
  };

  const createPromotionBatch = async () => {
    if (!promotionForm.targetAcademicYearId) {
      setPromotionError('Select a target academic year.');
      return;
    }
    try {
      setPromotionLoading(true);
      setPromotionError(null);
      const res = await api.post('/students/promotion-batches', {
        ...promotionForm,
        ...(schoolScopedParams || {}),
      });
      setPromotionBatch(res.data);
    } catch (err: unknown) {
      setPromotionError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not create promotion batch.'));
    } finally {
      setPromotionLoading(false);
    }
  };

  const updatePromotionItem = async (itemId: string, action: 'PROMOTE' | 'HOLD') => {
    if (!promotionBatch?.id) return;
    try {
      setPromotionLoading(true);
      setPromotionError(null);
      const res = await api.patch(`/students/promotion-batches/${encodeURIComponent(promotionBatch.id)}/items/${encodeURIComponent(itemId)}`, {
        action,
        reason: action === 'HOLD' ? 'Held from promotion wizard' : '',
      });
      setPromotionBatch(res.data);
    } catch (err: unknown) {
      setPromotionError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not update promotion item.'));
    } finally {
      setPromotionLoading(false);
    }
  };

  const applyPromotionBatch = async () => {
    if (!promotionBatch?.id) return;
    const count = (promotionBatch.items || []).filter((item) => (item.action || '').toUpperCase() === 'PROMOTE').length;
    const ok = window.confirm(`Apply promotion for ${count} student${count === 1 ? '' : 's'}?`);
    if (!ok) return;
    try {
      setPromotionLoading(true);
      setPromotionError(null);
      const res = await api.post(`/students/promotion-batches/${encodeURIComponent(promotionBatch.id)}/apply`);
      setPromotionBatch(res.data);
      await loadStudents(studentFilters, studentsPage);
      onRefresh();
    } catch (err: unknown) {
      setPromotionError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not apply promotion batch.'));
    } finally {
      setPromotionLoading(false);
    }
  };

  const displayDate = (value: unknown) => value ? String(value).slice(0, 10) : '-';

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
            {can('student:update') && schoolScopedParams ? <button className="ck-btn ck-btn-ghost" onClick={() => void openPromotionWizard()}>Promote class</button> : null}
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
                  {studentHistoryLoading ? (
                    <div className="ck-form-card">
                      <div className="ck-form-body">
                        <div className="ts">Loading lifecycle history...</div>
                      </div>
                    </div>
                  ) : null}
                  {studentHistoryError ? (
                    <div className="ck-alert ck-alert-r">
                      <span>!</span>
                      <div>{studentHistoryError}</div>
                    </div>
                  ) : null}
                  {studentHistory ? (
                    <div className="ck-form-card" style={{ gridColumn: '1 / -1' }}>
                      <div className="ck-form-head">Lifecycle history</div>
                      <div className="ck-form-body">
                        <div className="tb" style={{ marginBottom: 8 }}>Class history</div>
                        {(studentHistory.enrollments || []).length ? (
                          <div className="ck-table-wrap">
                            <table className="ck-table">
                              <thead><tr><th>From</th><th>To</th><th>Class</th><th>Status</th><th>Reason</th></tr></thead>
                              <tbody>
                                {(studentHistory.enrollments || []).map((entry, index) => (
                                  <tr key={entry.id || index}>
                                    <td>{displayDate(entry.effectiveFrom)}</td>
                                    <td>{displayDate(entry.effectiveTo)}</td>
                                    <td>{entry.className || '-'} {entry.sectionName ? `- ${entry.sectionName}` : ''}</td>
                                    <td><span className="ck-status sgr">{entry.status || '-'}</span></td>
                                    <td>{entry.reason || entry.sourceType || '-'}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        ) : <div className="ts">No class history recorded yet.</div>}
                        {(studentHistory.imports || []).length ? (
                          <>
                            <div className="tb" style={{ margin: '14px 0 8px' }}>Import evidence</div>
                            {(studentHistory.imports || []).map((entry, index) => (
                              <div key={`${entry.batchId || index}`} className="ts" style={{ marginBottom: 6 }}>
                                Row {entry.rowNumber} in {entry.fileName || 'uploaded file'} - {entry.status || '-'} on {displayDate(entry.appliedAt || entry.createdAt)}
                              </div>
                            ))}
                          </>
                        ) : null}
                        {(studentHistory.promotions || []).length ? (
                          <>
                            <div className="tb" style={{ margin: '14px 0 8px' }}>Promotion batches</div>
                            {(studentHistory.promotions || []).map((entry, index) => (
                              <div key={`${entry.batchId || index}`} className="ts" style={{ marginBottom: 6 }}>
                                {entry.action || '-'} - {entry.status || '-'} to {entry.targetAcademicYearId || '-'} on {displayDate(entry.appliedAt || entry.createdAt)}
                              </div>
                            ))}
                          </>
                        ) : null}
                      </div>
                    </div>
                  ) : null}
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
                <>
                  <button className="ck-btn ck-btn-ghost" onClick={() => void loadStudentHistory()} disabled={studentHistoryLoading || !studentDetail}>{studentHistoryLoading ? 'Loading...' : 'History'}</button>
                  {can('student:update') && studentDetail ? <button className="ck-btn ck-btn-ghost" onClick={() => enterEditMode(studentDetail)}>Edit</button> : null}
                  {can('student:delete') && studentDetail ? <button className="ck-btn ck-btn-ghost" onClick={() => void deleteStudent()} disabled={deleteBusy}>{deleteBusy ? 'Deleting...' : 'Delete'}</button> : null}
                  <button className="ck-btn ck-btn-ghost" onClick={closeStudentModal}>Close</button>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {promotionOpen && (
        <div className="ck-modal-bg" onClick={() => setPromotionOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 980 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Promote students</div>
              <button className="ck-modal-x" onClick={() => setPromotionOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {promotionError ? (
                <div className="ck-alert ck-alert-r" style={{ marginBottom: 12 }}>
                  <span>!</span>
                  <div>{promotionError}</div>
                </div>
              ) : null}
              <div className="ck-form-grid ck-fg-3">
                <div className="ck-field">
                  <label>Source class</label>
                  <select value={promotionForm.sourceClassId} onChange={(e) => void loadPromotionSections(e.target.value)} disabled={promotionLoading}>
                    <option value="">All classes</option>
                    {promotionClasses.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
                <div className="ck-field">
                  <label>Source section</label>
                  <select value={promotionForm.sourceSectionId} onChange={(e) => setPromotionForm((f) => ({ ...f, sourceSectionId: e.target.value }))} disabled={promotionLoading || !promotionForm.sourceClassId}>
                    <option value="">All sections</option>
                    {promotionSections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                </div>
                <div className="ck-field">
                  <label>Target academic year</label>
                  <select value={promotionForm.targetAcademicYearId} onChange={(e) => setPromotionForm((f) => ({ ...f, targetAcademicYearId: e.target.value }))} disabled={promotionLoading}>
                    <option value="">Select year</option>
                    {promotionYears.map((y) => <option key={y.id} value={y.id}>{y.label}{y.active ? ' (current)' : ''}</option>)}
                  </select>
                </div>
              </div>
              <div className="ck-actions-inline" style={{ marginTop: 12 }}>
                <button className="ck-btn ck-btn-g" onClick={() => void createPromotionBatch()} disabled={promotionLoading || !promotionForm.targetAcademicYearId}>
                  {promotionLoading ? 'Working...' : promotionBatch ? 'Rebuild preview' : 'Create preview'}
                </button>
              </div>

              {promotionBatch ? (
                <div className="ck-form-card" style={{ marginTop: 16 }}>
                  <div className="ck-form-head">
                    Preview - {promotionBatch.items?.length || 0} students - {promotionBatch.status}
                  </div>
                  <div className="ck-form-body">
                    {promotionBatch.status === 'APPLIED' ? (
                      <div className="ck-alert ck-alert-g" style={{ marginBottom: 12 }}>
                        <span>✓</span>
                        <div>{promotionBatch.promoted || 0} promoted. {promotionBatch.skipped || 0} skipped.</div>
                      </div>
                    ) : null}
                    <div className="ck-table-wrap">
                      <table className="ck-table">
                        <thead>
                          <tr>
                            <th>Student</th>
                            <th>From</th>
                            <th>To</th>
                            <th>Action</th>
                            <th />
                          </tr>
                        </thead>
                        <tbody>
                          {(promotionBatch.items || []).slice(0, 100).map((item) => {
                            const action = (item.action || '').toUpperCase();
                            return (
                              <tr key={item.id}>
                                <td>
                                  <div className="tb">{item.studentName}</div>
                                  <div className="ts">{item.admissionNumber}</div>
                                </td>
                                <td>{item.sourceClassName || '-'} {item.sourceSectionName ? `- ${item.sourceSectionName}` : ''}</td>
                                <td>{item.targetClassName || '-'} {item.targetSectionName ? `- ${item.targetSectionName}` : ''}</td>
                                <td><span className={`ck-status ${action === 'PROMOTE' ? 'sg' : 'sam'}`}>{item.action || '-'}</span></td>
                                <td>
                                  <div className="ck-actions-inline">
                                    <button className="ck-btn ck-btn-ghost ck-btn-sm" disabled={promotionLoading || promotionBatch.status !== 'DRAFT' || action === 'PROMOTE'} onClick={() => void updatePromotionItem(item.id, 'PROMOTE')}>Promote</button>
                                    <button className="ck-btn ck-btn-ghost ck-btn-sm" disabled={promotionLoading || promotionBatch.status !== 'DRAFT' || action === 'HOLD'} onClick={() => void updatePromotionItem(item.id, 'HOLD')}>Hold</button>
                                  </div>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                    {(promotionBatch.items || []).length > 100 ? <div className="ts" style={{ marginTop: 8 }}>Showing first 100 students. Apply still processes the full batch.</div> : null}
                  </div>
                </div>
              ) : null}
            </div>
            <div className="ck-modal-foot">
              {promotionBatch && promotionBatch.status === 'DRAFT' ? <button className="ck-btn ck-btn-g" onClick={() => void applyPromotionBatch()} disabled={promotionLoading || (promotionBatch.items || []).length === 0}>Apply promotion</button> : null}
              <button className="ck-btn ck-btn-ghost" onClick={() => setPromotionOpen(false)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
