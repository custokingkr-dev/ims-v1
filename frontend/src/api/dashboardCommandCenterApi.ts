import api, { getAuthSessionVersion } from '../services/api';
import { reportingClient } from '../services/reportingApi';
import { studentClient } from '../services/studentApi';
import type {
  DashboardCommandCenterResponse,
  FeeDefaulterListResponse,
  SendFeeRemindersRequest,
  SendFeeRemindersResult,
  ClassPhotographyPaymentStatusResponse,
  SendEventPaymentRemindersRequest,
  SendEventPaymentRemindersResult,
  IdCardReviewStatusResponse,
  FullNameVerificationStatusResponse,
  StudentVerificationStatusResponse,
  StudentVerificationSummaryResponse,
  ReviewItemDetail,
  InitiateIdCardReviewRequest,
  InitiateFullNameVerificationRequest,
  UpdateReviewItemRequest,
  VerifyFullNameRequest,
  LowAttendanceSectionsResponse,
  LowAttendanceStudentItem,
  SendMeetingInvitesRequest,
  SendMeetingInvitesResult,
  VendorDuesListResponse,
  MarkVendorPaidRequest,
  ReorderSignalsResponse,
} from '../types/dashboardCommandCenter';

const COMMAND_CENTER_CACHE_MS = 55_000;
let commandCenterCache: {
  authSessionVersion: number;
  loadedAt: number;
  data: DashboardCommandCenterResponse;
} | null = null;
let commandCenterRequest: {
  authSessionVersion: number;
  promise: Promise<DashboardCommandCenterResponse>;
} | null = null;

export async function fetchCommandCenterMetrics(): Promise<DashboardCommandCenterResponse> {
  const sessionVersion = getAuthSessionVersion();
  const cache = commandCenterCache;
  const cacheMatchesSession = cache?.authSessionVersion === sessionVersion;
  const tabIsHidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
  if (cache && cacheMatchesSession && (tabIsHidden || Date.now() - cache.loadedAt < COMMAND_CENTER_CACHE_MS)) {
    return cache.data;
  }
  if (commandCenterRequest?.authSessionVersion === sessionVersion) return commandCenterRequest.promise;

  const request = reportingClient.getDashboardCommandCenter()
    .then((data) => {
      if (getAuthSessionVersion() === sessionVersion) {
        commandCenterCache = { authSessionVersion: sessionVersion, loadedAt: Date.now(), data };
      }
      return data;
    })
    .finally(() => {
      if (commandCenterRequest?.promise === request) commandCenterRequest = null;
    });
  commandCenterRequest = { authSessionVersion: sessionVersion, promise: request };
  return request;
}

export async function fetchFeeDefaulters(params: {
  classId?: string;
  sectionId?: string;
  daysOverdue?: number;
  reminderStatus?: string;
  page?: number;
  size?: number;
}): Promise<FeeDefaulterListResponse> {
  return reportingClient.getFeeDefaulters(params);
}

export async function sendFeeReminders(request: SendFeeRemindersRequest): Promise<SendFeeRemindersResult> {
  const res = await api.post<SendFeeRemindersResult>('/fees/reminders/fee', request);
  return res.data;
}

export async function fetchClassPhotographyPaymentStatus(params: {
  classId?: string;
  sectionId?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<ClassPhotographyPaymentStatusResponse> {
  const res = await api.get<ClassPhotographyPaymentStatusResponse>(
    '/reporting/class-photography/payment-status',
    { params }
  );
  return res.data;
}

export async function sendPhotographyPaymentReminders(
  eventId: string,
  request: SendEventPaymentRemindersRequest
): Promise<SendEventPaymentRemindersResult> {
  const res = await api.post<SendEventPaymentRemindersResult>(
    `/dashboard/events/${eventId}/payment-reminders`,
    request
  );
  return res.data;
}

// ── Student Review Campaigns ──────────────────────────────────────────────────

export async function fetchIdCardReviewStatus(schoolId: number): Promise<IdCardReviewStatusResponse> {
  return studentClient.getIdCardReviewStatus({ schoolId });
}

export async function initiateIdCardReview(request: InitiateIdCardReviewRequest): Promise<IdCardReviewStatusResponse> {
  return studentClient.initiateIdCardReview(request);
}

export async function fetchFullNameVerificationStatus(schoolId: number): Promise<FullNameVerificationStatusResponse> {
  return studentClient.getFullNameReviewStatus({ schoolId });
}

export async function initiateFullNameVerification(request: InitiateFullNameVerificationRequest): Promise<FullNameVerificationStatusResponse> {
  return studentClient.initiateFullNameReview(request);
}

export async function fetchProfileVerificationStatus(schoolId: number): Promise<StudentVerificationStatusResponse> {
  return studentClient.getProfileReviewStatus({ schoolId });
}

export async function initiateProfileVerification(request: InitiateIdCardReviewRequest): Promise<StudentVerificationStatusResponse> {
  return studentClient.initiateProfileReview(request);
}

export async function fetchPhotoVerificationStatus(schoolId: number): Promise<StudentVerificationStatusResponse> {
  return studentClient.getPhotoReviewStatus({ schoolId });
}

export async function initiatePhotoVerification(request: InitiateIdCardReviewRequest): Promise<StudentVerificationStatusResponse> {
  return studentClient.initiatePhotoReview(request);
}

export async function fetchStudentVerificationSummary(studentId: number): Promise<StudentVerificationSummaryResponse> {
  return studentClient.getStudentVerification({ id: studentId });
}

export async function verifyStudentProfile(studentId: number): Promise<ReviewItemDetail> {
  return studentClient.verifyStudentProfile({ id: studentId });
}

export async function verifyStudentPhoto(studentId: number): Promise<ReviewItemDetail> {
  return studentClient.verifyStudentPhoto({ id: studentId });
}

export interface CampaignItemsParams {
  schoolId?: number;
  status?: string;
  classId?: string;
  sectionId?: string;
  page?: number;
  size?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  page: number;
  size: number;
}

export async function fetchCampaignItems(campaignId: string, params: CampaignItemsParams): Promise<PageResponse<ReviewItemDetail>> {
  return studentClient.getCampaignItems({ campaignId, ...params });
}

export async function updateReviewItem(itemId: string, request: UpdateReviewItemRequest): Promise<ReviewItemDetail> {
  return studentClient.updateReviewItem({ itemId }, request);
}

export async function verifyFullName(itemId: string, request: VerifyFullNameRequest): Promise<ReviewItemDetail> {
  return studentClient.verifyFullName({ itemId }, request);
}

export async function completeReviewCampaign(campaignId: string): Promise<void> {
  await studentClient.completeReviewCampaign({ campaignId });
}

// ── Low Attendance Meeting Invites ────────────────────────────────────────────

export async function fetchLowAttendanceSections(date?: string): Promise<LowAttendanceSectionsResponse> {
  return reportingClient.getLowAttendanceSections(date ? { date } : undefined);
}

export async function fetchLowAttendanceStudents(sectionId: string): Promise<LowAttendanceStudentItem[]> {
  return reportingClient.getLowAttendanceStudents({ sectionId });
}

export async function sendMeetingInvites(
  request: SendMeetingInvitesRequest
): Promise<SendMeetingInvitesResult> {
  const res = await api.post<SendMeetingInvitesResult>(
    '/dashboard/attendance/meeting-invites',
    request
  );
  return res.data;
}

// ── Vendor Payment Dues ───────────────────────────────────────────────────────

export async function fetchVendorDues(): Promise<VendorDuesListResponse> {
  return reportingClient.getVendorDues();
}

export async function markCatalogOrderVendorPaid(
  orderId: string,
  request?: MarkVendorPaidRequest
): Promise<void> {
  await api.post(`/catalog/orders/${encodeURIComponent(orderId)}/vendor-paid`, request ?? {});
}

export async function markFirefightingVendorPaid(
  code: string,
  request?: MarkVendorPaidRequest
): Promise<void> {
  await api.post(`/ff/requests/${encodeURIComponent(code)}/vendor-paid`, request ?? {});
}

// ── Reorder Prediction ────────────────────────────────────────────────────────

export async function fetchReorderSignals(): Promise<ReorderSignalsResponse> {
  return reportingClient.getReorderSignals();
}
