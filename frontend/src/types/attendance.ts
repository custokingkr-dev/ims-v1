/**
 * Attendance-related TypeScript types for frontend API contracts.
 */

export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE';

/** Local editable state also allows null = unmarked. */
export type EditableAttendanceStatus = AttendanceStatus | null;

export interface StudentEditRecord {
  studentId: number;
  status: EditableAttendanceStatus;
  remarks: string;
}

export interface StudentAttendanceRecord {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  photoUrl: string | null;
  status: AttendanceStatus | null;
  remarks: string;
}

export interface SectionRegisterResponse {
  date: string;
  classId: string;
  sectionId: string;
  sectionName: string;
  locked: boolean;
  totalStudents: number;
  presentCount: number;
  lateCount: number;
  leaveCount: number;
  absentCount: number;
  presentPercent: number;
  students: StudentAttendanceRecord[];
}

export interface SaveSectionRegisterRequest {
  date: string;
  classId: string;
  sectionId: string;
  records: Array<{
    studentId: number;
    status: AttendanceStatus;
    remarks: string;
  }>;
}

export interface SubmitSectionRequest {
  date: string;
  classId: string;
  sectionId: string;
}

export interface AttendanceDailySummarySection {
  sectionId: string;
  classId: string;
  sectionName: string;
  totalStudents: number;
  presentCount: number;
  lateCount: number;
  leaveCount: number;
  absentCount: number;
  presentPercent: number;
  teacherName: string;
  status: 'Pending' | 'Saved' | 'Submitted';
  locked: boolean;
}

export interface AttendanceDailySummaryResponse {
  date: string;
  dateLabel: string;
  overallPercent: number;
  sections: AttendanceDailySummarySection[];
  allSubmitted: boolean;
  nonWorkingDay: boolean;
}

export interface RegisterDay { date: string; dayOfMonth: number; weekday: string; nonWorkingDay: boolean; }
export interface RegisterCell { date: string; status: AttendanceStatus | null; }
export interface RegisterStudentRow {
  studentId: number; admissionNo: string; rollNo: string; fullName: string;
  cells: RegisterCell[];
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number;
}
export interface RegisterDayTotal { date: string; presentCount: number; lateCount: number; leaveCount: number; absentCount: number; }
export interface AttendanceRegisterReport {
  month: string; monthLabel: string; classId: string; sectionId: string; sectionName: string; teacherName: string;
  days: RegisterDay[]; students: RegisterStudentRow[]; dayTotals: RegisterDayTotal[];
  totals: { presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number };
}

export interface StudentHistoryDay { date: string; weekday: string; status: AttendanceStatus | null; remarks: string; nonWorkingDay: boolean; }
export interface AttendanceStudentHistory {
  student: { studentId: number; admissionNo: string; rollNo: string; fullName: string; sectionName: string };
  from: string; to: string; days: StudentHistoryDay[];
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number; daysRecorded: number;
}

export interface SummarySection {
  classId: string; sectionId: string; sectionName: string; teacherName: string;
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number; daysRecorded: number;
}
export interface AttendanceSummaryReport {
  from: string; to: string; sections: SummarySection[];
  overall: { presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number };
}

/**
 * Delivery lifecycle of a queued absentee notification, written by the school-core delivery worker.
 * SENT is reserved for a real, non-dry-run provider send; SENT_DRY_RUN means every step ran except
 * the actual message.
 */
export type AbsenteeNotificationStatus =
  | 'QUEUED'
  | 'SENT'
  | 'SENT_DRY_RUN'
  | 'FAILED'
  | 'DEAD_LETTER'
  | 'SUPPRESSED';

/** Absence alerts go to parents on WhatsApp only. */
export type AbsenteeAlertChannel = 'WHATSAPP';

/**
 * How the school-core delivery worker runs. DRY_RUN runs every step except sending the message, so no
 * parent receives anything; LIVE hands each alert to the messaging provider. Older backends omit it,
 * and the screen then treats delivery as not switched on.
 */
export type AbsenteeDeliveryMode = 'DRY_RUN' | 'LIVE';

/** Present on every absentee/exception list and on the notify response. */
interface AbsenteeAlertDelivery {
  channel?: AbsenteeAlertChannel;
  deliveryMode?: AbsenteeDeliveryMode;
}

export interface AbsenteeStudent {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  classSection: string;
  parentContact: string;
  hasContact: boolean;
  alreadyQueued: boolean;
  /** null when no notification has been queued for this student on this date. */
  notificationStatus?: AbsenteeNotificationStatus | null;
  /**
   * For an ABSENT row with no alert yet: whether queuing would pass the guardian-communication policy
   * (primary guardian, verified phone, consent). null on other rows; missing from older backends.
   */
  alertAllowed?: boolean | null;
  /** The policy's reason code when alertAllowed is false (e.g. CONTACT_NOT_VERIFIED), else null. */
  alertBlockedReason?: string | null;
}
export interface AbsenteeListResponse extends AbsenteeAlertDelivery {
  date: string;
  sectionId: string | null;
  students: AbsenteeStudent[];
  totalAbsent: number;
  queuedCount: number;
}
export interface AttendanceExceptionStudent extends AbsenteeStudent {
  status: 'ABSENT' | 'LATE' | 'LEAVE';
  remarks: string;
}
export interface AttendanceExceptionListResponse extends AbsenteeAlertDelivery {
  date: string;
  sectionId: string | null;
  students: AttendanceExceptionStudent[];
  totalExceptions: number;
  absentCount: number;
  lateCount: number;
  leaveCount: number;
  queuedCount: number;
}
export interface NotifyAbsenteesResponse extends AbsenteeAlertDelivery {
  date: string;
  queued: number;
  /** Students whose guardian has no phone number; they are also counted in suppressedCount. */
  skippedNoContact: number;
  skippedAlreadyQueued: number;
  /** Students the guardian-communication policy refused (no number, unverified contact, no consent, ...). */
  suppressedCount?: number;
}
