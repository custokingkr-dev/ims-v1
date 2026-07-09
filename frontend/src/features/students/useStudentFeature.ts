import { useState } from 'react';
import type { StudentDetail, StudentFilters, StudentFormState, StudentsView } from './types';
import { emptyStudentProfileForm } from './profileForm';

const DEFAULT_FORM: StudentFormState = emptyStudentProfileForm();

const DEFAULT_VIEW: StudentsView = {
  items: [], filteredCount: 0, filteredSections: 0,
  filters: { classes: [], sections: [], feeStatuses: ['Paid', 'Overdue', 'Pending', 'Partial'] },
};

/** Encapsulates all student-domain state for UnifiedWorkspacePage. */
export function useStudentFeature() {
  const [studentForm, setStudentForm] = useState<StudentFormState>(DEFAULT_FORM);
  const [studentFilters, setStudentFilters] = useState<StudentFilters>({ className: 'All', sectionName: 'All', feeStatus: 'All' });
  const [studentsView, setStudentsView] = useState<StudentsView>(DEFAULT_VIEW);
  const [studentsLoading, setStudentsLoading] = useState(false);
  const [studentDetail, setStudentDetail] = useState<StudentDetail | null>(null);
  const [studentModalOpen, setStudentModalOpen] = useState(false);
  const [studentModalLoading, setStudentModalLoading] = useState(false);
  const [editingStudentId, setEditingStudentId] = useState<number | null>(null);

  const resetStudentForm = () => setStudentForm(emptyStudentProfileForm());

  return {
    studentForm, setStudentForm, resetStudentForm,
    studentFilters, setStudentFilters,
    studentsView, setStudentsView,
    studentsLoading, setStudentsLoading,
    studentDetail, setStudentDetail,
    studentModalOpen, setStudentModalOpen,
    studentModalLoading, setStudentModalLoading,
    editingStudentId, setEditingStudentId,
  };
}
