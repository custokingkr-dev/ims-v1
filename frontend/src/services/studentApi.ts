import api from './api';
import { createStudentClient } from '../generated/studentClient';

export const studentClient = createStudentClient(api);
