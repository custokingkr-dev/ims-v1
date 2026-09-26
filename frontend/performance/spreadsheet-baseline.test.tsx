import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import * as XLSX from 'xlsx';
import ExcelJS from 'exceljs';
import { writeFileSync, mkdirSync } from 'node:fs';
import { cpus } from 'node:os';
import { performance } from 'node:perf_hooks';
import api from '../src/services/api';
import { BulkImportPanel, extractXlsxPhotos } from '../src/pages/workspace/panels/BulkImportPanel';

vi.mock('../src/services/api', () => ({ default: { post: vi.fn() } }));

const ROWS = 500;
const samples = 5;
const table = [['Name', 'Class', 'Section', 'AdmissionNo', 'DateOfBirth', 'FatherName', 'FatherContact', 'Address'],
  ...Array.from({ length: ROWS }, (_, index) => [
    `Synthetic student ${index + 1}`, '5', 'A', `PERF-${index + 1}`, '2016-01-01',
    `Synthetic guardian ${index + 1}`, '9000000000', 'Synthetic address for local measurement',
  ])];

function summary(values: number[]) {
  const sorted = values.slice().sort((left, right) => left - right);
  const round = (value: number) => Number(value.toFixed(2));
  return { samples: values.map(round), minMs: round(sorted[0]), medianMs: round(sorted[Math.floor(sorted.length / 2)]), maxMs: round(sorted.at(-1)!) };
}

async function importUntilPreview(file: File) {
  vi.mocked(api.post).mockReset();
  let finish!: (value: { elapsed: number; rows: number }) => void;
  const completed = new Promise<{ elapsed: number; rows: number }>(resolve => { finish = resolve; });
  let started = 0;
  vi.mocked(api.post).mockImplementation(async (url, data) => {
    expect(url).toBe('/students/import/upload-preview');
    const rows = JSON.parse(String((data as FormData).get('rowsJson')));
    finish({ elapsed: performance.now() - started, rows: rows.length });
    return { data: { rows: [], validCount: rows.length, errorCount: 0, warningCount: 0, fileToken: 'local-baseline' } };
  });
  render(<BulkImportPanel onRefresh={vi.fn()} />);
  let result!: { elapsed: number; rows: number };
  await act(async () => {
    started = performance.now();
    fireEvent.change(document.querySelector('input[type=file]')!, { target: { files: [file] } });
    result = await completed;
  });
  cleanup();
  expect(result.rows).toBe(ROWS);
  return result.elapsed;
}

it('records bounded actual import preflight and embedded-photo extraction baselines without network writes', async () => {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(table), 'Students');
  const xlsx = new File([XLSX.write(workbook, { type: 'array', bookType: 'xlsx' })], 'synthetic-500.xlsx');
  const csv = new File([XLSX.utils.sheet_to_csv(workbook.Sheets.Students)], 'synthetic-500.csv');

  const embedded = new ExcelJS.Workbook();
  const sheet = embedded.addWorksheet('Students');
  sheet.addRow(['Name', 'Class', 'Section', 'AdmissionNo', 'Photo']);
  for (let index = 0; index < ROWS; index++) sheet.addRow([`Synthetic ${index}`, '5', 'A', `PERF-${index + 1}`, '']);
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/ak0AAAAASUVORK5CYII=';
  for (let index = 0; index < 20; index++) {
    const imageId = embedded.addImage({ base64: png, extension: 'png' });
    sheet.addImage(imageId, { tl: { col: 4, row: index + 1 }, ext: { width: 1, height: 1 } });
  }
  const photoFile = new File([await embedded.xlsx.writeBuffer()], 'synthetic-500-with-20-tiny-photos.xlsx');
  const results: Array<Record<string, unknown>> = [];
  for (const file of [xlsx, csv]) {
    await importUntilPreview(file); // one warm-up, excluded from measurements
    const values: number[] = [];
    for (let run = 0; run < samples; run++) values.push(await importUntilPreview(file));
    results.push({ operation: 'actual BulkImportPanel preflight until mocked preview HTTP call', file: file.name, rows: ROWS, bytes: file.size, ...summary(values) });
  }
  await extractXlsxPhotos(photoFile);
  const photoTimes: number[] = [];
  for (let run = 0; run < samples; run++) {
    const start = performance.now();
    const photos = await extractXlsxPhotos(photoFile);
    photoTimes.push(performance.now() - start);
    expect(photos.size).toBe(20);
    expect(photos.has('PERF-1')).toBe(true);
    expect(photos.has('PERF-20')).toBe(true);
  }
  results.push({ operation: 'actual extractXlsxPhotos helper', file: photoFile.name, rows: ROWS, tinyPhotos: 20, bytes: photoFile.size, ...summary(photoTimes) });

  // The documented row ceiling must still stop the upload after parsing.
  const over = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(over, XLSX.utils.aoa_to_sheet([...table, ['Extra student', '5', 'A', 'PERF-501']]), 'Students');
  vi.mocked(api.post).mockReset();
  render(<BulkImportPanel onRefresh={vi.fn()} />);
  fireEvent.change(document.querySelector('input[type=file]')!, { target: { files: [new File([XLSX.write(over, { type: 'array', bookType: 'xlsx' })], 'synthetic-501.xlsx')] } });
  await screen.findByText('Maximum 500 rows per import. Please reduce the file and try again.');
  expect(api.post).not.toHaveBeenCalled();
  cleanup();

  const output = {
    measuredAt: new Date().toISOString(), environment: { node: process.version, platform: process.platform, architecture: process.arch, cpu: cpus()[0]?.model },
    method: 'One warm-up plus five sequential samples, Vitest/jsdom with real SheetJS/ExcelJS and actual production handlers; mocked preview transport; synthetic records only.',
    limitations: 'Not browser paint/input latency, backend import time, real-photo decode/resize, peak memory, a production concurrency test, or a latency SLA. Tiny valid PNGs test workbook/image mapping overhead only.',
    rejectedOverLimitWithoutHttp: true, results,
  };
  mkdirSync('../artifacts/product-followup-2026-09-26', { recursive: true });
  writeFileSync('../artifacts/product-followup-2026-09-26/frontend-spreadsheet-baseline.json', JSON.stringify(output, null, 2) + '\n');
  console.log(JSON.stringify(output));
});
