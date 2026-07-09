import type { StudentProfileFormState } from './profileForm';

export type StudentFormState = StudentProfileFormState;

export interface StudentFilters {
  className: string;
  sectionName: string;
  feeStatus: string;
}

export interface StudentsView {
  items: StudentRow[];
  filteredCount: number;
  filteredSections: number;
  filters: {
    classes: string[];
    sections: string[];
    feeStatuses: string[];
  };
}

export interface StudentRow {
  id: number;
  admissionNumber: string;
  fullName: string;
  classId?: string;
  className?: string;
  gradeLevel?: string;
  sectionId?: string;
  sectionName: string;
  feeStatus: string;
  [key: string]: unknown;
}

export interface StudentDetail {
  id: number;
  fullName: string;
  [key: string]: unknown;
}
