import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as XLSX from 'xlsx';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { BulkImportPanel, extractXlsxPhotos, attachPhotos } from './BulkImportPanel';
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

    expect(screen.getAllByText('Optional').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Required').length).toBeGreaterThanOrEqual(4);

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

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/import/preview', expect.objectContaining({
      rows: expect.arrayContaining([expect.objectContaining({ Name: 'Aya', Class: '1', AdmissionNo: 'A-1' })]),
    })));
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
    expect(photos.get(1)).toBeTruthy();            // data row 1
    expect(photos.get(1)!.contentType).toContain('jpeg');
  });

  it('attaches embedded photos via multipart and link photos via photo-from-url', async () => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockResolvedValue({ data: { ok: true } });

    const staged = new Map<number, { kind: 'embedded'; bytes: Uint8Array; contentType: string } | { kind: 'link'; url: string }>([
      [1, { kind: 'embedded', bytes: new Uint8Array([1, 2]), contentType: 'image/jpeg' }],
      [2, { kind: 'link', url: 'https://cdn/x.jpg' }],
    ]);
    const admissionByRow = new Map<number, string>([[1, 'A-1'], [2, 'A-2']]);
    const inserted = [{ admissionNo: 'A-1', studentId: 11 }, { admissionNo: 'A-2', studentId: 22 }];

    const report = await attachPhotos(inserted, staged, admissionByRow);

    expect(report.attached).toBe(2);
    // embedded -> multipart to /students/11/photo
    expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.objectContaining({ headers: expect.any(Object) }));
    // link -> /students/22/photo-from-url
    expect(api.post).toHaveBeenCalledWith('/students/22/photo-from-url', { url: 'https://cdn/x.jpg' });
  });

  it('records a skip (not a throw) when a photo fails', async () => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockRejectedValue({ response: { status: 422, data: { reason: 'unreachable' } } });
    const staged = new Map([[1, { kind: 'link' as const, url: 'https://cdn/x.jpg' }]]);
    const report = await attachPhotos([{ admissionNo: 'A-1', studentId: 11 }], staged, new Map([[1, 'A-1']]));
    expect(report.attached).toBe(0);
    expect(report.skipped[0]).toMatchObject({ admissionNo: 'A-1', reason: 'unreachable' });
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
      if (url === '/students/import/preview') return Promise.resolve({ data: { rows: [{ rowNumber: 2, name: 'Aya', className: '1', sectionName: 'A', admissionNo: 'A-1', phone: '', status: 'Valid' }], validCount: 1, errorCount: 0, warningCount: 0, fileToken: 't' } });
      if (url === '/students/import/confirm') return Promise.resolve({ data: { done: true, inserted: 1, skipped: 0, skippedRows: [], insertedStudents: [{ admissionNo: 'A-1', studentId: 11 }] } });
      if (url === '/students/11/photo') return Promise.resolve({ data: { ok: true } });
      return Promise.resolve({ data: {} });
    });

    render(<BulkImportPanel onRefresh={vi.fn()} />);
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
    await screen.findByRole('button', { name: /import 1 valid rows/i });
    await userEvent.click(screen.getByRole('button', { name: /import 1 valid rows/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.any(Object)));
    await screen.findByText(/1 photo/i); // photo report line
  });
});
