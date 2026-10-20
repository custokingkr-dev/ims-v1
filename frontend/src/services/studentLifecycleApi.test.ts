import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createStudentClient } from '../generated/studentClient';

function transport() {
  const http = { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() };
  return { http, client: createStudentClient(http as unknown as AxiosInstance) };
}

describe('student lifecycle transport', () => {
  it('preserves directory filters without concatenating them into the URL', async () => {
    const { http, client } = transport();
    const data = { items: [], filters: { classes: [], sections: [], feeStatuses: [] } };
    http.get.mockResolvedValue({ data });
    expect(await client.getStudentDirectory({ schoolId: 7, class: 'Class 1', section: 'A', q: 'Name&schoolId=99', page: 2, size: 37 })).toBe(data);
    expect(http.get).toHaveBeenCalledWith('/students', { params: {
      schoolId: 7, class: 'Class 1', section: 'A', feeStatus: undefined, q: 'Name&schoolId=99', search: undefined, page: 2, size: 37,
    } });
  });

  it('returns distinct rich detail and history envelopes without rebuilding nulls or dates', async () => {
    const { http, client } = transport();
    const detail = { id: 7, admissionDate: null, fee: { assigned: false, status: 'Pending' } };
    const history = { student: { id: 7 }, enrollments: [{ effectiveTo: null }], feePayments: [{ paidAt: '2026-10-12T00:00:00Z' }] };
    http.get.mockResolvedValueOnce({ data: detail }).mockResolvedValueOnce({ data: history });
    expect(await client.getStudentWorkspace({ id: 7 })).toBe(detail);
    expect(await client.getStudentHistory({ id: 7 })).toBe(history);
    expect(http.get.mock.calls.map(call => call[0])).toEqual(['/students/7/workspace', '/students/7/history']);
  });

  it('keeps import evidence scope server-owned and passes only declared filters', async () => {
    const { http, client } = transport();
    const rows = [{ id: 'row1', rawJson: null, normalizedJson: null }];
    http.get.mockResolvedValue({ data: rows });
    expect(await client.getImportEvidenceRows({ batchId: 'b/%', status: 'Valid', limit: 37 })).toBe(rows);
    expect(http.get).toHaveBeenCalledWith('/students/imports/rows', { params: { batchId: 'b/%', status: 'Valid', limit: 37 } });
  });

  it('rejects a denied history read without returning an empty success or retrying', async () => {
    const { http, client } = transport();
    const denial = { response: { status: 403 } };
    http.get.mockRejectedValue(denial);
    await expect(client.getStudentHistory({ id: 7 })).rejects.toBe(denial);
    expect(http.get).toHaveBeenCalledExactlyOnceWith('/students/7/history');
  });
  it('encodes admission confirmation once without putting it in the URL or body', async () => {
    const { http, client } = transport();
    http.delete.mockResolvedValue({ data: { id: 7, deleted: true, permanent: true } });
    const confirmation = 'A+/% \u0928\u093e\u092e';
    await client.deleteStudent({ id: 7, confirmation });
    const [url, config] = http.delete.mock.calls[0];
    expect(url).toBe('/students/7');
    expect(config).toEqual({ headers: { 'X-Student-Delete-Confirmation': encodeURIComponent(confirmation) } });
    expect(decodeURIComponent(config.headers['X-Student-Delete-Confirmation'])).toBe(confirmation);
    expect(http.delete).toHaveBeenCalledTimes(1);
  });

  it('sends file and row JSON together with school scope and lets the browser set the boundary', async () => {
    const { http, client } = transport();
    const preview = { fileToken: 'original-token', jobId: 'job-1' };
    http.post.mockResolvedValue({ data: preview });
    const file = new File(['Name\nStudent'], 'students.csv', { type: 'text/csv' });
    const rowsJson = JSON.stringify([{ Name: 'Student', AdmissionNo: 'A1' }]);
    expect(await client.previewImportFile({ schoolId: 10 }, { file, rowsJson })).toBe(preview);
    const [url, form, config] = http.post.mock.calls[0];
    expect(url).toBe('/students/import/upload-preview');
    expect(form.get('file').name).toBe('students.csv');
    expect(form.get('rowsJson')).toBe(rowsJson);
    expect(config).toEqual({ params: { schoolId: 10 } });
  });

  it('retains the same preview token and rejects an uncertain confirmation without retry', async () => {
    const { http, client } = transport();
    const request = { fileToken: 'original-token', schoolId: 10 };
    const failure = new Error('Connection lost after submission');
    http.post.mockRejectedValue(failure);
    await expect(client.confirmImport(request)).rejects.toBe(failure);
    expect(http.post).toHaveBeenCalledExactlyOnceWith('/students/imports/confirm', request);
    expect(http.post.mock.calls[0][1]).toBe(request);
  });

  it('preserves nullable review values and the actual page envelope', async () => {
    const { http, client } = transport();
    const page = { content: [{ correctionNotes: null, completedAt: null, photoUrl: null }], page: 2, size: 17, totalElements: 40 };
    http.get.mockResolvedValue({ data: page });
    expect(await client.getCampaignItems({ campaignId: 'campaign/% +', schoolId: 10, page: 2, size: 17 })).toBe(page);
    expect(http.get).toHaveBeenCalledWith('/students/review-campaigns/campaign%2F%25%20%2B/items', {
      params: { schoolId: 10, status: undefined, classId: undefined, sectionId: undefined, page: 2, size: 17 },
    });
  });

  it('passes the required review school and makes no inferred global request', async () => {
    const { http, client } = transport();
    http.get.mockResolvedValue({ data: { campaignId: null } });
    await client.getIdCardReviewStatus({ schoolId: 10 });
    await client.getFullNameReviewStatus({ schoolId: 10 });
    expect(http.get.mock.calls.map(call => call[1])).toEqual([{ params: { schoolId: 10 } }, { params: { schoolId: 10 } }]);
  });

  it('encodes promotion identifiers once and keeps apply separate from draft edits', async () => {
    const { http, client } = transport();
    http.patch.mockResolvedValue({ data: { id: 'batch/%' } });
    http.post.mockResolvedValue({ data: { promoted: 1 } });
    const request = { action: 'HOLD' as const, reason: 'Review first' };
    await client.updatePromotionItem({ batchId: 'batch/%', itemId: 'item/+' }, request);
    expect(http.patch).toHaveBeenCalledWith('/students/promotion-batches/batch%2F%25/items/item%2F%2B', request);
    expect(http.post).not.toHaveBeenCalled();
    await client.applyPromotionBatch({ batchId: 'batch/%' });
    expect(http.post).toHaveBeenCalledExactlyOnceWith('/students/promotion-batches/batch%2F%25/apply');
  });

  it.each([403, 409, 500])('does not replay review or erasure mutations after %s', async (status) => {
    const { http, client } = transport();
    const failure = { response: { status } };
    http.post.mockRejectedValue(failure);
    http.delete.mockRejectedValue(failure);
    await expect(client.updateReviewItem({ itemId: 'item-1' }, { status: 'COMPLETED' })).rejects.toBe(failure);
    await expect(client.deleteStudent({ id: 7, confirmation: 'A7' })).rejects.toBe(failure);
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.delete).toHaveBeenCalledTimes(1);
  });
});
