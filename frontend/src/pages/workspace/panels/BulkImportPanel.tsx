import { DragEvent, useRef, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { splitCsvLine } from '../utils';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
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

  const handleBulkImportFile = async (file: File) => {
    const ext = file.name.toLowerCase();
    setBulkImportError('');
    setBulkImportWarning('');
    setBulkImportToast(null);
    setBulkImportProgress(null);
    if (!(ext.endsWith('.xlsx') || ext.endsWith('.csv'))) { setBulkImportError('Only .xlsx and .csv files are supported.'); return; }
    if (file.size > 5 * 1024 * 1024) { setBulkImportError('Maximum file size is 5 MB.'); return; }
    try {
      let rows: Record<string, string | number>[] = [];
      if (ext.endsWith('.csv')) {
        rows = parseCsvRows(await file.text());
      } else {
        rows = await parseXlsxRows(await file.arrayBuffer());
      }
      if (rows.length > 500) { setBulkImportWarning('Maximum 500 rows per import. Please reduce the file and try again.'); return; }
      setBulkImportFileName(file.name);
      const res = await api.post('/students/import/preview', { rows });
      setBulkImportPreview(res.data as typeof bulkImportPreview);
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
    const res = await api.get('/students/import/template', { responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([res.data as BlobPart], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'student-import-template.xlsx';
    link.click();
    URL.revokeObjectURL(url);
  };

  const confirmBulkImport = async () => {
    if (!bulkImportPreview?.fileToken) return;
    try {
      setSaving('bulk-import-confirm');
      setBulkImportToast(null);
      const confirmRes = await api.post<{ jobId?: string }>('/students/import/confirm', { fileToken: bulkImportPreview.fileToken });
      const jobId = (confirmRes.data as { jobId?: string })?.jobId;
      let done = false;
      let finalStatus: { done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: { rowNumber: number; reason: string }[] } | null = null;
      while (!done && jobId) {
        const statusRes = await api.get<{ done?: boolean; inserted?: number; skipped?: number; pct?: number; skippedRows?: { rowNumber: number; reason: string }[] } | null>(`/students/import/status/${encodeURIComponent(jobId)}`);
        finalStatus = statusRes.data;
        setBulkImportProgress(finalStatus);
        done = Boolean(finalStatus?.done);
        if (!done) await new Promise((resolve) => setTimeout(resolve, 500));
      }
      setBulkImportToast({ type: 'success', message: `${finalStatus?.inserted || 0} students imported successfully. ${finalStatus?.skipped || 0} rows skipped due to errors.` });
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
      <input ref={bulkImportInputRef} type="file" accept=".xlsx,.csv" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) void handleBulkImportFile(file); }} />
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
