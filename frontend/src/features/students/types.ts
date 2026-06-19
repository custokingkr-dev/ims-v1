export interface StudentFormState {
  admissionNumber: string;
  boardRegistrationNumber: string;
  fullName: string;
  dateOfBirth: string;
  gender: string;
  gradeLevel: string;
  sectionName: string;
  academicYear: string;
  admissionDate: string;
  houseNumber: string;
  street: string;
  locality: string;
  city: string;
  state: string;
  pinCode: string;
  fatherName: string;
  fatherContactNumber: string;
  paymentSchedule: string;
  manualDiscountOverride: string;
}

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
  gradeLevel: string;
  sectionName: string;
  feeStatus: string;
  [key: string]: unknown;
}

export interface StudentDetail {
  id: number;
  fullName: string;
  [key: string]: unknown;
}
