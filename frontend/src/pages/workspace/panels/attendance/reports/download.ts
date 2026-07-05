import api from '../../../../../services/api';

/** Fetch a report as a blob and trigger a browser download. Mirrors the invoice/receipt PDF idiom. */
export async function downloadReport(
  path: string,
  params: Record<string, unknown>,
  format: 'csv' | 'pdf',
  filename: string,
): Promise<void> {
  const res = await api.get(path, { params: { ...params, format }, responseType: 'blob' });
  const type = format === 'pdf' ? 'application/pdf' : 'text/csv';
  const url = window.URL.createObjectURL(new Blob([res.data as BlobPart], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}
