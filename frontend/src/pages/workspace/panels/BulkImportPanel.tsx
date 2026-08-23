import { DragEvent, useRef, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import type { PanelKey } from '../config';
import { StudentModuleTabs } from './StudentModuleTabs';
import {
  Check,
  Download,
  FileCheck2,
  FileDown,
  FileSpreadsheet,
  ListFilter,
  Upload,
} from 'lucide-react';

interface Props {
  setPanel?: (key: PanelKey) => void;
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
  canCreateStudents?: boolean;
}

// The exact columns the importer accepts (header names are case-insensitive on the server).
// Shown to schools and used to generate a real .xlsx template.
const IMPORT_COLUMNS: Array<{ key: string; required: boolean; example: string; note: string }> = [
  { key: 'Name', required: true, example: 'Aryan Mehta', note: 'Student full name' },
  { key: 'Class', required: true, example: '9', note: 'Must match an active class in this school setup' },
  { key: 'Section', required: true, example: 'B', note: 'Must match an active section in this school setup' },
  { key: 'AdmissionNo', required: true, example: 'ADM-1001', note: 'Unique admission number' },
  { key: 'DateOfBirth', required: false, example: '2010-05-12', note: 'Use YYYY-MM-DD; Excel date cells are also supported' },
  { key: 'AdmissionDate', required: false, example: '2024-04-01', note: 'Use YYYY-MM-DD; Excel date cells are also supported' },
  { key: 'Gender', required: false, example: 'Male', note: 'Male / Female / Other' },
  { key: 'FatherName', required: false, example: 'R. Mehta', note: '' },
  { key: 'Phone', required: true, example: '9876543210', note: '10-digit contact number' },
  { key: 'HouseNumber', required: false, example: '17-8-547', note: 'Preferred structured address field' },
  { key: 'Street', required: false, example: 'Main Road', note: 'Preferred structured address field' },
  { key: 'Locality', required: false, example: 'Shah Colony', note: 'Preferred structured address field' },
  { key: 'City', required: false, example: 'Hyderabad', note: 'Preferred structured address field' },
  { key: 'State', required: false, example: 'Telangana', note: 'Preferred structured address field' },
  { key: 'PostalCode', required: false, example: '500024', note: 'Postal or ZIP code' },
  { key: 'Address', required: false, example: '', note: 'Legacy full address; use only when structured fields are unavailable' },
  { key: 'BoardRegistrationNo', required: false, example: 'BRN1001', note: '' },
  { key: 'Photo', required: false, example: 'embedded image', note: 'Embedded image in .xlsx only, anchored in this row cell' },
  { key: 'PhotoUrl', required: false, example: 'https://school.example/photos/ADM-1001.jpg', note: 'Public https image link; works in .xlsx, .xls, .ods, and .csv' },
];

const PHOTO_IMPORT_GUIDE = [
  {
    mode: 'Embedded image',
    formats: '.xlsx only',
    format: 'Insert the image inside the Photo cell on the student row. AdmissionNo must be filled on the same row.',
  },
  {
    mode: 'Image link',
    formats: '.xlsx, .xls, .ods, .csv',
    format: 'Put a public https image URL in PhotoUrl, or in Photo if you are not embedding an image.',
  },
  {
    mode: 'Not supported',
    formats: '.xls, .ods, .csv',
    format: 'Embedded images in these formats are not extracted. Use PhotoUrl for those files.',
  },
];

function rowText(row: Record<string, string | number>, keys: string[]): string {
  const wanted = new Set(keys.map((key) => key.trim().toLowerCase()));
  for (const [key, value] of Object.entries(row)) {
    if (!wanted.has(key.trim().toLowerCase())) continue;
    const text = String(value ?? '').trim();
    if (text) return text;
  }
  return '';
}

// Extracts embedded images from an .xlsx and maps each to its AdmissionNo, keeping only
// images anchored in the Photo column. Joining by AdmissionNo (not row ordinal) avoids
// mis-attaching photos when SheetJS's `sheet_to_json` (used elsewhere for parsing) drops
// blank rows and renumbers survivors — a blank row would otherwise shift the row-ordinal
// mapping and attach a photo to the wrong student.
export async function extractXlsxPhotos(
  file: File,
): Promise<Map<string, { bytes: Uint8Array; contentType: string }>> {
  const result = new Map<string, { bytes: Uint8Array; contentType: string }>();
  if (!file.name.toLowerCase().endsWith('.xlsx')) return result; // embedded images only supported for .xlsx
  const ExcelJS = (await import('exceljs')).default;
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(await file.arrayBuffer());
  const sheet = wb.worksheets[0];
  if (!sheet) return result;

  let photoCol = -1;            // 0-indexed (matches image anchor cols)
  let admissionCol1Based = -1;  // ExcelJS getCell is 1-based
  sheet.getRow(1).eachCell({ includeEmpty: true }, (cell, col) => {
    const h = String(cell.value ?? '').trim().toLowerCase();
    if (h === 'photo') photoCol = col - 1;
    if (h === 'admissionno' || h === 'admission no') admissionCol1Based = col;
  });
  if (photoCol < 0 || admissionCol1Based < 0) return result;

  for (const img of sheet.getImages()) {
    const tl = img.range?.tl as { nativeCol?: number; col?: number; nativeRow?: number; row?: number } | undefined;
    const anchorCol = tl?.nativeCol ?? tl?.col;
    const anchorRow = tl?.nativeRow ?? tl?.row; // 0-indexed; header=0, first data row=1
    if (anchorCol == null || anchorRow == null) continue;
    if (Math.round(anchorCol) !== photoCol) continue;
    const sheetRow = Math.round(anchorRow) + 1; // ExcelJS rows are 1-based
    if (sheetRow < 2) continue;
    const admissionNo = String(sheet.getRow(sheetRow).getCell(admissionCol1Based).value ?? '').trim();
    if (!admissionNo) continue;
    const media = wb.getImage(Number(img.imageId));
    const buffer = media.buffer as unknown as ArrayBuffer;
    const ext = (media.extension || 'jpeg').toLowerCase();
    const contentType = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : 'image/jpeg';
    result.set(admissionNo, { bytes: new Uint8Array(buffer), contentType });
  }
  return result;
}

// Downscales an embedded image to <=maxDim px on the longer side before upload, as a
// client-side progressive enhancement (the server still resizes as a backstop). Falls back
// to the original bytes if canvas/Image is unavailable (e.g. jsdom in tests) or decode fails.
async function downscaleImageBlob(bytes: Uint8Array, contentType: string, maxDim = 512): Promise<Blob> {
  const original = new Blob([bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer], { type: contentType });
  try {
    const url = URL.createObjectURL(original);
    try {
      const img = new Image();
      await new Promise<void>((resolve, reject) => { img.onload = () => resolve(); img.onerror = () => reject(new Error('decode')); img.src = url; });
      const scale = Math.min(1, maxDim / Math.max(img.width, img.height));
      const w = Math.max(1, Math.round(img.width * scale));
      const h = Math.max(1, Math.round(img.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d');
      if (!ctx) return original;
      ctx.drawImage(img, 0, 0, w, h);
      const blob: Blob | null = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.82));
      return blob ?? original;
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch {
    return original; // jsdom / decode failure -> upload original (server resizes as backstop)
  }
}

export type StagedPhoto =
  | { kind: 'embedded'; bytes: Uint8Array; contentType: string }
  | { kind: 'link'; url: string };

export function normalizeImportCellValue(value: string | number | boolean | Date | null | undefined): string | number {
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) return '';
    const year = value.getFullYear();
    const month = String(value.getMonth() + 1).padStart(2, '0');
    const day = String(value.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }
  if (value == null) return '';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return typeof value === 'number' ? value : String(value);
}

interface ImportStructurePreview {
  currentClassCount: number;
  currentSectionCount: number;
  requiredClassCount: number;
  requiredSectionCount: number;
  requiresStructureUpdate: boolean;
  missingClasses?: string[];
  missingSections?: string[];
  unsupportedClasses?: string[];
}

type SkippedImportRow = {
  rowNumber: number;
  reason: string;
  name?: string;
  admissionNo?: string;
  className?: string;
  sectionName?: string;
  phone?: string;
  status?: string;
  message?: string;
};

function csvCell(value: unknown): string {
  const text = String(value ?? '');
  // Spreadsheet programs can execute formula-like CSV cells. Import content is school supplied,
  // so force dangerous leading characters to remain literal text before applying normal CSV escaping.
  const safeText = /^[=+\-@\t\r\n]/.test(text) ? `'${text}` : text;
  return /[",\r\n]/.test(safeText) ? `"${safeText.replace(/"/g, '""')}"` : safeText;
}

/** Build an Excel-friendly reconciliation export without losing commas, quotes, or line breaks. */
export function buildSkippedRowsCsv(rows: SkippedImportRow[]): string {
  const columns: Array<[string, (row: SkippedImportRow) => unknown]> = [
    ['Row', (row) => row.rowNumber],
    ['Name', (row) => row.name],
    ['AdmissionNo', (row) => row.admissionNo],
    ['Class', (row) => row.className],
    ['Section', (row) => row.sectionName],
    ['Phone', (row) => row.phone],
    ['Status', (row) => row.status || 'Skipped'],
    ['Issue', (row) => row.reason || row.message || 'Import row failed'],
  ];
  const lines = [
    columns.map(([header]) => csvCell(header)).join(','),
    ...rows.map((row) => columns.map(([, read]) => csvCell(read(row))).join(',')),
  ];
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

// Phase B: after students are inserted, attach each staged photo to its new student record.
// Photo failures are recorded as skips — never thrown — so a bad photo never fails the data import.
export async function attachPhotos(
  insertedStudents: Array<{ admissionNo: string; studentId: number }>,
  stagedByAdmission: Map<string, StagedPhoto>,
): Promise<{ attached: number; skipped: Array<{ admissionNo: string; reason: string }> }> {
  const idByAdmission = new Map(insertedStudents.map((s) => [String(s.admissionNo), s.studentId]));
  const jobs: Array<{ admissionNo: string; studentId: number; photo: StagedPhoto }> = [];
  for (const [admissionNo, photo] of stagedByAdmission.entries()) {
    const studentId = idByAdmission.get(String(admissionNo));
    if (studentId == null) continue; // row wasn't inserted (skipped/error) — no photo target
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
          const blob = await downscaleImageBlob(job.photo.bytes, job.photo.contentType);
          const fd = new FormData();
          fd.append('file', new File([blob], 'photo.jpg', { type: blob.type || 'image/jpeg' }));
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

export function BulkImportPanel({
  setPanel,
  onRefresh,
  schoolScopedParams: _params,
  canCreateStudents = true,
}: Props) {
  const bulkImportInputRef = useRef<HTMLInputElement | null>(null);
  const [bulkImportDragActive, setBulkImportDragActive] = useState(false);
  const [bulkImportError, setBulkImportError] = useState('');
  const [bulkImportWarning, setBulkImportWarning] = useState('');
  const [bulkImportFileName, setBulkImportFileName] = useState('');
  const [bulkImportPreview, setBulkImportPreview] = useState<{ fileToken?: string; batchId?: string; originalFileStored?: boolean; structure?: ImportStructurePreview; rows?: { rowNumber: number; name: string; className: string; sectionName: string; admissionNo: string; dateOfBirth?: string; phone: string; status: string; statusTone: string; description?: string; message?: string }[]; validCount?: number; errorCount?: number; warningCount?: number } | null>(null);
  const [bulkImportProgress, setBulkImportProgress] = useState<{ done?: boolean; pct?: number; inserted?: number; skipped?: number; skippedRows?: SkippedImportRow[] } | null>(null);
  const [bulkImportToast, setBulkImportToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [photoReport, setPhotoReport] = useState<{ attached: number; skipped: Array<{ admissionNo: string; reason: string }> } | null>(null);
  const [saving, setSaving] = useState('');
  const [previewFilter, setPreviewFilter] = useState<'all' | 'valid' | 'errors' | 'warnings'>('all');
  const stagedPhotosRef = useRef<Map<string, StagedPhoto>>(new Map());
  const stagedRowsRef = useRef<Record<string, string | number>[]>([]);
  const stagedFileRef = useRef<File | null>(null);
  const filteredPreviewRows = (bulkImportPreview?.rows || []).filter((row) => {
    if (previewFilter === 'all') return true;
    if (previewFilter === 'valid') return row.statusTone === 'sg';
    if (previewFilter === 'errors') return row.statusTone === 'sr' || row.statusTone === 'spu';
    return row.statusTone === 'sam';
  });

  // Uniform SheetJS-based reader for .xlsx/.xls/.ods/.csv into header-keyed rows.
  const parseRows = async (file: File): Promise<Record<string, string | number>[]> => {
    // SheetJS is only needed after the operator selects a workbook. Keeping it
    // out of the panel module makes the import instructions cheap to open.
    const XLSX = await import('xlsx');
    const buf = await file.arrayBuffer();
    const wb = XLSX.read(buf, { type: 'array', cellDates: true });
    const sheet = wb.Sheets[wb.SheetNames[0]];
    if (!sheet) return [];
    const json = XLSX.utils.sheet_to_json<Record<string, string | number | boolean | Date>>(sheet, { defval: '', raw: true });
    return json.map((row, index) => Object.fromEntries(
      Object.entries(row).map(([key, value]) => [key, normalizeImportCellValue(value)])
        .concat([['__rowNumber', index + 2]]),
    ) as Record<string, string | number>);
  };

  const previewImportFile = async (file: File, rows: Record<string, string | number>[]) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('rowsJson', JSON.stringify(rows));
    if (_params?.schoolId) fd.append('schoolId', String(_params.schoolId));
    const res = await api.post('/students/import/upload-preview', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
    setBulkImportPreview(res.data as typeof bulkImportPreview);
  };

  const handleBulkImportFile = async (file: File) => {
    const ext = file.name.toLowerCase();
    setBulkImportError('');
    setBulkImportWarning('');
    setBulkImportToast(null);
    setBulkImportProgress(null);
    setPhotoReport(null);
    if (!(ext.endsWith('.xlsx') || ext.endsWith('.xls') || ext.endsWith('.ods') || ext.endsWith('.csv'))) { setBulkImportError('Only .xlsx, .xls, .ods, and .csv files are supported.'); return; }
    if (file.size > 50 * 1024 * 1024) { setBulkImportError('Maximum file size is 50 MB.'); return; }
    try {
      const rows = await parseRows(file);
      if (rows.length > 500) { setBulkImportWarning('Maximum 500 rows per import. Please reduce the file and try again.'); return; }
      stagedRowsRef.current = rows;
      stagedFileRef.current = file;

      const hasPhotoColumn = rows.some((row) =>
        Object.keys(row).some((key) => {
          const header = key.trim().toLowerCase();
          return header === 'photo' || header === 'photourl' || header === 'photo url';
        })
      );
      const embedded = hasPhotoColumn ? await extractXlsxPhotos(file) : new Map<string, { bytes: Uint8Array; contentType: string }>();
      const stagedByAdmission = new Map<string, StagedPhoto>();
      rows.forEach((row) => {
        const admissionNo = rowText(row, ['AdmissionNo', 'Admission No', 'admissionNo']);
        if (!admissionNo) return;
        const emb = embedded.get(admissionNo);
        const link = rowText(row, ['Photo', 'PhotoUrl', 'Photo URL', 'photoUrl']);
        if (emb) stagedByAdmission.set(admissionNo, { kind: 'embedded', bytes: emb.bytes, contentType: emb.contentType });
        else if (/^https?:\/\//i.test(link)) stagedByAdmission.set(admissionNo, { kind: 'link', url: link });
      });
      stagedPhotosRef.current = stagedByAdmission;

      setBulkImportFileName(file.name);
      setSaving('previewing');
      try {
        await previewImportFile(file, rows);
      } finally {
        setSaving('');
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not parse and preview this file.');
      setBulkImportError(msg);
    }
  };

  const updateStructureAndRevalidate = async () => {
    const structure = bulkImportPreview?.structure;
    const file = stagedFileRef.current;
    if (!structure || !file) return;
    if (!_params?.schoolId) {
      setBulkImportToast({ type: 'error', message: 'School structure can only be updated from a school-scoped account.' });
      return;
    }
    if ((structure.unsupportedClasses || []).length > 0) {
      setBulkImportToast({ type: 'error', message: `Unsupported classes in file: ${structure.unsupportedClasses?.join(', ')}` });
      return;
    }
    try {
      setSaving('structure-update');
      setBulkImportToast(null);
      await api.put(`/schools/${_params.schoolId}/structure`, {
        classCount: structure.requiredClassCount,
        sectionCount: structure.requiredSectionCount,
      });
      await onRefresh();
      await previewImportFile(file, stagedRowsRef.current);
      setBulkImportToast({ type: 'success', message: 'School structure updated. The file was revalidated.' });
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not update school structure.');
      setBulkImportToast({ type: 'error', message: msg });
    } finally {
      setSaving('');
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
      const insertedStudents = (confirmRes.data as { insertedStudents?: Array<{ admissionNo: string; studentId: number }> })?.insertedStudents || [];
      if (!jobId) {
        // Backend returned a synchronous inline result — no job polling needed
        const inlineResult = confirmRes.data as { inserted?: number; skipped?: number; skippedRows?: SkippedImportRow[] };
        setBulkImportProgress({ done: true, pct: 100, inserted: inlineResult.inserted, skipped: inlineResult.skipped, skippedRows: inlineResult.skippedRows });
        setBulkImportToast({ type: 'success', message: `${inlineResult.inserted ?? 0} students imported. ${inlineResult.skipped ?? 0} rows skipped.` });
      } else {
        let done = false;
        let finalStatus: { done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: SkippedImportRow[] } | null = null;
        while (!done) {
          const statusRes = await api.get<{ done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: SkippedImportRow[] } | null>(`/students/import/status/${encodeURIComponent(jobId)}`);
          finalStatus = statusRes.data;
          setBulkImportProgress(finalStatus);
          done = Boolean(finalStatus?.done);
          if (!done) await new Promise((resolve) => setTimeout(resolve, 500));
        }
        setBulkImportToast({ type: 'success', message: `${finalStatus?.inserted || 0} students imported successfully. ${finalStatus?.skipped || 0} rows skipped due to errors.` });
      }
      await onRefresh();
      if (stagedPhotosRef.current.size > 0 && insertedStudents.length > 0) {
        setSaving('photos');
        try {
          const report = await attachPhotos(insertedStudents, stagedPhotosRef.current);
          setPhotoReport(report);
        } catch (photoErr: unknown) {
          setPhotoReport({ attached: 0, skipped: [{ admissionNo: '', reason: 'photo upload failed' }] });
        }
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Import failed.');
      setBulkImportToast({ type: 'error', message: msg });
    } finally {
      setSaving('');
    }
  };

  const downloadSkippedRows = () => {
    const rows = bulkImportProgress?.skippedRows || [];
    if (rows.length === 0) return;
    const blob = new Blob([buildSkippedRowsCsv(rows)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    try {
      const link = document.createElement('a');
      const suffix = String(bulkImportPreview?.batchId || 'latest').replace(/[^A-Za-z0-9._-]/g, '_');
      link.href = url;
      link.download = `student-import-skipped-${suffix}.csv`;
      link.click();
    } finally {
      URL.revokeObjectURL(url);
    }
  };

  return (
    <ModuleShell
      title="Bulk import"
      subtitle="Validate a spreadsheet before adding students to the school"
      actions={(
        <button className="ck-btn ck-btn-ghost ck-icon-label ck-student-workflow-action" type="button" onClick={() => void downloadImportTemplate()}>
          <FileDown size={15} aria-hidden="true" />Download template
        </button>
      )}
    >
      <StudentModuleTabs active="bulkimport" setPanel={setPanel} canCreate={canCreateStudents} />
      <div className="ck-import-steps" aria-label="Import progress">
        <div className="done"><span><Check size={14} /></span><div><strong>Prepare</strong><small>Use the template</small></div></div>
        <div className={bulkImportFileName ? 'done' : 'on'}><span>{bulkImportFileName ? <Check size={14} /> : '2'}</span><div><strong>Upload</strong><small>Select a file</small></div></div>
        <div className={bulkImportPreview ? (bulkImportProgress?.done ? 'done' : 'on') : ''}><span>{bulkImportProgress?.done ? <Check size={14} /> : '3'}</span><div><strong>Review</strong><small>Resolve row issues</small></div></div>
        <div className={bulkImportProgress?.done ? 'done' : ''}><span>{bulkImportProgress?.done ? <Check size={14} /> : '4'}</span><div><strong>Import</strong><small>Commit valid rows</small></div></div>
      </div>

      <input ref={bulkImportInputRef} type="file" accept=".xlsx,.xls,.ods,.csv" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) void handleBulkImportFile(file); }} />
      <div className="ck-import-prepare-grid">
        <div className={`ck-import-zone ${bulkImportDragActive ? 'ck-import-zone-active' : ''}`} onDragOver={(e) => { e.preventDefault(); setBulkImportDragActive(true); }} onDragLeave={() => setBulkImportDragActive(false)} onDrop={(e) => void handleBulkImportDrop(e)}>
          <div className="ck-iz-icon"><Upload size={22} aria-hidden="true" /></div>
          <div className="ck-iz-title">{bulkImportFileName ? 'Replace the selected file' : 'Drop the student file here'}</div>
          <div className="ck-iz-sub">XLSX, XLS, ODS, or CSV · Maximum 50 MB · Up to 500 rows</div>
          <div className="ck-actions-inline" style={{ justifyContent: 'center', marginTop: 14 }}>
            <button className="ck-btn ck-btn-g ck-icon-label" type="button" onClick={() => bulkImportInputRef.current?.click()}><Upload size={15} />Choose file</button>
          </div>
          {bulkImportFileName ? <div className="ck-iz-file"><FileSpreadsheet size={15} />{bulkImportFileName}</div> : null}
        </div>
        <aside className="ck-import-template">
          <span><FileDown size={19} aria-hidden="true" /></span>
          <h3>Start with the school template</h3>
          <p>It contains the accepted headers, examples, and photo columns.</p>
          <ul>
            <li><Check size={13} />Required columns are marked</li>
            <li><Check size={13} />Works with Excel and Sheets</li>
            <li><Check size={13} />Validation happens before import</li>
          </ul>
          <button className="ck-btn ck-btn-ghost ck-icon-label" type="button" onClick={() => void downloadImportTemplate()}>
            <Download size={15} />Download sample template
          </button>
        </aside>
      </div>

      <details className="ck-card ck-import-format">
        <summary>
          <span><strong>Accepted columns and photo formats</strong><small>Review the exact spreadsheet structure</small></span>
          <ListFilter size={17} aria-hidden="true" />
        </summary>
        <div className="ck-table-wrap">
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
        </div>
        <div style={{ padding: '0 16px 16px' }}>
          <div className="ck-card-t" style={{ margin: '4px 0 10px' }}>Photo import options</div>
          <div className="ck-table-wrap">
            <table className="ck-table">
              <thead><tr><th>Photo source</th><th>File formats</th><th>Required format</th></tr></thead>
              <tbody>
                {PHOTO_IMPORT_GUIDE.map((item) => (
                  <tr key={item.mode}>
                    <td><strong>{item.mode}</strong></td>
                    <td>{item.formats}</td>
                    <td className="ts">{item.format}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="ts" style={{ marginTop: 10 }}>Students are imported first. Photos are attached after import by matching AdmissionNo to the inserted student.</div>
        </div>
      </details>
      {saving === 'previewing' ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>…</span><div>Validating file, please wait…</div></div> : null}
      {saving === 'photos' ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>…</span><div>Uploading photos, please wait…</div></div> : null}
      {saving === 'structure-update' ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>…</span><div>Updating school structure and revalidating file…</div></div> : null}
      {bulkImportError ? <div className="ck-alert ck-alert-r" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportError}</div></div> : null}
      {bulkImportWarning ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportWarning}</div></div> : null}
      {bulkImportToast ? <div className={`ck-alert ${bulkImportToast.type === 'success' ? 'ck-alert-g' : 'ck-alert-r'}`} style={{ marginTop: 16 }}><span>{bulkImportToast.type === 'success' ? '✓' : '!'}</span><div>{bulkImportToast.message}</div></div> : null}
      {bulkImportPreview?.structure?.requiresStructureUpdate ? (
        <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}>
          <span>!</span>
          <div>
            <strong>File has classes or sections beyond this school setup.</strong>
            <div>
              Current setup: {bulkImportPreview.structure.currentClassCount} classes and {bulkImportPreview.structure.currentSectionCount} sections.
              File needs up to {bulkImportPreview.structure.requiredClassCount} classes and {bulkImportPreview.structure.requiredSectionCount} sections.
            </div>
            {(bulkImportPreview.structure.missingClasses || []).length ? <div>Missing classes: {bulkImportPreview.structure.missingClasses?.join(', ')}</div> : null}
            {(bulkImportPreview.structure.missingSections || []).length ? <div>Missing sections: {bulkImportPreview.structure.missingSections?.join(', ')}</div> : null}
            <div className="ck-actions-inline" style={{ marginTop: 10 }}>
              <button className="ck-btn ck-btn-g" type="button" disabled={saving === 'structure-update'} onClick={() => void updateStructureAndRevalidate()}>
                Increase setup and revalidate
              </button>
            </div>
          </div>
        </div>
      ) : null}
      {bulkImportProgress ? <div className="ck-card" style={{ marginTop: 16 }}><div className="ck-form-body"><div className="ck-progress-wrap"><div className="ck-progress-label"><span>Import progress</span><strong>{bulkImportProgress.pct}%</strong></div><div className="ck-progress-bar"><div className="ck-progress-fill" style={{ width: `${bulkImportProgress.pct || 0}%` }} /></div></div>{bulkImportProgress.done ? <div className="ts">Done · {bulkImportProgress.inserted} inserted · {bulkImportProgress.skipped} skipped</div> : null}</div></div> : null}
      {bulkImportPreview ? (
        <div className="ck-card ck-import-preview" style={{ marginTop: 16 }}>
          <div className="ck-card-h">
            <div>
              <div className="ck-card-t">Review import rows</div>
              <div className="ck-card-sub">Nothing is saved until you confirm the import.</div>
            </div>
            <button className="ck-btn ck-btn-g ck-icon-label" disabled={(bulkImportPreview.validCount || 0) === 0 || Boolean(bulkImportProgress?.done) || saving === 'bulk-import-confirm' || Boolean(bulkImportPreview.structure?.requiresStructureUpdate)} onClick={() => void confirmBulkImport()}>
              <FileCheck2 size={15} aria-hidden="true" />
              {bulkImportProgress?.done ? 'Done' : saving === 'bulk-import-confirm' ? 'Importing…' : `Import ${bulkImportPreview.validCount || 0} valid rows`}
            </button>
          </div>
          <div className="ck-import-validation-band">
            <div><strong>{bulkImportPreview.rows?.length || 0}</strong><span>Total rows</span></div>
            <div className="valid"><strong>{bulkImportPreview.validCount || 0}</strong><span>Ready to import</span></div>
            <div className="error"><strong>{bulkImportPreview.errorCount || 0}</strong><span>Blocking errors</span></div>
            <div className="warning"><strong>{bulkImportPreview.warningCount || 0}</strong><span>Warnings</span></div>
          </div>
          <div className="ck-import-filterbar">
            <div className="ck-import-filter-tabs" aria-label="Preview row filters">
              {[
                { key: 'all', label: 'All', count: bulkImportPreview.rows?.length || 0 },
                { key: 'errors', label: 'Errors', count: bulkImportPreview.errorCount || 0 },
                { key: 'warnings', label: 'Warnings', count: bulkImportPreview.warningCount || 0 },
                { key: 'valid', label: 'Ready', count: bulkImportPreview.validCount || 0 },
              ].map((filter) => (
                <button
                  key={filter.key}
                  type="button"
                  className={previewFilter === filter.key ? 'on' : ''}
                  onClick={() => setPreviewFilter(filter.key as typeof previewFilter)}
                >
                  {filter.label} <span>{filter.count}</span>
                </button>
              ))}
            </div>
            <span>{filteredPreviewRows.length} rows shown</span>
          </div>
          <div className="ck-table-wrap">
            <table className="ck-table">
              <thead><tr><th>#</th><th>Name</th><th>Class</th><th>Section</th><th>Admission No.</th><th>Date of birth</th><th>Phone</th><th>Status</th></tr></thead>
              <tbody>
                {filteredPreviewRows.map((row) => <tr key={row.rowNumber} className={row.statusTone === 'sr' ? 'ck-row-error' : row.statusTone === 'sam' ? 'ck-row-warning' : row.statusTone === 'spu' ? 'ck-row-duplicate' : ''}><td>{row.rowNumber}</td><td>{row.name}</td><td>{row.className}</td><td>{row.sectionName}</td><td>{row.admissionNo}</td><td>{row.dateOfBirth || '-'}</td><td>{row.phone}</td><td><span className={`ck-status ${row.statusTone}`}>{row.status}</span>{row.status !== 'Valid' ? <div className="ts" style={{ marginTop: 4 }}>{row.description || row.message}</div> : null}</td></tr>)}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
      {bulkImportProgress?.done && (bulkImportProgress.skippedRows || []).length ? (
        <div className="ck-card" style={{ marginTop: 16 }}>
          <div className="ck-card-h">
            <div>
              <div className="ck-card-t">Skipped rows</div>
              <div className="ts">Review these rows in the uploaded file and correct the listed issue. The export contains student data; store it securely and delete it after reconciliation.</div>
            </div>
            <button className="ck-btn ck-btn-ghost ck-icon-label" type="button" onClick={downloadSkippedRows}>
              <FileDown size={15} aria-hidden="true" />Export skipped rows
            </button>
          </div>
          <div className="ck-table-wrap">
            <table className="ck-table">
              <thead><tr><th>Row</th><th>Name</th><th>Admission No.</th><th>Class</th><th>Section</th><th>Issue</th></tr></thead>
              <tbody>
                {(bulkImportProgress.skippedRows || []).map((row, index) => (
                  <tr key={`${row.rowNumber}-${row.admissionNo || index}`} className="ck-row-error">
                    <td>{row.rowNumber}</td>
                    <td>{row.name || '-'}</td>
                    <td>{row.admissionNo || '-'}</td>
                    <td>{row.className || '-'}</td>
                    <td>{row.sectionName || '-'}</td>
                    <td>
                      <span className="ck-status sr">{row.status || 'Skipped'}</span>
                      <div className="ts" style={{ marginTop: 4 }}>{row.reason || row.message || 'Import row failed'}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
      {photoReport ? (
        <div className="ck-card" style={{ marginTop: 16 }}>
          <div className="ck-card-h"><div className="ck-card-t">Photos — {photoReport.attached} photo{photoReport.attached === 1 ? '' : 's'} attached · {photoReport.skipped.length} skipped</div></div>
          {photoReport.skipped.length ? <div className="ck-form-body">{photoReport.skipped.map((s, i) => <div key={i} className="ts" style={{ marginBottom: 8 }}>{s.admissionNo}: {s.reason}</div>)}</div> : null}
        </div>
      ) : null}
    </ModuleShell>
  );
}
