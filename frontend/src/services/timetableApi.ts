import api from './api';

export interface BellPeriod {
  id: number;
  label: string;
  start: string;
  end: string;
  isBreak: boolean;
  sortOrder: number;
}

export interface BellSchedule {
  id: number;
  name: string;
  periods: BellPeriod[];
}

export interface ClassScheduleRow {
  classId: string;
  className: string;
  scheduleId: number | null;
}

export const getBellSchedules = (p?: object) => api.get<BellSchedule[]>('/timetable/bell-schedules', { params: p });
export const createSchedule = (name: string) => api.post('/timetable/bell-schedules', { name });
export const renameSchedule = (id: number, name: string) => api.put(`/timetable/bell-schedules/${id}`, { name });
export const deleteSchedule = (id: number) => api.delete(`/timetable/bell-schedules/${id}`);
export const addPeriod = (id: number, b: Omit<BellPeriod, 'id'>) => api.post(`/timetable/bell-schedules/${id}/periods`, b);
export const updatePeriod = (id: number, pid: number, b: Omit<BellPeriod, 'id'>) => api.put(`/timetable/bell-schedules/${id}/periods/${pid}`, b);
export const deletePeriod = (id: number, pid: number) => api.delete(`/timetable/bell-schedules/${id}/periods/${pid}`);
export const swapPeriods = (scheduleId: number, idA: number, idB: number) =>
  api.put(`/timetable/bell-schedules/${scheduleId}/periods/swap`, { idA, idB });
export const getClassSchedules = (p?: object) => api.get<ClassScheduleRow[]>('/timetable/class-schedules', { params: p });
export const setClassSchedule = (classId: string, scheduleId: number) => api.put(`/timetable/class-schedules/${encodeURIComponent(classId)}`, { scheduleId });
export const unassignClass = (classId: string) => api.delete(`/timetable/class-schedules/${encodeURIComponent(classId)}`);

export interface ClassSubject {
  id: number;
  subjectName: string;
  sortOrder: number;
  weeklyPeriods: number;
  preferredPartOfDay: 'ANY' | 'MORNING' | 'AFTERNOON';
  requiredRoomType: string | null;
  doublePeriod: boolean;
}
export interface ClassSubjects { editable: boolean; yearId: string; subjects: ClassSubject[]; }
export const getClassSubjects = (classId: string, yearId?: string) => api.get<ClassSubjects>('/timetable/class-subjects', { params: { classId, yearId } });
export const addSubject = (classId: string, subjectName: string, yearId?: string) =>
  api.post('/timetable/class-subjects', { classId, subjectName, yearId });
export const updateSubjectPolicy = (id: number, body: Partial<Omit<ClassSubject, 'id' | 'subjectName' | 'sortOrder'>>) =>
  api.put<ClassSubject>(`/timetable/class-subjects/${id}`, body);
export const deleteSubject = (id: number) => api.delete(`/timetable/class-subjects/${id}`);

export interface TimetableEntry {
  day: string;
  periodId: number;
  subjectName: string;
  teacherId: number | null;
  teacherName: string | null;
  roomId: number | null;
  roomName: string | null;
  roomType: string | null;
}

export interface TimetableView {
  editable: boolean;
  yearId: string;
  sectionId: string;
  classId?: string;
  scheduleId?: number;
  noSchedule?: boolean;
  days: string[];
  periods: BellPeriod[];
  entries: TimetableEntry[];
  conflict?: string | null;
  publication?: TimetablePublication;
}

export const getTimetable = (sectionId: string, yearId?: string) =>
  api.get<TimetableView>('/timetable', { params: { sectionId, yearId } });
export const putEntry = (b: { sectionId: string; day: string; periodId: number; subjectName: string; teacherId: number | null; roomId?: number | null }) =>
  api.put<TimetableView>('/timetable/entry', b);
export const deleteEntry = (p: { sectionId: string; day: string; periodId: number }) =>
  api.delete('/timetable/entry', { params: p });
export const putEntriesBulk = (b: { sectionId: string; entries: { day: string; periodId: number; subjectName: string; teacherId: number | null; roomId?: number | null }[] }) =>
  api.put<TimetableView>('/timetable/entries/bulk', b);

export interface TimetableRoom {
  id: number;
  name: string;
  roomType: string;
  capacity: number;
  active: boolean;
}

export interface TimetableIssue {
  code: string;
  label: string;
  count: number;
  severity: 'HARD' | 'PREFERENCE';
}

export interface TimetablePublication {
  id?: number;
  revision?: number;
  label?: string;
  publishedAt?: string;
  publishedBy?: number | null;
}

export interface TimetableHealth {
  academicYearId: string;
  sectionId?: string;
  score: number;
  readyToPublish: boolean;
  hardConflicts: number;
  teacherConflicts: number;
  roomConflicts: number;
  unavailableTeachers: number;
  unassignedTeachers: number;
  missingRooms: number;
  roomCapacityIssues: number;
  unscheduledSections: number;
  quotaIssues: number;
  preferredTimeIssues: number;
  doublePeriodIssues: number;
  preferenceIssues: number;
  issues: TimetableIssue[];
  publication?: TimetablePublication;
}

export interface TimetableOverviewEntry extends TimetableEntry {
  id: number;
  sectionId: string;
  sectionName: string;
  classId: string;
  className: string;
  periodLabel: string;
  start: string;
  end: string;
}

export const getRooms = () => api.get<TimetableRoom[]>('/timetable/rooms');
export const createRoom = (body: { name: string; roomType: string; capacity: number }) =>
  api.post<TimetableRoom>('/timetable/rooms', body);

export interface TeacherAvailability {
  id?: number;
  teacherId: number;
  teacherName?: string;
  day: string;
  periodId: number;
  periodLabel?: string;
  available: boolean;
  note?: string | null;
}

export const getTeacherAvailability = (yearId?: string, teacherId?: number) =>
  api.get<TeacherAvailability[]>('/timetable/teacher-availability', { params: { yearId, teacherId } });
export const saveTeacherAvailability = (body: Omit<TeacherAvailability, 'id' | 'teacherName' | 'periodLabel'> & { yearId: string }) =>
  api.put<TeacherAvailability>('/timetable/teacher-availability', body);
export const getTimetableHealth = (yearId?: string, sectionId?: string) =>
  api.get<TimetableHealth>('/timetable/health', { params: { yearId, sectionId } });
export const getTimetableOverview = (yearId?: string) =>
  api.get<TimetableOverviewEntry[]>('/timetable/overview', { params: { yearId } });
export const publishTimetable = (yearId: string, label?: string) =>
  api.post<TimetablePublication>('/timetable/publish', { yearId, label });
