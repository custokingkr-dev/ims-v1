import api, { getAuthSessionVersion } from '../services/api';
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

  const request = api.get<DashboardCommandCenterResponse>('/dashboard/command-center')
    .then((res) => {
      if (getAuthSessionVersion() === sessionVersion) {
        commandCenterCache = { authSessionVersion: sessionVersion, loadedAt: Date.now(), data: res.data };
      }
      return res.data;
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
  const res = await api.get<FeeDefaulterListResponse>('/dashboard/finance/fee-defaulters', { params });
  return res.data;
}

export async function sendFeeReminders(request: SendFeeRemindersRequest): Promise<SendFeeRemindersResult> {
  const res = await api.post<SendFeeRemindersResult>('/dashboard/finance/fee-defaulters/reminders', request);
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
    '/dashboard/events/class-photography/payment-status',
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

export async function fetchIdCardReviewStatus(): Promise<IdCardReviewStatusResponse> {
  const res = await api.get<IdCardReviewStatusResponse>(
    '/students/reviews/id-card/status'
  );
  return res.data;
}

export async function initiateIdCardReview(
  request: InitiateIdCardReviewRequest
): Promise<IdCardReviewStatusResponse> {
  const res = await api.post<IdCardReviewStatusResponse>(
    '/students/reviews/id-card/initiate',
    request
  );
  return res.data;
}

export async function fetchFullNameVerificationStatus(): Promise<FullNameVerificationStatusResponse> {
  const res = await api.get<FullNameVerificationStatusResponse>(
    '/students/reviews/full-name/status'
  );
  return res.data;
}

export async function initiateFullNameVerification(
  request: InitiateFullNameVerificationRequest
): Promise<FullNameVerificationStatusResponse> {
  const res = await api.post<FullNameVerificationStatusResponse>(
    '/students/reviews/full-name/initiate',
    request
  );
  return res.data;
}

export async function fetchProfileVerificationStatus(schoolId: number): Promise<StudentVerificationStatusResponse> {
  const res = await api.get<StudentVerificationStatusResponse>(
    '/students/reviews/profile/status',
    { params: { schoolId } }
  );
  return res.data;
}

export async function initiateProfileVerification(
  request: InitiateIdCardReviewRequest
): Promise<StudentVerificationStatusResponse> {
  const res = await api.post<StudentVerificationStatusResponse>(
    '/students/reviews/profile/initiate',
    request
  );
  return res.data;
}

export async function fetchPhotoVerificationStatus(schoolId: number): Promise<StudentVerificationStatusResponse> {
  const res = await api.get<StudentVerificationStatusResponse>(
    '/students/reviews/photo/status',
    { params: { schoolId } }
  );
  return res.data;
}

export async function initiatePhotoVerification(
  request: InitiateIdCardReviewRequest
): Promise<StudentVerificationStatusResponse> {
  const res = await api.post<StudentVerificationStatusResponse>(
    '/students/reviews/photo/initiate',
    request
  );
  return res.data;
}

export async function fetchStudentVerificationSummary(studentId: number): Promise<StudentVerificationSummaryResponse> {
  const res = await api.get<StudentVerificationSummaryResponse>(`/students/${studentId}/verification`);
  return res.data;
}

export async function verifyStudentProfile(studentId: number): Promise<ReviewItemDetail> {
  const res = await api.post<ReviewItemDetail>(`/students/${studentId}/verification/profile/verify`, {});
  return res.data;
}

export async function verifyStudentPhoto(studentId: number): Promise<ReviewItemDetail> {
  const res = await api.post<ReviewItemDetail>(`/students/${studentId}/verification/photo/verify`, {});
  return res.data;
}

export interface CampaignItemsParams {
  status?: string;
  classId?: string;
  sectionId?: string;
  page?: number;
  size?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export async function fetchCampaignItems(
  campaignId: string,
  params: CampaignItemsParams
): Promise<PageResponse<ReviewItemDetail>> {
  const res = await api.get<PageResponse<ReviewItemDetail>>(
    `/students/review-campaigns/${campaignId}/items`,
    { params }
  );
  return res.data;
}

export async function updateReviewItem(
  itemId: string,
  request: UpdateReviewItemRequest
): Promise<ReviewItemDetail> {
  const res = await api.put<ReviewItemDetail>(`/student-review-items/${itemId}`, request);
  return res.data;
}

export async function verifyFullName(
  itemId: string,
  request: VerifyFullNameRequest
): Promise<ReviewItemDetail> {
  const res = await api.put<ReviewItemDetail>(
    `/student-review-items/${itemId}/full-name-verification`,
    request
  );
  return res.data;
}

export async function completeReviewCampaign(campaignId: string): Promise<void> {
  await api.post(`/students/review-campaigns/${campaignId}/complete`, {});
}

// ── Low Attendance Meeting Invites ────────────────────────────────────────────

export async function fetchLowAttendanceSections(date?: string): Promise<LowAttendanceSectionsResponse> {
  const res = await api.get<LowAttendanceSectionsResponse>(
    '/dashboard/attendance/low-sections',
    { params: date ? { date } : undefined }
  );
  return res.data;
}

export async function fetchLowAttendanceStudents(sectionId: string): Promise<LowAttendanceStudentItem[]> {
  const res = await api.get<LowAttendanceStudentItem[]>(
    `/dashboard/attendance/sections/${sectionId}/low-students`
  );
  return res.data;
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
  const res = await api.get<VendorDuesListResponse>('/dashboard/vendor-dues');
  return res.data;
}

export async function markCatalogOrderVendorPaid(
  orderId: string,
  request?: MarkVendorPaidRequest
): Promise<void> {
  await api.post(`/dashboard/vendor-dues/catalog-orders/${orderId}/mark-paid`, request ?? {});
}

export async function markFirefightingVendorPaid(
  code: string,
  request?: MarkVendorPaidRequest
): Promise<void> {
  await api.post(`/dashboard/vendor-dues/firefighting/${code}/mark-paid`, request ?? {});
}

// ── Reorder Prediction ────────────────────────────────────────────────────────

export async function fetchReorderSignals(): Promise<ReorderSignalsResponse> {
  const res = await api.get<ReorderSignalsResponse>('/dashboard/reorder-signals');
  return res.data;
}
