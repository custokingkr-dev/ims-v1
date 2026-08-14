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

function exportUrl(schoolId: number): string {
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
  return new URL(`${base}/students/export/archive?schoolId=${encodeURIComponent(schoolId)}`, window.location.origin).toString();
}

async function authenticatedFetch(url: string): Promise<Response> {
  const request = () => {
    const token = getAccessToken();
    return fetch(url, {
      method: 'GET',
      credentials: 'include',
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

export async function downloadStudentExport(
  schoolId: number,
  suggestedName: string,
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

  const response = await authenticatedFetch(exportUrl(schoolId));
  if (!response.ok) throw await responseError(response);

  if (handle && response.body) {
    const writable = await handle.createWritable();
    try {
      const reader = response.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        await writable.write(value);
      }
      await writable.close();
      return 'saved';
    } catch (error) {
      await writable.abort?.(error).catch(() => undefined);
      throw error;
    }
  }

  // Firefox/Safari fallback. Chromium/Edge use the direct-to-disk path above, which is strongly
  // preferred for schools with thousands of portraits because it does not retain the ZIP in RAM.
  const blob = await response.blob();
  const objectUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = suggestedName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(objectUrl);
  return 'saved';
}
