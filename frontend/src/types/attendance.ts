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

export interface AbsenteeStudent {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  classSection: string;
  parentContact: string;
  hasContact: boolean;
  alreadyQueued: boolean;
}
export interface AbsenteeListResponse {
  date: string;
  sectionId: string | null;
  students: AbsenteeStudent[];
  totalAbsent: number;
  queuedCount: number;
}
export interface NotifyAbsenteesResponse {
  date: string;
  queued: number;
  skippedNoContact: number;
  skippedAlreadyQueued: number;
}
