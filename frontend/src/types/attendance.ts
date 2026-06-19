/**
 * Attendance-related TypeScript types for frontend API contracts.
 */

export interface StudentAttendanceRecord {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  photoUrl: string | null;
  status: 'PRESENT' | 'ABSENT' | null;
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
    status: 'PRESENT' | 'ABSENT';
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
