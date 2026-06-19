export interface DashboardCommandCenterResponse {
  fees: FeeSection;
  photography: PhotographySection;
  lifecycle: LifecycleSection;
  attendance: AttendanceSection;
  vendorDues: VendorDuesSection;
  reorderSignals: ReorderSection;
}

export interface ReorderSection {
  alertCount: number;
}

// ── Reorder Prediction ────────────────────────────────────────────────────────

export interface ReorderSignalItem {
  category: string;
  lastOrderDate: string;
  daysSinceLastOrder: number;
  avgIntervalDays: number | null;
  predictedNextOrderDate: string | null;
  alertLevel: 'RED' | 'YELLOW' | 'OK';
}

export interface ReorderSignalsResponse {
  alertCount: number;
  items: ReorderSignalItem[];
}

export interface VendorDuesSection {
  catalogOrderCount: number;
  catalogOrderTotalPaise: number;
  firefightingCount: number;
  firefightingTotalPaise: number;
  totalDuesPaise: number;
}

// ── Vendor Payment Dues ───────────────────────────────────────────────────────

export interface VendorDueItem {
  sourceType: 'CATALOG_ORDER' | 'FIREFIGHTING';
  id: string;
  title: string;
  category: string;
  vendorName: string | null;
  amountPaise: number;
  status: string;
  createdAt: string;
}

export interface VendorDuesListResponse {
  catalogOrderCount: number;
  catalogOrderTotalPaise: number;
  firefightingCount: number;
  firefightingTotalPaise: number;
  totalDuesPaise: number;
  items: VendorDueItem[];
}

export interface MarkVendorPaidRequest {
  notes?: string | null;
}

export interface FeeSection {
  defaulterCount: number;
  totalOverdueAmountPaise: number;
  oldestDueDays: number;
}

// ── Fee Defaulter list ────────────────────────────────────────────────────────

export interface FeeDefaulterListResponse {
  totalDefaulters: number;
  totalOverdueAmount: number;
  oldestDueDays: number;
  items: FeeDefaulterItem[];
  page: number;
  size: number;
  totalElements: number;
}

export interface FeeDefaulterItem {
  studentId: number;
  studentName: string;
  admissionNo: string;
  className: string;
  sectionName: string;
  parentName: string | null;
  parentPhone: string | null;
  dueAmount: number;
  dueDate: string | null;
  daysOverdue: number;
  lastReminderSentAt: string | null;
  reminderStatus: 'NOT_SENT' | 'SENT' | 'PENDING' | 'FAILED';
  paymentStatus: string;
}

export interface SendFeeRemindersRequest {
  studentIds: number[];
  channel: string;
  message: string;
  context?: string;
}

export interface SendFeeRemindersResult {
  sentCount: number;
  failedCount: number;
  failedItems: Array<{ studentId: number; reason: string }>;
}

export interface PhotographySection {
  eventId: string | null;
  collectedAmount: number;
  pendingAmount: number;
  targetAmount: number;
}

export interface LifecycleSection {
  pendingReviewCount: number;
  longAbsenceCount: number;
}

export interface AttendanceSection {
  sectionsBelowThresholdCount: number;
  thresholdPercent: number;
}

// ── Class Photography payment status ──────────────────────────────────────────

export interface ClassPhotographyPaymentStatusResponse {
  eventId: string | null;
  title: string | null;
  eventDate: string | null;
  totalBudget: number;
  schoolContribution: number;
  studentContributionTarget: number;
  collectedAmount: number;
  pendingAmount: number;
  students: PhotoContributionItem[];
  page: number;
  size: number;
  totalElements: number;
}

export interface PhotoContributionItem {
  studentId: number;
  studentName: string;
  admissionNo: string;
  className: string;
  sectionName: string;
  parentPhone: string | null;
  expectedAmount: number;
  paidAmount: number;
  pendingAmount: number;
  status: 'PENDING' | 'PARTIAL' | 'PAID';
  lastReminderSentAt: string | null;
}

export interface SendEventPaymentRemindersRequest {
  studentIds: number[];
  channel: string;
  message: string;
}

export interface SendEventPaymentRemindersResult {
  sentCount: number;
  failedCount: number;
  failedItems: Array<{ studentId: number; reason: string }>;
}

// ── Student Review Campaigns ──────────────────────────────────────────────────

export interface IdCardReviewStatusResponse {
  campaignId: string | null;
  totalStudents: number;
  completed: number;
  pending: number;
  needsCorrection: number;
  completionPercent: number;
  recentItems: ReviewItemDetail[];
}

export interface FullNameVerificationStatusResponse {
  campaignId: string | null;
  totalStudents: number;
  confirmed: number;
  correctionRequested: number;
  pending: number;
  completionPercent: number;
}

export interface ReviewItemDetail {
  itemId: string;
  studentId: number;
  studentName: string;
  admissionNo: string;
  className: string;
  sectionName: string;
  currentFullName: string | null;
  suggestedFullName: string | null;
  status: 'PENDING' | 'COMPLETED' | 'NEEDS_CORRECTION';
  verifiedPhoto: boolean;
  verifiedFullName: boolean;
  verifiedAdmissionNo: boolean;
  verifiedClassSection: boolean;
  verifiedRollNo: boolean;
  verifiedFatherName: boolean;
  verifiedFatherContact: boolean;
  verifiedAddress: boolean;
  verifiedBloodGroup: boolean;
  parentConfirmed: boolean;
  teacherConfirmed: boolean;
  correctionRequested: boolean;
  correctionNotes: string | null;
  completedAt: string | null;
}

export interface InitiateIdCardReviewRequest {
  classIds?: string[] | null;
  sectionIds?: string[] | null;
  dueDate?: string | null;
  assignedToUserId?: number | null;
}

export interface InitiateFullNameVerificationRequest {
  classIds?: string[] | null;
  sectionIds?: string[] | null;
  verifier: 'TEACHER' | 'PARENT' | 'BOTH';
  dueDate?: string | null;
}

export interface UpdateReviewItemRequest {
  verifiedPhoto?: boolean | null;
  verifiedFullName?: boolean | null;
  verifiedAdmissionNo?: boolean | null;
  verifiedClassSection?: boolean | null;
  verifiedRollNo?: boolean | null;
  verifiedFatherName?: boolean | null;
  verifiedFatherContact?: boolean | null;
  verifiedAddress?: boolean | null;
  verifiedBloodGroup?: boolean | null;
  status?: string | null;
  correctionNotes?: string | null;
}

export interface VerifyFullNameRequest {
  confirmed: boolean;
  suggestedFullName?: string | null;
  correctionNotes?: string | null;
}

// ── Low Attendance Meeting Invites ────────────────────────────────────────────

export interface LowAttendanceSectionItem {
  sectionId: string;
  sectionName: string;
  className: string;
  presentCount: number;
  totalEnrolled: number;
  attendancePct: number;
  studentsBelowThreshold: number;
}

export interface LowAttendanceSectionsResponse {
  date: string;
  thresholdPercent: number;
  sections: LowAttendanceSectionItem[];
}

export interface LowAttendanceStudentItem {
  studentId: number;
  studentName: string;
  admissionNo: string;
  className: string;
  sectionName: string;
  fatherName: string | null;
  fatherContact: string | null;
  attendancePercent: number | null;
  lastInviteSentAt: string | null;
}

export interface SendMeetingInvitesRequest {
  studentIds: number[];
  channel: string;
  message: string;
  meetingDate?: string | null;
}

export interface SendMeetingInvitesResult {
  sentCount: number;
  failedCount: number;
  failedStudentIds: number[];
}
