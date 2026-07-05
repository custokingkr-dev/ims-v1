import { DragEvent, useRef, useState } from 'react';
import * as XLSX from 'xlsx';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { splitCsvLine } from '../utils';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

// The exact columns the importer accepts (header names are case-insensitive on the server).
// Shown to schools and used to generate a real .xlsx template.
const IMPORT_COLUMNS: Array<{ key: string; required: boolean; example: string; note: string }> = [
  { key: 'Name', required: true, example: 'Aryan Mehta', note: 'Student full name' },
  { key: 'Class', required: true, example: '9', note: 'Class / grade (e.g. 1–12)' },
  { key: 'Section', required: true, example: 'B', note: 'Section letter' },
  { key: 'AdmissionNo', required: true, example: 'ADM-1001', note: 'Unique admission number' },
  { key: 'DateOfBirth', required: false, example: '2010-05-12', note: 'Format YYYY-MM-DD' },
  { key: 'Gender', required: false, example: 'Male', note: 'Male / Female / Other' },
  { key: 'FatherName', required: false, example: 'R. Mehta', note: '' },
  { key: 'Phone', required: false, example: '9876543210', note: '10-digit contact number' },
  { key: 'Address', required: false, example: 'Hyderabad', note: '' },
  { key: 'BoardRegistrationNo', required: false, example: 'BRN1001', note: '' },
];

// Extracts embedded images from an .xlsx and maps each to its 1-based data-row ordinal
// (matching `__rowNumber - 1` from parseRows), keeping only images anchored in the Photo column.
export async function extractXlsxPhotos(
  file: File,
): Promise<Map<number, { bytes: Uint8Array; contentType: string }>> {
  const result = new Map<number, { bytes: Uint8Array; contentType: string }>();
  const name = file.name.toLowerCase();
  if (!name.endsWith('.xlsx')) return result; // embedded images only supported for .xlsx
  const ExcelJS = (await import('exceljs')).default;
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(await file.arrayBuffer());
  const sheet = wb.worksheets[0];
  if (!sheet) return result;

  // Find the Photo column (0-indexed) from the header row.
  let photoCol = -1;
  sheet.getRow(1).eachCell({ includeEmpty: true }, (cell, col) => {
    if (String(cell.value ?? '').trim().toLowerCase() === 'photo') photoCol = col - 1; // ExcelJS col is 1-based
  });
  if (photoCol < 0) return result;

  for (const img of sheet.getImages()) {
    const tl = img.range?.tl as { nativeCol?: number; col?: number; nativeRow?: number; row?: number } | undefined;
    const anchorCol = tl?.nativeCol ?? tl?.col;
    const anchorRow = tl?.nativeRow ?? tl?.row; // 0-indexed
    if (anchorCol == null || anchorRow == null) continue;
    if (Math.round(anchorCol) !== photoCol) continue;                 // only images in the Photo column
    const dataRow = Math.round(anchorRow); // sheet row (0-indexed); header is row 0, so dataRow 1 == first data row
    if (dataRow < 1) continue;
    const media = wb.getImage(Number(img.imageId));
    const buffer = media.buffer as ArrayBuffer;
    const ext = (media.extension || 'jpeg').toLowerCase();
    const contentType = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : 'image/jpeg';
    result.set(dataRow, { bytes: new Uint8Array(buffer), contentType });
  }
  return result;
}

export type StagedPhoto =
  | { kind: 'embedded'; bytes: Uint8Array; contentType: string }
  | { kind: 'link'; url: string };

// Phase B: after students are inserted, attach each staged photo to its new student record.
// Photo failures are recorded as skips — never thrown — so a bad photo never fails the data import.
export async function attachPhotos(
  insertedStudents: Array<{ admissionNo: string; studentId: number }>,
  stagedByRow: Map<number, StagedPhoto>,
  admissionByRow: Map<number, string>,
): Promise<{ attached: number; skipped: Array<{ admissionNo: string; reason: string }> }> {
  const idByAdmission = new Map(insertedStudents.map((s) => [String(s.admissionNo), s.studentId]));
  const jobs: Array<{ admissionNo: string; studentId: number; photo: StagedPhoto }> = [];
  for (const [rowOrdinal, photo] of stagedByRow.entries()) {
    const admissionNo = admissionByRow.get(rowOrdinal);
    if (!admissionNo) continue;
    const studentId = idByAdmission.get(String(admissionNo));
    if (studentId == null) continue; // row wasn't inserted (skipped/error) — no photo to attach
    jobs.push({ admissionNo, studentId, photo });
  }

  let attached = 0;
  const skipped: Array<{ admissionNo: string; reason: string }> = [];
  const CONCURRENCY = 4;
  let cursor = 0;
  async function worker() {
    while (cursor < jobs.length) {
      const job = jobs[cursor++];
      try {
        if (job.photo.kind === 'embedded') {
          const bytes = job.photo.bytes;
          const arrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
          const blob = new Blob([arrayBuffer], { type: job.photo.contentType });
          const fd = new FormData();
          fd.append('file', new File([blob], 'photo.jpg', { type: job.photo.contentType }));
          await api.post(`/students/${job.studentId}/photo`, fd, { headers: { 'Content-Type': 'multipart/form-data' } });
        } else {
          await api.post(`/students/${job.studentId}/photo-from-url`, { url: job.photo.url });
        }
        attached += 1;
      } catch (err: unknown) {
        const reason = (err as { response?: { data?: { reason?: string } } })?.response?.data?.reason
          || (err instanceof Error ? err.message : 'failed');
        skipped.push({ admissionNo: job.admissionNo, reason });
      }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  return { attached, skipped };
}

export function BulkImportPanel({ onRefresh, schoolScopedParams: _params }: Props) {
  const bulkImportInputRef = useRef<HTMLInputElement | null>(null);
  const [bulkImportDragActive, setBulkImportDragActive] = useState(false);
  const [bulkImportError, setBulkImportError] = useState('');
  const [bulkImportWarning, setBulkImportWarning] = useState('');
  const [bulkImportFileName, setBulkImportFileName] = useState('');
  const [bulkImportPreview, setBulkImportPreview] = useState<{ fileToken?: string; rows?: { rowNumber: number; name: string; className: string; sectionName: string; admissionNo: string; phone: string; status: string; statusTone: string; description?: string }[]; validCount?: number; errorCount?: number; warningCount?: number } | null>(null);
  const [bulkImportProgress, setBulkImportProgress] = useState<{ done?: boolean; pct?: number; inserted?: number; skipped?: number; skippedRows?: { rowNumber: number; reason: string }[] } | null>(null);
  const [bulkImportToast, setBulkImportToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [saving, setSaving] = useState('');

  const parseCsvRows = (content: string) => {
    const lines = content.replace(/\r/g, '').split('\n').filter((line) => line.trim().length > 0);
    if (!lines.length) return [] as Record<string, string | number>[];
    const headers = splitCsvLine(lines[0]);
    return lines.slice(1).map((line, index) => {
      const values = splitCsvLine(line);
      const row: Record<string, string | number> = { __rowNumber: index + 2 };
      headers.forEach((header, headerIndex) => { row[header] = values[headerIndex] || ''; });
      return row;
    });
  };

  // Coerce an ExcelJS cell value to a scalar, mirroring xlsx sheet_to_json's defval: ''.
  const cellToScalar = (value: unknown): string | number => {
    if (value === null || value === undefined) return '';
    if (typeof value === 'number' || typeof value === 'string') return value;
    if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE';
    if (value instanceof Date) return value.toISOString();
    if (typeof value === 'object') {
      const v = value as Record<string, unknown>;
      if (Array.isArray(v.richText)) return v.richText.map((t) => (t as { text?: string }).text ?? '').join('');
      if (typeof v.text === 'string') return v.text;           // hyperlink cell
      if ('result' in v) return cellToScalar(v.result);        // formula cell → cached result
    }
    return '';
  };

  const parseXlsxRows = async (buffer: ArrayBuffer) => {
    const ExcelJS = (await import('exceljs')).default;
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(buffer);
    const sheet = workbook.worksheets[0];
    if (!sheet) return [] as Record<string, string | number>[];

    const headers: string[] = [];
    sheet.getRow(1).eachCell({ includeEmpty: true }, (cell, col) => { headers[col] = String(cellToScalar(cell.value)); });

    const dataRows: Record<string, string | number>[] = [];
    sheet.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      if (rowNumber === 1) return;
      const parsed: Record<string, string | number> = {};
      let hasValue = false;
      for (let col = 1; col < headers.length; col += 1) {
        const key = headers[col];
        if (!key) continue;
        const val = cellToScalar(row.getCell(col).value);
        if (val !== '') hasValue = true;
        parsed[key] = val;
      }
      if (hasValue) dataRows.push(parsed);
    });
    return dataRows.map((row, index) => ({ ...row, __rowNumber: index + 2 }));
  };

  // Uniform SheetJS-based reader for .xlsx/.xls/.ods/.csv into header-keyed rows.
  const parseRows = async (file: File): Promise<Record<string, string | number>[]> => {
    const buf = await file.arrayBuffer();
    const wb = XLSX.read(buf, { type: 'array' });
    const sheet = wb.Sheets[wb.SheetNames[0]];
    if (!sheet) return [];
    const json = XLSX.utils.sheet_to_json<Record<string, string | number>>(sheet, { defval: '', raw: false });
    return json.map((row, index) => ({ ...row, __rowNumber: index + 2 }));
  };

  const handleBulkImportFile = async (file: File) => {
    const ext = file.name.toLowerCase();
    setBulkImportError('');
    setBulkImportWarning('');
    setBulkImportToast(null);
    setBulkImportProgress(null);
    if (!(ext.endsWith('.xlsx') || ext.endsWith('.xls') || ext.endsWith('.ods') || ext.endsWith('.csv'))) { setBulkImportError('Only .xlsx, .xls, .ods, and .csv files are supported.'); return; }
    if (file.size > 5 * 1024 * 1024) { setBulkImportError('Maximum file size is 5 MB.'); return; }
    try {
      const rows = await parseRows(file);
      if (rows.length > 500) { setBulkImportWarning('Maximum 500 rows per import. Please reduce the file and try again.'); return; }
      setBulkImportFileName(file.name);
      setSaving('previewing');
      try {
        const res = await api.post('/students/import/preview', { rows });
        setBulkImportPreview(res.data as typeof bulkImportPreview);
      } finally {
        setSaving('');
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not parse and preview this file.');
      setBulkImportError(msg);
    }
  };

  const handleBulkImportDrop = async (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setBulkImportDragActive(false);
    const file = event.dataTransfer.files?.[0];
    if (file) await handleBulkImportFile(file);
  };

  const downloadImportTemplate = async () => {
    try {
      // Generate a REAL .xlsx (the old backend template was CSV mislabeled as .xlsx, which
      // failed the .xlsx parser on re-upload). ExcelJS is already used for parsing uploads.
      const ExcelJS = (await import('exceljs')).default;
      const workbook = new ExcelJS.Workbook();
      const sheet = workbook.addWorksheet('Students');
      sheet.columns = IMPORT_COLUMNS.map((c) => ({ header: c.key, key: c.key, width: Math.max(16, c.key.length + 4) }));
      sheet.getRow(1).font = { bold: true };
      sheet.addRow(Object.fromEntries(IMPORT_COLUMNS.map((c) => [c.key, c.example])));
      const buffer = await workbook.xlsx.writeBuffer();
      const url = URL.createObjectURL(new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'student-import-template.xlsx';
      link.click();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not download template.');
      setBulkImportError(msg);
    }
  };

  const confirmBulkImport = async () => {
    if (!bulkImportPreview?.fileToken) return;
    try {
      setSaving('bulk-import-confirm');
      setBulkImportToast(null);
      const confirmRes = await api.post<{ jobId?: string }>('/students/import/confirm', { fileToken: bulkImportPreview.fileToken });
      const jobId = (confirmRes.data as { jobId?: string })?.jobId;
      if (!jobId) {
        // Backend returned a synchronous inline result — no job polling needed
        const inlineResult = confirmRes.data as { inserted?: number; skipped?: number; skippedRows?: { rowNumber: number; reason: string }[] };
        setBulkImportProgress({ done: true, inserted: inlineResult.inserted, skipped: inlineResult.skipped, skippedRows: inlineResult.skippedRows });
        setBulkImportToast({ type: 'success', message: `${inlineResult.inserted ?? 0} students imported. ${inlineResult.skipped ?? 0} rows skipped.` });
      } else {
        let done = false;
        let finalStatus: { done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: { rowNumber: number; reason: string }[] } | null = null;
        while (!done) {
          const statusRes = await api.get<{ done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: { rowNumber: number; reason: string }[] } | null>(`/students/import/status/${encodeURIComponent(jobId)}`);
          finalStatus = statusRes.data;
          setBulkImportProgress(finalStatus);
          done = Boolean(finalStatus?.done);
          if (!done) await new Promise((resolve) => setTimeout(resolve, 500));
        }
        setBulkImportToast({ type: 'success', message: `${finalStatus?.inserted || 0} students imported successfully. ${finalStatus?.skipped || 0} rows skipped due to errors.` });
      }
      await onRefresh();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Import failed.');
      setBulkImportToast({ type: 'error', message: msg });
    } finally {
      setSaving('');
    }
  };

  return (
    <ModuleShell title="Bulk import" subtitle="Upload .xlsx or .csv files, preview validations, and import valid students only.">
      <input ref={bulkImportInputRef} type="file" accept=".xlsx,.xls,.ods,.csv" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) void handleBulkImportFile(file); }} />
      <div className={`ck-import-zone ${bulkImportDragActive ? 'ck-import-zone-active' : ''}`} onDragOver={(e) => { e.preventDefault(); setBulkImportDragActive(true); }} onDragLeave={() => setBulkImportDragActive(false)} onDrop={(e) => void handleBulkImportDrop(e)}>
        <div className="ck-iz-icon">📊</div>
        <div className="ck-iz-title">Drop your Excel or CSV file here</div>
        <div className="ck-iz-sub">.xlsx, .csv supported · Max 5 MB · Up to 500 rows</div>
        <div className="ck-actions-inline" style={{ justifyContent: 'center', marginTop: 14 }}>
          <button className="ck-btn ck-btn-g" type="button" onClick={() => bulkImportInputRef.current?.click()}>Browse file</button>
          <button className="ck-btn ck-btn-ghost" type="button" onClick={() => void downloadImportTemplate()}>Download sample template</button>
        </div>
        {bulkImportFileName ? <div className="ck-iz-file">Selected file: {bulkImportFileName}</div> : null}
      </div>
      <div className="ck-card" style={{ marginTop: 16 }}>
        <div className="ck-card-h"><div className="ck-card-t">Excel format — use these exact column headers (row 1)</div></div>
        <table className="ck-table">
          <thead><tr><th>Column</th><th>Required</th><th>Example</th><th>Notes</th></tr></thead>
          <tbody>
            {IMPORT_COLUMNS.map((c) => (
              <tr key={c.key}>
                <td><strong>{c.key}</strong></td>
                <td>{c.required ? <span className="ck-status sr">Required</span> : <span className="ck-status sgr">Optional</span>}</td>
                <td>{c.example}</td>
                <td className="ts">{c.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="ts" style={{ padding: '0 16px 16px' }}>Download the sample template above for a ready-to-fill .xlsx. Add student photos per student after import (bulk photo import is coming soon).</div>
      </div>
      {saving === 'previewing' ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>…</span><div>Validating file, please wait…</div></div> : null}
      {bulkImportError ? <div className="ck-alert ck-alert-r" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportError}</div></div> : null}
      {bulkImportWarning ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportWarning}</div></div> : null}
      {bulkImportToast ? <div className={`ck-alert ${bulkImportToast.type === 'success' ? 'ck-alert-g' : 'ck-alert-r'}`} style={{ marginTop: 16 }}><span>{bulkImportToast.type === 'success' ? '✓' : '!'}</span><div>{bulkImportToast.message}</div></div> : null}
      {bulkImportProgress ? <div className="ck-card" style={{ marginTop: 16 }}><div className="ck-form-body"><div className="ck-progress-wrap"><div className="ck-progress-label"><span>Import progress</span><strong>{bulkImportProgress.pct}%</strong></div><div className="ck-progress-bar"><div className="ck-progress-fill" style={{ width: `${bulkImportProgress.pct || 0}%` }} /></div></div>{bulkImportProgress.done ? <div className="ts">Done · {bulkImportProgress.inserted} inserted · {bulkImportProgress.skipped} skipped</div> : null}</div></div> : null}
      {bulkImportPreview ? (
        <div className="ck-card" style={{ marginTop: 16 }}>
          <div className="ck-card-h">
            <div>
              <div className="ck-card-t">Preview — {bulkImportPreview.rows?.length || 0} rows detected</div>
              <div className="ck-import-badges"><span className="ck-status sg">{bulkImportPreview.validCount || 0} valid</span><span className="ck-status sr">{bulkImportPreview.errorCount || 0} errors</span><span className="ck-status sam">{bulkImportPreview.warningCount || 0} warnings</span></div>
            </div>
            <button className="ck-btn ck-btn-g" disabled={(bulkImportPreview.validCount || 0) === 0 || Boolean(bulkImportProgress?.done) || saving === 'bulk-import-confirm'} onClick={() => void confirmBulkImport()}>{bulkImportProgress?.done ? 'Done' : saving === 'bulk-import-confirm' ? 'Importing…' : `Import ${bulkImportPreview.validCount || 0} valid rows`}</button>
          </div>
          <table className="ck-table">
            <thead><tr><th>#</th><th>Name</th><th>Class</th><th>Section</th><th>Admission No.</th><th>Phone</th><th>Status</th></tr></thead>
            <tbody>
              {(bulkImportPreview.rows || []).map((row) => <tr key={row.rowNumber} className={row.statusTone === 'sr' ? 'ck-row-error' : row.statusTone === 'sam' ? 'ck-row-warning' : row.statusTone === 'spu' ? 'ck-row-duplicate' : ''}><td>{row.rowNumber}</td><td>{row.name}</td><td>{row.className}</td><td>{row.sectionName}</td><td>{row.admissionNo}</td><td>{row.phone}</td><td><span className={`ck-status ${row.statusTone}`}>{row.status}</span>{row.status !== 'Valid' ? <div className="ts" style={{ marginTop: 4 }}>{row.description}</div> : null}</td></tr>)}
            </tbody>
          </table>
        </div>
      ) : null}
      {bulkImportProgress?.done && (bulkImportProgress.skippedRows || []).length ? <div className="ck-card" style={{ marginTop: 16 }}><div className="ck-card-h"><div className="ck-card-t">Skipped rows</div></div><div className="ck-form-body">{(bulkImportProgress.skippedRows || []).map((row, index) => <div key={index} className="ts" style={{ marginBottom: 8 }}>Row {row.rowNumber}: {row.reason}</div>)}</div></div> : null}
    </ModuleShell>
  );
}
