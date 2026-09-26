import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createFirefightingClient } from './firefightingClient';
import { createCatalogClient } from './catalogClient';
import { createReportingClient } from './reportingClient';
import { createFeeClient } from './feeClient';
import { createStudentClient } from './studentClient';
import { createAttendanceClient } from './attendanceClient';
import { createBillingClient } from './billingClient';
import { createQuotationDocumentClient } from './quotationDocumentClient';
import { createBroadcastClient } from './broadcastClient';

function transport() {
  const get = vi.fn(); const post = vi.fn(); const put = vi.fn(); const patch = vi.fn(); const deleteRequest = vi.fn();
  return { get, post, put, patch, deleteRequest, http: { get, post, put, patch, delete: deleteRequest } as unknown as AxiosInstance };
}

describe('critical generated browser contracts', () => {
  it('preserves exact payment retry identity, assignment year, and school scope', async () => {
    const { http, post } = transport();
    const client = createFeeClient(http);
    const request = { studentId: 7, schoolId: 4, assignmentId: 'historical', academicYearId: '2024-25', amount: 2500, idempotencyKey: 'original-key', paidAt: '2026-09-26T10:00:00Z' };
    const receipt = { paymentId: 'payment-1', receiptNumber: 'RCPT-V2-1', academicYearId: '2024-25' };
    post.mockRejectedValueOnce(new Error('Response lost')).mockResolvedValueOnce({ data: receipt });
    await expect(client.recordPayment(request)).rejects.toThrow('Response lost');
    await expect(client.recordPayment(request)).resolves.toEqual(receipt);
    expect(post.mock.calls).toEqual([['/fees/payments', request], ['/fees/payments', request]]);
  });

  it('keeps canonical report envelopes and forwards explicit filter parameters', async () => {
    const { http, get } = transport();
    const report = { content: [{ studentId: 7, assignmentId: 'old' }] };
    get.mockResolvedValue({ data: report });
    const params = { classId: 'c1', sectionId: 's1', academicYearId: '2024-25', schoolId: 4 };
    await expect(createFeeClient(http).getCollectionReport(params)).resolves.toBe(report);
    expect(get).toHaveBeenCalledWith('/fees/reports/collection', { params });
  });

  it('separates admission from photo upload and never overwrites multipart boundaries', async () => {
    const { http, post } = transport();
    const client = createStudentClient(http);
    post.mockResolvedValueOnce({ data: { id: 42 } }).mockRejectedValueOnce(new Error('Photo upload failed'));
    await expect(client.createStudent({ fullName: 'Student', admissionNumber: 'ADM-42', schoolId: 4, address: 'Village road' })).resolves.toEqual({ id: 42 });
    const file = new File(['photo'], 'photo.png', { type: 'image/png' });
    await expect(client.uploadStudentPhoto({ id: 42 }, { file })).rejects.toThrow('Photo upload failed');
    expect(post.mock.calls[0][0]).toBe('/students');
    expect(post.mock.calls[1][0]).toBe('/students/42/photo');
    expect(post.mock.calls[1][1].get('file')).toBe(file);
    expect(post.mock.calls[1]).toHaveLength(2);
  });

  it('keeps the attendance save and submit operations distinct and forwards every mark', async () => {
    const { http, post, put } = transport();
    const client = createAttendanceClient(http);
    const request = { classId: 'c1', sectionId: 's1', date: '2026-09-26', schoolId: 4, records: [{ studentId: 7, status: 'LATE' as const, remarks: 'Bus delay' }] };
    put.mockResolvedValue({ data: { locked: false } });
    post.mockResolvedValue({ data: { locked: true } });
    await expect(client.saveSectionRegister(request)).resolves.toEqual({ locked: false });
    expect(post).not.toHaveBeenCalled();
    await client.submitSection({ classId: 'c1', sectionId: 's1', date: request.date, schoolId: 4 });
    expect(put).toHaveBeenCalledWith('/attendance/section-register', request);
    expect(post.mock.calls[0][0]).toBe('/attendance/submit-section');
  });

  it('reads server-wide billing totals through the canonical statistics endpoint', async () => {
    const { http, get } = transport();
    const stats = { sentThisMonth: 101, paid: 120, pending: 130, totalInvoiced: 500000, periodStart: '2026-09-01', periodEndExclusive: '2026-10-01', reportingTimeZone: 'Asia/Kolkata' };
    get.mockResolvedValue({ data: stats });
    await expect(createBillingClient(http).getInvoiceStatistics()).resolves.toEqual(stats);
    expect(get).toHaveBeenCalledWith('/billing/sa/invoices/stats');
  });

  it('encodes quotation IDs, transfers authenticated binary content, and handles 204 deletion', async () => {
    const { http, get, post, deleteRequest } = transport();
    const client = createQuotationDocumentClient(http);
    const params = { code: 'FF / 42', quotationId: 'q#1' };
    const pdf = new File(['pdf'], 'quote.pdf', { type: 'application/pdf' });
    post.mockResolvedValue({ data: { id: 'doc-1', filename: pdf.name } });
    get.mockResolvedValue({ data: pdf });
    deleteRequest.mockResolvedValue({ status: 204 });
    await client.uploadDocument(params, { file: pdf });
    await expect(client.downloadDocument(params)).resolves.toBe(pdf);
    await expect(client.removeDocument(params)).resolves.toBeUndefined();
    const url = '/ff/requests/FF%20%2F%2042/quotations/q%231/document';
    expect(post.mock.calls[0][0]).toBe(url);
    expect(post.mock.calls[0][1].get('file')).toBe(pdf);
    expect(get).toHaveBeenCalledWith(url, { responseType: 'blob' });
    expect(deleteRequest).toHaveBeenCalledWith(url);
  });

  it('approves the reviewed broadcast fingerprint and exposes dry-run queue outcomes separately', async () => {
    const { http, post } = transport();
    const client = createBroadcastClient(http);
    post.mockResolvedValueOnce({ data: { fingerprint: 'reviewed-hash', eligible: 8 } })
      .mockResolvedValueOnce({ data: { status: 'APPROVED' } })
      .mockResolvedValueOnce({ data: { mode: 'DRY_RUN', delivered: 0 } });
    const preview = await client.previewBroadcast({ id: 'draft-1' });
    await client.approveBroadcast({ id: 'draft-1' }, { previewFingerprint: preview.fingerprint });
    await expect(client.queueBroadcast({ id: 'draft-1' })).resolves.toEqual({ mode: 'DRY_RUN', delivered: 0 });
    expect(post.mock.calls).toEqual([
      ['/notifications/broadcasts/draft-1/preview'],
      ['/notifications/broadcasts/draft-1/approve', { previewFingerprint: 'reviewed-hash' }],
      ['/notifications/broadcasts/draft-1/send'],
    ]);
  });
  it('preserves order pagination, filters, and the distinct guarded lifecycle routes', async () => {
    const { http, get, patch, post } = transport();
    const client = createCatalogClient(http);
    const page = { content: [{ id: 'last' }], page: 2, size: 7, totalElements: 19, totalPages: 3 };
    get.mockResolvedValue({ data: page });
    await expect(client.getOrderPage({ schoolId: 10, status: 'APPROVED', page: 2, size: 7 })).resolves.toBe(page);
    expect(get).toHaveBeenCalledWith('/catalog/orders/page', { params: { schoolId: 10, status: 'APPROVED', page: 2, size: 7 } });
    patch.mockResolvedValue({ data: {} }); post.mockResolvedValue({ data: {} });
    await client.updateOrderStatus({ id: 'o/1' }, { status: 'APPROVED' });
    await client.markDelivered({ id: 'o/1' });
    expect(patch).toHaveBeenCalledWith('/catalog/orders/o%2F1/status', { status: 'APPROVED' });
    expect(post).toHaveBeenCalledWith('/catalog/orders/o%2F1/deliver');
  });

  it('keeps roster IDs and school filters separate from the workspace grid and preserves the workspace envelope', async () => {
    const { http, get, put } = transport();
    get.mockResolvedValueOnce({ data: [{ id: 7, fullName: 'Student' }] }).mockResolvedValueOnce({ data: { school: { name: 'School' }, dashboard: {}, fees: {} } });
    const student = createStudentClient(http);
    await expect(student.getRoster({ classId: 'c1', sectionId: 's1', schoolId: 10 })).resolves.toEqual([{ id: 7, fullName: 'Student' }]);
    expect(get.mock.calls[0]).toEqual(['/students/roster', { params: { classId: 'c1', sectionId: 's1', schoolId: 10, limit: undefined } }]);
    await expect(createReportingClient(http).getWorkspace({ schoolId: 10 })).resolves.toHaveProperty('school.name', 'School');
    put.mockResolvedValue({ data: { id: 7 } });
    const profile = { fullName: 'Student', admissionNumber: 'A7', classId: 'c1', sectionId: 's1', phone: '9999999999' };
    await student.updateStudent({ id: 7 }, profile);
    expect(put).toHaveBeenCalledWith('/students/7', profile);
  });

  it('replays firefighting creation with the original keys and payload after response loss', async () => {
    const { http, post } = transport();
    const client = createFirefightingClient(http);
    const request = { title: 'Repair lights', schoolId: 10, idempotencyKey: 'request-original' };
    post.mockRejectedValueOnce(new Error('Response lost')).mockResolvedValueOnce({ data: { code: 'FF-7' } });
    await expect(client.createRequest(request)).rejects.toThrow('Response lost');
    await client.createRequest(request);
    expect(post.mock.calls.slice(0, 2)).toEqual([['/ff/requests', request], ['/ff/requests', request]]);
    const quote = { vendorName: 'Vendor', amount: 2000, idempotencyKey: 'quote-original' };
    post.mockRejectedValueOnce(new Error('Response lost')).mockResolvedValueOnce({ data: { id: 'q1' } });
    await expect(client.createQuotation({ code: 'FF / 7' }, quote)).rejects.toThrow('Response lost');
    await client.createQuotation({ code: 'FF / 7' }, quote);
    expect(post.mock.calls.slice(2)).toEqual([['/ff/requests/FF%20%2F%207/quotations', quote], ['/ff/requests/FF%20%2F%207/quotations', quote]]);
  });

  it('confirms only the reviewed annual-plan fingerprint and does not claim a notification', async () => {
    const { http, get, post } = transport();
    const client = createCatalogClient(http);
    get.mockResolvedValue({ data: { fingerprint: 'reviewed', items: [{ id: 'plan-1' }] } });
    const review = await client.reviewAnnualPlan({ schoolId: 10 });
    post.mockResolvedValue({ data: { confirmed: true, notificationStatus: 'NOT_SENT', revision: 1 } });
    await expect(client.confirmAnnualPlan({ schoolId: 10 }, { fingerprint: review.fingerprint })).resolves.toEqual({ confirmed: true, notificationStatus: 'NOT_SENT', revision: 1 });
    expect(post).toHaveBeenCalledWith('/catalog/annual-plan/confirm', { fingerprint: 'reviewed' }, { params: { schoolId: 10 } });
  });

});
