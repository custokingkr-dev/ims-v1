export interface PaymentForm {
  studentId: string;
  studentName: string;
  amount: string;
  paymentMode: string;
  notes: string;
}

export interface FeeAssignForm {
  studentId: string;
  bandId: string;
  paymentSchedule: string;
  bandDiscount: string;
  manualDiscount: string;
  surcharge: string;
}

export interface FeeFilters {
  className: string;
  sectionName: string;
}

export interface SelectionState {
  classId: string;
  sectionId: string;
  studentId: string;
}

export interface FeeStructureData {
  academicYear: string;
  academicYearId: string;
  bands: FeeBand[];
}

export interface FeeBand {
  id: string;
  name: string;
  classFrom: number;
  classTo: number;
  discount: number;
  schedules: string[];
  items: FeeItem[];
}

export interface FeeItem {
  id: string;
  itemName: string;
  frequency: string;
  amount: number;
}

export interface BandForm {
  name: string;
  classFrom: string;
  classTo: string;
  discount: string;
  schedules: string[];
}
