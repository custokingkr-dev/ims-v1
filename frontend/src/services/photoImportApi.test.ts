import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { photoImportClient } from './photoImportApi';

vi.mock('./api', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

describe('canonical photo import transport', () => {
  beforeEach(() => vi.clearAllMocks());

  it('retains school filters, bounded timeouts and the caller cancellation signal', async () => {
    const batches = [{ id: 'batch', schoolId: 7 }];
    vi.mocked(api.get).mockResolvedValue({ data: batches });
    const signal = new AbortController().signal;
    expect(await photoImportClient.listBatches({ schoolId: 7 }, { timeout: 120000, signal })).toBe(batches);
    expect(api.get).toHaveBeenCalledWith('/student-photo-imports', expect.objectContaining({ params: { schoolId: 7 }, timeout: 120000, signal }));
  });

  it('encodes identifiers once and returns preview/CSV Blobs with download progress', async () => {
    const data = new Blob(['csv or jpeg']);
    vi.mocked(api.get).mockResolvedValue({ data });
    const onDownloadProgress = vi.fn();
    // Runtime callers cannot override URL, auth headers, filters or binary response handling.
    const options = { onDownloadProgress, headers: { 'X-Authenticated-Role': 'SUPERADMIN' }, baseURL: 'https://untrusted.invalid', responseType: 'json', params: { schoolId: 99 } };
    expect(await photoImportClient.getPreview({ batchId: 'batch/a', rowId: 'row?x' }, options)).toBe(data);
    expect(api.get).toHaveBeenLastCalledWith('/student-photo-imports/batch%2Fa/rows/row%3Fx/preview', { signal: undefined, timeout: undefined, onDownloadProgress, responseType: 'blob' });
    expect(await photoImportClient.downloadResult({ id: 'batch/a' }, options)).toBe(data);
    expect(api.get).toHaveBeenLastCalledWith('/student-photo-imports/batch%2Fa/result', { signal: undefined, timeout: undefined, onDownloadProgress, responseType: 'blob' });
  });

  it('preserves explicit create/review/recovery bodies and bodyless commands', async () => {
    const data = { id: 'batch', schoolId: 7 };
    vi.mocked(api.post).mockResolvedValue({ data });
    const request = { schoolId: 7, academicYearId: 'ay', driveFolderUrl: null };
    expect(await photoImportClient.createBatch(request)).toBe(data);
    expect(api.post).toHaveBeenLastCalledWith('/student-photo-imports', request, expect.any(Object));
    const review = { admissionNo: 'ADM-1', imageNo: null, excluded: false, cropX: null, cropY: 0.5 };
    await photoImportClient.reviewRow({ batchId: 'batch', rowId: 'row' }, review);
    expect(api.post).toHaveBeenLastCalledWith('/student-photo-imports/batch/rows/row', review, expect.any(Object));
    await photoImportClient.recoverPhotos({ id: 'batch' }, { rowIds: ['row-1', 'row-2'] });
    expect(api.post).toHaveBeenLastCalledWith('/student-photo-imports/batch/recover', { rowIds: ['row-1', 'row-2'] }, expect.any(Object));
    for (const command of ['scanBatch', 'freezeBatch', 'cancelBatch', 'executeBatch'] as const) expect(await photoImportClient[command]({ id: 'batch' })).toBe(data);
    expect(api.post).toHaveBeenLastCalledWith('/student-photo-imports/batch/execute', undefined, expect.any(Object));
  });

  it('does not retry denied or uncertain writes or turn them into empty success', async () => {
    const denied = { response: { status: 403 } };
    vi.mocked(api.post).mockRejectedValueOnce(denied);
    await expect(photoImportClient.executeBatch({ id: 'batch' })).rejects.toBe(denied);
    const uncertain = { code: 'ECONNABORTED' };
    vi.mocked(api.post).mockRejectedValueOnce(uncertain);
    await expect(photoImportClient.recoverPhotos({ id: 'batch' }, { rowIds: ['row'] })).rejects.toBe(uncertain);
    expect(api.post).toHaveBeenCalledTimes(2);
    expect(api.get).not.toHaveBeenCalled();
  });
});
