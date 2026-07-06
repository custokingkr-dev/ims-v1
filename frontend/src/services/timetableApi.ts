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
export const getClassSchedules = (p?: object) => api.get<ClassScheduleRow[]>('/timetable/class-schedules', { params: p });
export const setClassSchedule = (classId: string, scheduleId: number) => api.put(`/timetable/class-schedules/${encodeURIComponent(classId)}`, { scheduleId });

export interface ClassSubjects { editable: boolean; yearId: string; subjects: { id: number; subjectName: string; sortOrder: number }[]; }
export const getClassSubjects = (classId: string, yearId?: string) => api.get<ClassSubjects>('/timetable/class-subjects', { params: { classId, yearId } });
export const addSubject = (classId: string, subjectName: string) => api.post('/timetable/class-subjects', { classId, subjectName });
export const deleteSubject = (id: number) => api.delete(`/timetable/class-subjects/${id}`);

export interface TimetableEntry {
  day: string;
  periodId: number;
  subjectName: string;
  teacherId: number | null;
  teacherName: string | null;
}

export interface TimetableView {
  editable: boolean;
  yearId: string;
  sectionId: string;
  noSchedule?: boolean;
  days: string[];
  periods: BellPeriod[];
  entries: TimetableEntry[];
  conflict?: string | null;
}

export const getTimetable = (sectionId: string, yearId?: string) =>
  api.get<TimetableView>('/timetable', { params: { sectionId, yearId } });
export const putEntry = (b: { sectionId: string; day: string; periodId: number; subjectName: string; teacherId: number | null }) =>
  api.put<TimetableView>('/timetable/entry', b);
export const deleteEntry = (p: { sectionId: string; day: string; periodId: number }) =>
  api.delete('/timetable/entry', { params: p });
