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
