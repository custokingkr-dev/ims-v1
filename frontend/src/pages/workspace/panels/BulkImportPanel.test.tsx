import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as XLSX from 'xlsx';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { BulkImportPanel, extractXlsxPhotos, attachPhotos, buildSkippedRowsCsv, normalizeImportCellValue } from './BulkImportPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

afterEach(cleanup);

describe('BulkImportPanel Excel format', () => {
  it('shows the required column headers, an optional PhotoUrl, and the sample-template action', () => {
    render(<BulkImportPanel onRefresh={vi.fn()} />);

    // Required columns are displayed so schools know the exact format.
    expect(screen.getAllByText('AdmissionNo').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Class').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Section').length).toBeGreaterThan(0);
    expect(screen.getAllByText('HouseNumber').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Street').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Locality').length).toBeGreaterThan(0);
    expect(screen.getAllByText('City').length).toBeGreaterThan(0);
    expect(screen.getAllByText('State').length).toBeGreaterThan(0);
    expect(screen.getAllByText('PostalCode').length).toBeGreaterThan(0);
    expect(screen.getAllByText('AdmissionDate').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Address').length).toBeGreaterThan(0);
    expect(screen.getAllByText('PhotoUrl').length).toBeGreaterThan(0);

    expect(screen.getAllByText('Optional').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Required').length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText('Photo import options')).toBeInTheDocument();
    expect(screen.getByText('.xlsx only')).toBeInTheDocument();
    expect(screen.getByText(/Embedded images in these formats are not extracted/i)).toBeInTheDocument();

    // The real-.xlsx template action is present.
    expect(screen.getByRole('button', { name: /sample template/i })).toBeInTheDocument();
  });

  it('parses an .xlsx and a .csv into the same row shape via SheetJS', async () => {
    // Build an .xlsx in memory and hand it to the panel's parser through the file input.
    const ws = XLSX.utils.aoa_to_sheet([['Name', 'Class', 'AdmissionNo'], ['Aya', '1', 'A-1']]);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Students');
    const xlsxBuf = XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
    const file = new File([xlsxBuf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    vi.mocked(api.post).mockResolvedValue({ data: { rows: [], validCount: 0, errorCount: 0, warningCount: 0, fileToken: 't' } });
    render(<BulkImportPanel onRefresh={vi.fn()} />);
    const input = document.querySelector('input[type=file]') as HTMLInputElement;
    await userEvent.upload(input, file);

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/import/upload-preview', expect.any(FormData), expect.objectContaining({ headers: expect.any(Object) })));
    const previewCall = vi.mocked(api.post).mock.calls.find(([url]) => url === '/students/import/upload-preview');
    const fd = previewCall?.[1] as FormData;
    expect(JSON.parse(String(fd.get('rowsJson')))).toEqual(expect.arrayContaining([expect.objectContaining({ Name: 'Aya', Class: '1', AdmissionNo: 'A-1' })]));
  });

  it('normalizes formatted Excel date cells before preview upload', async () => {
    vi.mocked(api.post).mockReset();
    const ws = XLSX.utils.aoa_to_sheet([
      ['Name', 'Class', 'Section', 'AdmissionNo', 'DateOfBirth'],
      ['Aya', 'Nursery', 'A', 'A-1', new Date(2022, 5, 1)],
    ], { cellDates: true });
    ws.E2.z = 'yyyy/mm/dd';
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Students');
    const xlsxBuf = XLSX.write(wb, { type: 'array', bookType: 'xlsx', cellDates: true });
    const file = new File([xlsxBuf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    vi.mocked(api.post).mockResolvedValue({ data: { rows: [], validCount: 0, errorCount: 0, warningCount: 0, fileToken: 't' } });
    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/import/upload-preview', expect.any(FormData), expect.any(Object)));
    const previewCall = vi.mocked(api.post).mock.calls.find(([url]) => url === '/students/import/upload-preview');
    const fd = previewCall?.[1] as FormData;
    expect(JSON.parse(String(fd.get('rowsJson')))[0]).toEqual(expect.objectContaining({ DateOfBirth: '2022-06-01' }));
  });

  it('formats spreadsheet dates from calendar fields without UTC conversion', () => {
    const date = new Date(2022, 5, 1);
    vi.spyOn(date, 'toISOString').mockReturnValue('2022-05-31T18:30:00.000Z');

    expect(normalizeImportCellValue(date)).toBe('2022-06-01');
  });

  it('creates a UTF-8 CSV reconciliation export with escaped row failures', () => {
    const csv = buildSkippedRowsCsv([{
      rowNumber: 17,
      name: 'Asha "Anu", Rao',
      admissionNo: 'ADM-17',
      className: '4',
      sectionName: 'B',
      phone: '919999999999',
      status: 'Skipped',
      reason: 'Duplicate admission number\nUse the existing record',
    }]);

    expect(csv.startsWith('\uFEFFRow,Name,AdmissionNo')).toBe(true);
    expect(csv).toContain('"Asha ""Anu"", Rao"');
    expect(csv).toContain('"Duplicate admission number\nUse the existing record"');
  });

  it('neutralizes spreadsheet formulas and command-style skipped-row values', () => {
    const csv = buildSkippedRowsCsv([{
      rowNumber: 18,
      name: '=HYPERLINK("https://malicious.example","open")',
      admissionNo: '+cmd|\t/c calc',
      className: '@SUM(1+1)',
      sectionName: '-2+3',
      status: 'Skipped',
      reason: '\t=1+1',
    }]);

    expect(csv).toContain('"\'=HYPERLINK(""https://malicious.example"",""open"")"');
    expect(csv).toContain("'+cmd|\t/c calc");
    expect(csv).toContain("'@SUM(1+1)");
    expect(csv).toContain("'-2+3");
    expect(csv).toContain("'\t=1+1");
  });

  it('extracts an embedded image and maps it to the row anchored in the Photo column', async () => {
    const ExcelJS = (await import('exceljs')).default;
    const wb = new ExcelJS.Workbook();
    const ws = wb.addWorksheet('Students');
    ws.addRow(['Name', 'AdmissionNo', 'Photo']);       // row 1 header; Photo is column 3 (index 2)
    ws.addRow(['Aya', 'A-1', '']);                     // data row 1 -> sheet row 2
    const jpeg = new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0, 0]);
    const imageId = wb.addImage({ buffer: jpeg, extension: 'jpeg' } as unknown as Parameters<typeof wb.addImage>[0]);
    // Anchor the image's top-left into the Photo cell of the data row (col 2, row 1 — 0-indexed).
    ws.addImage(imageId, { tl: { col: 2, row: 1 }, ext: { width: 40, height: 40 } });
    const buf = await wb.xlsx.writeBuffer();
    const file = new File([buf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    const photos = await extractXlsxPhotos(file); // exported for test via a module-level helper (see Step 3)
    expect(photos.get('A-1')).toBeTruthy();            // keyed by admissionNo
    expect(photos.get('A-1')!.contentType).toContain('jpeg');
  });

  it('attaches embedded photos via multipart and link photos via photo-from-url', async () => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockResolvedValue({ data: { ok: true } });

    const stagedByAdmission = new Map<string, { kind: 'embedded'; bytes: Uint8Array; contentType: string } | { kind: 'link'; url: string }>([
      ['A-1', { kind: 'embedded', bytes: new Uint8Array([1, 2]), contentType: 'image/jpeg' }],
      ['A-2', { kind: 'link', url: 'https://cdn/x.jpg' }],
    ]);
    const inserted = [{ admissionNo: 'A-1', studentId: 11 }, { admissionNo: 'A-2', studentId: 22 }];

    const progress: Array<{ processed: number; total: number; attached: number; skipped: number; pct: number }> = [];
    const report = await attachPhotos(inserted, stagedByAdmission, update => progress.push(update));

    expect(report.attached).toBe(2);
    // embedded -> multipart to /students/11/photo
    expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.objectContaining({ headers: expect.any(Object) }));
    // link -> /students/22/photo-from-url
    expect(api.post).toHaveBeenCalledWith('/students/22/photo-from-url', { url: 'https://cdn/x.jpg' });
    expect(progress[progress.length - 1]).toEqual({ processed: 2, total: 2, attached: 2, skipped: 0, pct: 100 });
  });

  it('shows exact row progress while the confirmation request is still running', async () => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.get).mockReset();
    let finishConfirm: ((value: { data: Record<string, unknown> }) => void) | undefined;
    const confirmPending = new Promise<{ data: Record<string, unknown> }>(resolve => {
      finishConfirm = resolve;
    });
    vi.mocked(api.post).mockImplementation((url: string) => {
      if (url === '/students/import/upload-preview') {
        return Promise.resolve({
          data: {
            rows: [{ rowNumber: 2, name: 'Aya', className: '1', sectionName: 'A', admissionNo: 'A-1', phone: '', status: 'Valid', statusTone: 'sg' }],
            validCount: 1,
            errorCount: 0,
            warningCount: 0,
            fileToken: 'preview-token',
            jobId: 'job-live',
          },
        });
      }
      if (url === '/students/import/confirm') return confirmPending;
      return Promise.resolve({ data: {} });
    });
    vi.mocked(api.get).mockResolvedValue({
      data: { done: false, status: 'RUNNING', phase: 'IMPORTING', pct: 40, processedRows: 2, totalRows: 5, inserted: 2, skipped: 0 },
    });

    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(
      document.querySelector('input[type=file]') as HTMLInputElement,
      new File(['Name,Class,Section,AdmissionNo\nAya,1,A,A-1'], 'roster.csv', { type: 'text/csv' }),
    );
    await userEvent.click(await screen.findByRole('button', { name: /import 1 valid rows/i }));

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/students/import/status/job-live'));
    const progressbar = screen.getByRole('progressbar', { name: 'Student import progress' });
    expect(progressbar).toHaveAttribute('aria-valuenow', '40');
    expect(progressbar).toHaveAttribute('aria-valuetext', '2 of 5 rows processed');

    vi.mocked(api.get).mockResolvedValue({
      data: { done: true, status: 'COMPLETED', phase: 'COMPLETED', pct: 100, processedRows: 5, totalRows: 5, inserted: 5, skipped: 0 },
    });
    finishConfirm?.({ data: { jobId: 'job-live', inserted: 5, skipped: 0, insertedStudents: [] } });

    await waitFor(() => expect(progressbar).toHaveAttribute('aria-valuenow', '100'));
  });

  it('uses PhotoUrl when the optional Photo column is blank', async () => {
    const ws = XLSX.utils.aoa_to_sheet([
      ['Name', 'Class', 'Section', 'AdmissionNo', 'Photo', 'PhotoUrl'],
      ['Aya', '1', 'A', 'A-URL', '', 'https://cdn/aya.jpg'],
    ]);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Students');
    const xlsxBuf = XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
    const file = new File([xlsxBuf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockImplementation((url: string) => {
      if (url === '/students/import/upload-preview') return Promise.resolve({ data: { rows: [{ rowNumber: 2, name: 'Aya', className: '1', sectionName: 'A', admissionNo: 'A-URL', phone: '', status: 'Valid', statusTone: 'sg' }], validCount: 1, errorCount: 0, warningCount: 0, fileToken: 't' } });
      if (url === '/students/import/confirm') return Promise.resolve({ data: { done: true, inserted: 1, skipped: 0, skippedRows: [], insertedStudents: [{ admissionNo: 'A-URL', studentId: 11 }] } });
      if (url === '/students/11/photo-from-url') return Promise.resolve({ data: { ok: true } });
      return Promise.resolve({ data: {} });
    });

    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
    await screen.findByRole('button', { name: /import 1 valid rows/i });
    await userEvent.click(screen.getByRole('button', { name: /import 1 valid rows/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/11/photo-from-url', { url: 'https://cdn/aya.jpg' }));
  });

  it('records a skip (not a throw) when a photo fails', async () => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockRejectedValue({ response: { status: 422, data: { reason: 'unreachable' } } });
    const stagedByAdmission = new Map([['A-1', { kind: 'link' as const, url: 'https://cdn/x.jpg' }]]);
    const report = await attachPhotos([{ admissionNo: 'A-1', studentId: 11 }], stagedByAdmission);
    expect(report.attached).toBe(0);
    expect(report.skipped[0]).toMatchObject({ admissionNo: 'A-1', reason: 'unreachable' });
  });

  it('shows row-level skipped import details after confirm', async () => {
    const ws = XLSX.utils.aoa_to_sheet([
      ['Name', 'Class', 'Section', 'AdmissionNo', 'Phone'],
      ['Maryam Awad Balhabak', '4', 'B', '2166', '7013959554'],
    ]);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Students');
    const xlsxBuf = XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
    const file = new File([xlsxBuf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockImplementation((url: string) => {
      if (url === '/students/import/upload-preview') return Promise.resolve({ data: { rows: [{ rowNumber: 2, name: 'Maryam Awad Balhabak', className: '4', sectionName: 'B', admissionNo: '2166', phone: '7013959554', status: 'Valid', statusTone: 'sg' }], validCount: 1, errorCount: 0, warningCount: 0, fileToken: 't' } });
      if (url === '/students/import/confirm') return Promise.resolve({ data: { done: true, inserted: 0, skipped: 1, skippedRows: [{ rowNumber: 2, name: 'Maryam Awad Balhabak', admissionNo: '2166', className: '4', sectionName: 'B', status: 'Skipped', reason: 'Duplicate admission number' }], insertedStudents: [] } });
      return Promise.resolve({ data: {} });
    });

    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
    await screen.findByRole('button', { name: /import 1 valid rows/i });
    await userEvent.click(screen.getByRole('button', { name: /import 1 valid rows/i }));

    await screen.findByText('Skipped rows');
    expect(screen.getByRole('button', { name: /export skipped rows/i })).toBeInTheDocument();
    expect(screen.getAllByText('Maryam Awad Balhabak').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('2166').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Duplicate admission number')).toBeInTheDocument();
  });

  it('runs photo phase after import and shows the photo report', async () => {
    const ExcelJS = (await import('exceljs')).default;
    const wb = new ExcelJS.Workbook();
    const ws = wb.addWorksheet('S');
    ws.addRow(['Name', 'Class', 'Section', 'AdmissionNo', 'Photo']);
    ws.addRow(['Aya', '1', 'A', 'A-1', '']);
    const imageId = wb.addImage({ buffer: new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0, 0]), extension: 'jpeg' } as unknown as Parameters<typeof wb.addImage>[0]);
    ws.addImage(imageId, { tl: { col: 4, row: 1 }, ext: { width: 40, height: 40 } });
    const file = new File([await wb.xlsx.writeBuffer()], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    vi.mocked(api.post).mockImplementation((url: string) => {
      if (url === '/students/import/upload-preview') return Promise.resolve({ data: { rows: [{ rowNumber: 2, name: 'Aya', className: '1', sectionName: 'A', admissionNo: 'A-1', phone: '', status: 'Valid', statusTone: 'sg' }], validCount: 1, errorCount: 0, warningCount: 0, fileToken: 't' } });
      if (url === '/students/import/confirm') return Promise.resolve({ data: { done: true, inserted: 1, skipped: 0, skippedRows: [], insertedStudents: [{ admissionNo: 'A-1', studentId: 11 }] } });
      if (url === '/students/11/photo') return Promise.resolve({ data: { ok: true } });
      return Promise.resolve({ data: {} });
    });

    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
    await screen.findByRole('button', { name: /import 1 valid rows/i });
    await userEvent.click(screen.getByRole('button', { name: /import 1 valid rows/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.any(Object)));
    expect((await screen.findAllByText(/1 photo/i)).length).toBeGreaterThan(0);
  });
});
