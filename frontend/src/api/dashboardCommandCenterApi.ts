import api from '../services/api';
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

export async function fetchCommandCenterMetrics(): Promise<DashboardCommandCenterResponse> {
  const res = await api.get<DashboardCommandCenterResponse>('/dashboard/command-center');
  return res.data;
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
    '/dashboard/student-lifecycle/id-card-review/status'
  );
  return res.data;
}

export async function initiateIdCardReview(
  request: InitiateIdCardReviewRequest
): Promise<IdCardReviewStatusResponse> {
  const res = await api.post<IdCardReviewStatusResponse>(
    '/dashboard/student-lifecycle/id-card-review/initiate',
    request
  );
  return res.data;
}

export async function fetchFullNameVerificationStatus(): Promise<FullNameVerificationStatusResponse> {
  const res = await api.get<FullNameVerificationStatusResponse>(
    '/dashboard/student-lifecycle/full-name-verification/status'
  );
  return res.data;
}

export async function initiateFullNameVerification(
  request: InitiateFullNameVerificationRequest
): Promise<FullNameVerificationStatusResponse> {
  const res = await api.post<FullNameVerificationStatusResponse>(
    '/dashboard/student-lifecycle/full-name-verification/initiate',
    request
  );
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
