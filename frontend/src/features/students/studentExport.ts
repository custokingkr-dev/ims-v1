import { getAccessToken, refreshToken } from '../../services/api';

interface SaveFilePickerOptions {
  suggestedName?: string;
  types?: Array<{ description?: string; accept: Record<string, string[]> }>;
}

interface FileSystemWritable {
  write(data: BufferSource | Blob | string): Promise<void>;
  close(): Promise<void>;
  abort?(reason?: unknown): Promise<void>;
}

interface FileHandle {
  createWritable(): Promise<FileSystemWritable>;
}

type WindowWithSavePicker = Window & {
  showSaveFilePicker?: (options?: SaveFilePickerOptions) => Promise<FileHandle>;
};

export interface StudentExportProgress {
  phase: 'preparing' | 'downloading' | 'saving';
  loadedBytes: number;
  totalBytes?: number;
  /** Exact bounded server work completed (photo rows + workbook/finalization), 0-100. */
  percent?: number;
  serverPhase?: string;
  processedStudents?: number;
  totalStudents?: number;
  exportedPhotos?: number;
  missingPhotos?: number;
}

export type StudentExportProgressHandler = (progress: StudentExportProgress) => void;

function exportUrl(schoolId: number): string {
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
  return new URL(`${base}/students/export/archive?schoolId=${encodeURIComponent(schoolId)}`, window.location.origin).toString();
}

function exportProgressUrl(exportId: string, schoolId: number): string {
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
  return new URL(
    `${base}/students/export/${encodeURIComponent(exportId)}/progress?schoolId=${encodeURIComponent(schoolId)}`,
    window.location.origin,
  ).toString();
}

async function authenticatedFetch(url: string, signal?: AbortSignal): Promise<Response> {
  const request = () => {
    const token = getAccessToken();
    return fetch(url, {
      method: 'GET',
      credentials: 'include',
      signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
  };
  let response = await request();
  if (response.status === 401) {
    const refreshed = await refreshToken();
    if (refreshed) response = await request();
  }
  return response;
}

async function responseError(response: Response): Promise<Error> {
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    const data = await response.json().catch(() => null) as { message?: string; detail?: string } | null;
    return new Error(data?.message || data?.detail || `Export failed (${response.status}).`);
  }
  const body = await response.text().catch(() => '');
  return new Error(body.trim() || `Export failed (${response.status}).`);
}

interface ServerExportProgress {
  status: 'STARTED' | 'COMPLETED' | 'FAILED';
  percent: number;
  phase: string;
  processedStudents: number;
  totalStudents: number;
  exportedPhotos: number;
  missingPhotos: number;
}

async function pollExportProgress(
  exportId: string,
  schoolId: number,
  onProgress: (progress: ServerExportProgress) => void,
  shouldStop: () => boolean,
  signal: AbortSignal,
): Promise<void> {
  while (!shouldStop()) {
    const response = await authenticatedFetch(exportProgressUrl(exportId, schoolId), signal);
    if (response.ok) {
      const progress = await response.json() as ServerExportProgress;
      onProgress(progress);
      if (progress.status === 'COMPLETED' || progress.status === 'FAILED') return;
    }
    if (!shouldStop()) await new Promise(resolve => window.setTimeout(resolve, 500));
  }
}

export async function downloadStudentExport(
  schoolId: number,
  suggestedName: string,
  onProgress?: StudentExportProgressHandler,
): Promise<'saved' | 'cancelled'> {
  const picker = (window as WindowWithSavePicker).showSaveFilePicker;
  let handle: FileHandle | null = null;
  if (picker) {
    try {
      handle = await picker({
        suggestedName,
        types: [{ description: 'ZIP archive', accept: { 'application/zip': ['.zip'] } }],
      });
    } catch (error: any) {
      if (error?.name === 'AbortError') return 'cancelled';
      throw error;
    }
  }

  onProgress?.({ phase: 'preparing', loadedBytes: 0, percent: 0, serverPhase: 'PREPARING' });
  const response = await authenticatedFetch(exportUrl(schoolId));
  if (!response.ok) throw await responseError(response);

  const contentLength = Number(response.headers.get('content-length'));
  const totalBytes = Number.isFinite(contentLength) && contentLength > 0 ? contentLength : undefined;
  let loadedBytes = 0;
  let latestServer: ServerExportProgress | undefined;
  let stopPolling = false;
  const pollController = new AbortController();
  const exportId = response.headers.get('x-student-export-id');
  const polling = exportId && onProgress
    ? pollExportProgress(exportId, schoolId, progress => {
      latestServer = progress;
      onProgress({
        phase: progress.status === 'COMPLETED' ? 'downloading' : 'preparing',
        loadedBytes,
        totalBytes,
        percent: progress.percent,
        serverPhase: progress.phase,
        processedStudents: progress.processedStudents,
        totalStudents: progress.totalStudents,
        exportedPhotos: progress.exportedPhotos,
        missingPhotos: progress.missingPhotos,
      });
    }, () => stopPolling, pollController.signal).catch(() => undefined)
    : Promise.resolve();
  onProgress?.({ phase: 'downloading', loadedBytes: 0, totalBytes, percent: 0, serverPhase: 'PREPARING' });

  try {
    if (handle && response.body) {
      const writable = await handle.createWritable();
      try {
        const reader = response.body.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          await writable.write(value);
          loadedBytes += value.byteLength;
          onProgress?.({
            phase: 'downloading', loadedBytes, totalBytes,
            percent: latestServer?.percent, serverPhase: latestServer?.phase,
            processedStudents: latestServer?.processedStudents, totalStudents: latestServer?.totalStudents,
            exportedPhotos: latestServer?.exportedPhotos, missingPhotos: latestServer?.missingPhotos,
          });
        }
        onProgress?.({
          phase: 'saving', loadedBytes, totalBytes, percent: latestServer?.percent,
          serverPhase: latestServer?.phase,
          processedStudents: latestServer?.processedStudents, totalStudents: latestServer?.totalStudents,
          exportedPhotos: latestServer?.exportedPhotos, missingPhotos: latestServer?.missingPhotos,
        });
        await writable.close();
        onProgress?.({
          phase: 'saving', loadedBytes, totalBytes, percent: 100, serverPhase: 'COMPLETED',
          processedStudents: latestServer?.totalStudents, totalStudents: latestServer?.totalStudents,
          exportedPhotos: latestServer?.exportedPhotos, missingPhotos: latestServer?.missingPhotos,
        });
        return 'saved';
      } catch (error) {
        await writable.abort?.(error).catch(() => undefined);
        throw error;
      }
    }

    // Firefox/Safari fallback. Chromium/Edge use the direct-to-disk path above, which is strongly
    // preferred for schools with thousands of portraits because it does not retain the ZIP in RAM.
    onProgress?.({ phase: 'saving', loadedBytes: 0, totalBytes, percent: latestServer?.percent, serverPhase: latestServer?.phase });
    const blob = await response.blob();
    loadedBytes = blob.size;
    const objectUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = suggestedName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(objectUrl);
    onProgress?.({ phase: 'saving', loadedBytes, totalBytes, percent: 100, serverPhase: 'COMPLETED' });
    return 'saved';
  } finally {
    stopPolling = true;
    pollController.abort();
    await polling;
  }
}
