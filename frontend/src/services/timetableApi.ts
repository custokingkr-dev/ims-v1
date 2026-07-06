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
// subjects (Task 5) + timetable (Task 7) added in their tasks.
