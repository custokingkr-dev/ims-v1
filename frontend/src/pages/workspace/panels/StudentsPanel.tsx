import React, { useEffect, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Info } from '../ui';
import { formatAddress, initials, attendanceNumber } from '../utils';
import type { PanelKey } from '../config';

interface Props {
  setPanel: (key: PanelKey) => void;
  onRefresh: () => void;
}

export function StudentsPanel({ setPanel, onRefresh }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const schoolScopedParams = !can('platform:admin') && user?.branchId ? { schoolId: user.branchId } : undefined;

  const [studentFilters, setStudentFilters] = useState({ className: 'All', sectionName: 'All', feeStatus: 'All' });
  const [studentsView, setStudentsView] = useState<any>({ items: [], filteredCount: 0, filteredSections: 0, filters: { classes: [], sections: [], feeStatuses: ['Paid', 'Overdue', 'Pending', 'Partial'] } });
  const [studentsLoading, setStudentsLoading] = useState(false);
  const [studentDetail, setStudentDetail] = useState<any | null>(null);
  const [studentModalOpen, setStudentModalOpen] = useState(false);
  const [studentModalLoading, setStudentModalLoading] = useState(false);
  const [photoFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const loadStudents = async (filters = studentFilters) => {
    try {
      setStudentsLoading(true);
      const params: Record<string, string> = {};
      if (filters.className !== 'All') params.class = filters.className;
      if (filters.sectionName !== 'All') params.section = filters.sectionName;
      if (filters.feeStatus !== 'All') params.feeStatus = filters.feeStatus;
      const res = await api.get('/students', { params: { ...params, ...(schoolScopedParams || {}) } });
      setStudentsView(res.data);
    } finally {
      setStudentsLoading(false);
    }
  };

  useEffect(() => {
    loadStudents(studentFilters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [studentFilters]);

  const openStudentModal = async (student: any) => {
    try {
      setStudentModalOpen(true);
      setStudentModalLoading(true);
      const res = await api.get(`/students/${student.id}`);
      setStudentDetail(res.data);
    } catch {
      setStudentDetail(student);
    } finally {
      setStudentModalLoading(false);
    }
  };

  const handleEditStudent = (student: any) => {
    setStudentModalOpen(false);
    setPanel('addstudent');
  };

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
        {photoFeedback ? (
          <div className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}>
            <span>{photoFeedback.type === 'success' ? '✓' : '!'}</span>
            <div>{photoFeedback.message}</div>
          </div>
        ) : null}

        <div className="ck-form-card" style={{ marginBottom: 16 }}>
          <div className="ck-form-body">
            <div className="ck-form-grid ck-fg-4">
              <div className="ck-field">
                <label>Class</label>
                <select value={studentFilters.className} onChange={(e) => setStudentFilters({ ...studentFilters, className: e.target.value })}>
                  <option>All</option>
                  {(studentsView.filters?.classes || []).map((v: string) => <option key={v} value={v}>{v}</option>)}
                </select>
              </div>
              <div className="ck-field">
                <label>Section</label>
                <select value={studentFilters.sectionName} onChange={(e) => setStudentFilters({ ...studentFilters, sectionName: e.target.value })}>
                  <option>All</option>
                  {(studentsView.filters?.sections || []).map((v: string) => <option key={v} value={v}>{v}</option>)}
                </select>
              </div>
              <div className="ck-field">
                <label>Fee Status</label>
                <select value={studentFilters.feeStatus} onChange={(e) => setStudentFilters({ ...studentFilters, feeStatus: e.target.value })}>
                  <option>All</option>
                  <option>Paid</option>
                  <option>Overdue</option>
                  <option>Pending</option>
                  <option>Partial</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        <div className="ck-card">
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
            <tbody>
              {studentsLoading ? (
                <tr><td colSpan={7}><div className="ts">Loading students…</div></td></tr>
              ) : (studentsView.items || []).length === 0 ? (
                <tr><td colSpan={7}><div className="ts">No students match the selected filters.</div></td></tr>
              ) : (
                (studentsView.items || []).map((student: any) => {
                  const attendanceValue = attendanceNumber(student.attendance);
                  return (
                    <tr key={student.id}>
                      <td>
                        <div className="ck-student-cell">
                          {student.photoUrl
                            ? <img src={student.photoUrl} alt={student.name} className="ck-student-avatar" />
                            : <div className="ck-student-avatar ck-student-avatar-fallback">{initials(student.name)}</div>
                          }
                          <div>
                            <div className="tb">{student.name}</div>
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
                      <td>
                        <span className={`ck-status ${student.feeStatus === 'Paid' ? 'sg' : student.feeStatus === 'Overdue' ? 'sr' : 'sam'}`}>
                          {student.feeStatus}
                        </span>
                      </td>
                      <td>
                        <div className="ck-mini-progress-cell">
                          <div className="tb">{student.attendance}</div>
                          <div className="ck-mini-progress">
                            <div className="ck-mini-progress-fill" style={{ width: `${attendanceValue}%` }} />
                          </div>
                        </div>
                      </td>
                      <td>
                        <button className="ck-btn ck-btn-ghost" onClick={() => openStudentModal(student)}>View</button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </ModuleShell>

      {studentModalOpen && (
        <div className="ck-modal-bg" onClick={() => setStudentModalOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Student details</div>
              <button className="ck-modal-x" onClick={() => setStudentModalOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {studentModalLoading || !studentDetail ? (
                <div className="ts">Loading student details…</div>
              ) : (
                <div className="ck-student-modal-grid">
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
                          <strong>{studentDetail.attendance}</strong>
                        </div>
                        <div className="ck-progress-bar">
                          <div className="ck-progress-fill" style={{ width: `${attendanceNumber(studentDetail.attendance)}%` }} />
                        </div>
                      </div>
                      <span className={`ck-status ${studentDetail.feeStatus === 'Paid' ? 'sg' : studentDetail.feeStatus === 'Overdue' ? 'sr' : 'sam'}`}>
                        {studentDetail.feeStatus}
                      </span>
                    </div>
                  </div>
                </div>
              )}
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setStudentModalOpen(false)}>Close</button>
              {studentDetail ? (
                <button className="ck-btn ck-btn-g" onClick={() => handleEditStudent(studentDetail)}>Edit Student</button>
              ) : null}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
