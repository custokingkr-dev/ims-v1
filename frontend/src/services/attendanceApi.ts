import api from './api';
import { createAttendanceClient } from '../generated/attendanceClient';

export const attendanceClient = createAttendanceClient(api);
