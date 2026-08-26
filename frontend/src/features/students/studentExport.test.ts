import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAccessToken, refreshToken } from '../../services/api';
import { downloadStudentExport, type StudentExportProgress } from './studentExport';

vi.mock('../../services/api', () => ({
  getAccessToken: vi.fn(),
  refreshToken: vi.fn(),
}));

interface ResponseOptions {
  status?: number;
  headers?: Record<string, string>;
  body?: Pick<ReadableStream<Uint8Array>, 'getReader'> | null;
  json?: () => Promise<unknown>;
  text?: () => Promise<string>;
  blob?: () => Promise<Blob>;
}

function response({
  status = 200,
  headers = {},
  body = null,
  json = async () => ({}),
  text = async () => '',
  blob = async () => new Blob(['archive']),
}: ResponseOptions = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: new Headers(headers),
    body,
    json,
    text,
    blob,
  } as Response;
}

function setSavePicker(value: unknown): void {
  Object.defineProperty(window, 'showSaveFilePicker', {
    configurable: true,
    writable: true,
    value,
  });
}

describe('downloadStudentExport', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getAccessToken).mockReturnValue('access-token');
    vi.mocked(refreshToken).mockResolvedValue(null);
    vi.stubGlobal('fetch', vi.fn());
    setSavePicker(undefined);
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:student-export'),
    });
    Object.defineProperty(window.URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  it('returns cancelled without contacting the server when the file picker is dismissed', async () => {
    setSavePicker(vi.fn().mockRejectedValue({ name: 'AbortError' }));
    const onProgress = vi.fn();

    await expect(downloadStudentExport(7, 'students.zip', onProgress)).resolves.toBe('cancelled');

    expect(fetch).not.toHaveBeenCalled();
    expect(onProgress).not.toHaveBeenCalled();
  });

  it('aborts the writable file when streaming to disk fails', async () => {
    const diskError = new Error('disk full');
    const writable = {
      write: vi.fn().mockRejectedValue(diskError),
      close: vi.fn(),
      abort: vi.fn().mockResolvedValue(undefined),
    };
    const reader = {
      read: vi.fn().mockResolvedValue({ done: false, value: new Uint8Array([1, 2, 3]) }),
    };
    setSavePicker(vi.fn().mockResolvedValue({
      createWritable: vi.fn().mockResolvedValue(writable),
    }));
    vi.mocked(fetch).mockResolvedValue(response({
      body: { getReader: () => reader } as unknown as Pick<ReadableStream<Uint8Array>, 'getReader'>,
    }));

    await expect(downloadStudentExport(7, 'students.zip')).rejects.toThrow('disk full');

    expect(writable.abort).toHaveBeenCalledWith(diskError);
    expect(writable.close).not.toHaveBeenCalled();
  });

  it('refreshes once after a 401 and retries with the new access token', async () => {
    vi.mocked(getAccessToken)
      .mockReturnValueOnce('expired-token')
      .mockReturnValueOnce('fresh-token');
    vi.mocked(refreshToken).mockResolvedValue({
      accessToken: 'fresh-token',
      userId: 1,
      fullName: 'Export Operator',
      email: 'operator@example.test',
      role: 'OPERATIONS',
    });
    vi.mocked(fetch)
      .mockResolvedValueOnce(response({ status: 401 }))
      .mockResolvedValueOnce(response());

    await expect(downloadStudentExport(7, 'students.zip')).resolves.toBe('saved');

    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(vi.mocked(fetch).mock.calls[0]?.[1]).toMatchObject({
      headers: { Authorization: 'Bearer expired-token' },
    });
    expect(vi.mocked(fetch).mock.calls[1]?.[1]).toMatchObject({
      headers: { Authorization: 'Bearer fresh-token' },
    });
  });

  it('surfaces a JSON error message from a rejected export', async () => {
    vi.mocked(fetch).mockResolvedValue(response({
      status: 422,
      headers: { 'content-type': 'application/json; charset=utf-8' },
      json: async () => ({ message: 'Export scope is no longer authorized.' }),
    }));

    await expect(downloadStudentExport(7, 'students.zip'))
      .rejects.toThrow('Export scope is no longer authorized.');
  });

  it('surfaces a text error from a failed export response', async () => {
    vi.mocked(fetch).mockResolvedValue(response({
      status: 503,
      headers: { 'content-type': 'text/plain' },
      text: async () => 'Archive storage is unavailable.',
    }));

    await expect(downloadStudentExport(7, 'students.zip'))
      .rejects.toThrow('Archive storage is unavailable.');
  });

  it('reports terminal FAILED server progress and stops polling', async () => {
    const updates: StudentExportProgress[] = [];
    let reportFailed!: () => void;
    const failedReported = new Promise<void>(resolve => { reportFailed = resolve; });
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes('/progress?')) {
        return response({
          json: async () => ({
            status: 'FAILED',
            percent: 38,
            phase: 'FAILED',
            processedStudents: 380,
            totalStudents: 1000,
            exportedPhotos: 350,
            missingPhotos: 30,
          }),
        });
      }
      return response({
        headers: { 'x-student-export-id': 'export-123' },
        blob: async () => {
          await failedReported;
          return new Blob(['partial-archive']);
        },
      });
    });

    const result = await downloadStudentExport(7, 'students.zip', progress => {
      updates.push(progress);
      if (progress.serverPhase === 'FAILED') reportFailed();
    });

    expect(result).toBe('saved');
    expect(updates).toContainEqual(expect.objectContaining({
      phase: 'preparing',
      percent: 38,
      serverPhase: 'FAILED',
      processedStudents: 380,
      totalStudents: 1000,
      exportedPhotos: 350,
      missingPhotos: 30,
    }));
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
