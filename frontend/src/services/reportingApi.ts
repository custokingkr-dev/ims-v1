import api from './api';
import { createReportingClient } from '../generated/reportingClient';

export const reportingClient = createReportingClient(api);
