export interface Address {
  houseNumber?: string;
  street?: string;
  locality?: string;
  city?: string;
  state?: string;
  pinCode?: string;
}

export interface Student {
  id: number;
  name: string;
  fullName?: string;
  admissionNumber: string;
  boardRegistrationNumber?: string;
  rollNo?: string;
  classSection: string;
  className?: string;
  sectionName?: string;
  academicYear: string;
  feeStatus: string;
  attendance?: string;
  photoUrl?: string;
  fatherName?: string;
  fatherContact?: string;
  parentPhone?: string;
  motherName?: string;
  dateOfBirth?: string;
  address?: Address;
  joined?: string;
}

export interface FeeItem {
  id: string;
  name: string;
  frequency: string;
  amount: number;
}

export interface FeeStructureBand {
  id: string;
  name: string;
  classFrom: number;
  classTo: number;
  discount: number;
  annualTotal?: number;
  activeSchedules?: string[];
  items: FeeItem[];
}

export interface AttendanceSection {
  sectionName: string;
  totalStudents: number;
  presentPercent: number | null;
  teacherName?: string;
  status: string;
}

export interface Quotation {
  id?: string;
  vendorName: string;
  amount: string | number;
  deliveryTimeline: string;
  notes: string;
  documentUrl: string;
  isCustoking?: boolean;
}

export interface FirefightingRequest {
  id?: string;
  code: string;
  title: string;
  category: string;
  status: string;
  urgency: string;
  estimatedBudget?: number;
  amount?: number;
  winnerAmount?: number;
  winnerVendor?: string;
  winner?: string;
  date?: string;
  description?: string;
  summary?: string;
  requiredByDate?: string;
  quotations?: Quotation[];
}

export interface SupplyOrder {
  id: string;
  category: string;
  description?: string;
  title?: string;
  items?: string;
  totalAmount?: number;
  subtotal?: number;
  gst?: number;
  status: string;
  placedAt?: string;
  date?: string;
  schoolName?: string;
  school?: string;
  schoolId?: number;
  orderData?: string;
  requiredByDate?: string;
  estimatedDelivery?: string;
}

export interface SaInvoice {
  id: string;
  school?: string;
  orderRef?: string;
  description?: string;
  qty?: number;
  rate?: number;
  amount?: number;
  gstAmount?: number;
  total?: number;
  status?: string;
  issuedAt?: string;
  dueAt?: string;
  notes?: string;
  schoolId?: number;
}

export interface WorkspaceSchool {
  name: string;
  meta: string;
  students?: number;
}

export interface WorkspaceDashboard {
  students: number;
  sections: number;
  attendancePercent: number;
  attendancePresent: number;
  feeCollectedLakh: string;
  feeTargetLakh: string;
  feeOverdueCount: number;
  firefightingActive: number;
  pendingApprovals: number;
}

export interface WorkspaceData {
  school: WorkspaceSchool;
  dashboard: WorkspaceDashboard;
  recentActivity: Array<{ icon: string; title: string; meta: string; tag: string; tagClass?: string }>;
  timetable: Array<{ id?: string; day: string; period: string; classSection: string; subject: string; teacher: string }>;
  staff: Array<{ id: string; name: string; designation: string; department: string; payrollStatus: string; monthlySalary: number }>;
  annualPlan: { terms: Array<{ term: string; category: string; status: string; quantity: string; amount: number }> };
  fees?: {
    summary: { progressPercent: number; collected: number; outstanding: number; overdueCount: number; target: number };
    records?: unknown[];
  };
  firefighting?: { requests: FirefightingRequest[] };
  orders?: SupplyOrder[];
}
