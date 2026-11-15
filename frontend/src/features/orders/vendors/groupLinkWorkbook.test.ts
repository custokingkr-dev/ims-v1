import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';
import { parseCsv } from './groupLinkSheet';
import { WORKBOOK_COLUMN_LIMIT, WORKBOOK_ROW_LIMIT, workbookToCsv } from './groupLinkWorkbook';

const LINK = 'https://chat.whatsapp.com/SchoolAGroup0001';

function workbook(sheet: XLSX.WorkSheet, extra?: XLSX.WorkSheet): ArrayBuffer {
  const book = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(book, sheet, 'Links');
  if (extra) XLSX.utils.book_append_sheet(book, extra, 'Other');
  return XLSX.write(book, { type: 'array', bookType: 'xlsx' }) as ArrayBuffer;
}

/**
 * The same workbook with the first sheet's own dimension tag rewritten in the file, as a bloated or crafted sheet carries
 * it. (Setting `!ref` before writing would make SheetJS write every cell of that range.)
 */
function withDimension(bytes: ArrayBuffer, ref: string): Uint8Array {
  const zip = XLSX.CFB.read(new Uint8Array(bytes), { type: 'array' });
  const entry = zip.FileIndex[zip.FullPaths.findIndex((path: string) => path.endsWith('worksheets/sheet1.xml'))];
  const xml = new TextDecoder().decode(entry.content as Uint8Array);
  entry.content = new TextEncoder().encode(xml.replace(/<dimension ref="[^"]*"\/>/, `<dimension ref="${ref}"/>`));
  return new Uint8Array(XLSX.CFB.write(zip, { type: 'array', fileType: 'zip' }) as ArrayLike<number>);
}

describe('reading an Excel workbook', () => {
  it('reads the first sheet as CSV, numbers as they show and rows as the sheet numbers them', async () => {
    const sheet = XLSX.utils.aoa_to_sheet([['Type', 'School ID', 'School', 'Item', 'Vendor ID', 'Vendor', 'WhatsApp group link'],
      ['School group', 7, 'Delhi Public School', '', '', '', LINK]]);
    const csv = await workbookToCsv(workbook(sheet, XLSX.utils.aoa_to_sheet([['ignored']])));
    expect(parseCsv(csv)).toEqual([
      { line: 1, cells: ['Type', 'School ID', 'School', 'Item', 'Vendor ID', 'Vendor', 'WhatsApp group link'] },
      { line: 2, cells: ['School group', '7', 'Delhi Public School', '', '', '', LINK] },
    ]);
  });

  it('keeps row numbers when the table starts below row 1', async () => {
    const sheet = XLSX.utils.sheet_add_aoa(XLSX.utils.aoa_to_sheet([]), [['Type', 'WhatsApp group link'], ['School group', LINK]], { origin: 'B3' });
    const records = parseCsv(await workbookToCsv(workbook(sheet))).filter((record) => record.cells.some((cell) => cell !== ''));
    expect(records.map((record) => record.line)).toEqual([3, 4]);
    expect(records[1].cells).toEqual(['', 'School group', LINK]);
  });

  it("reads only the cells present, whatever range the file claims, so a bloated or crafted sheet can't hang the tab", async () => {
    const bloated = withDimension(workbook(XLSX.utils.aoa_to_sheet([['Type', 'WhatsApp group link'], ['School group', LINK]])), 'A1:XFD1048576');
    const naive = XLSX.read(bloated, { type: 'array' });
    expect(naive.Sheets[naive.SheetNames[0]]['!ref']).toBe('A1:XFD1048576');
    const started = Date.now();
    const csv = await workbookToCsv(bloated);
    expect(Date.now() - started).toBeLessThan(5000);
    expect(parseCsv(csv)).toEqual([{ line: 1, cells: ['Type', 'WhatsApp group link'] }, { line: 2, cells: ['School group', LINK] }]);
  });

  it('refuses a sheet over the row or column limit instead of reading part of it', async () => {
    const tall = XLSX.utils.aoa_to_sheet(Array.from({ length: WORKBOOK_ROW_LIMIT + 5 }, (_, row) => [`row ${row + 1}`]));
    await expect(workbookToCsv(workbook(tall))).rejects.toThrow(/more than 10,000 rows/);
    const wide = XLSX.utils.aoa_to_sheet([Array.from({ length: WORKBOOK_COLUMN_LIMIT + 1 }, (_, column) => `c${column}`)]);
    await expect(workbookToCsv(workbook(wide))).rejects.toThrow(`wider than ${WORKBOOK_COLUMN_LIMIT} columns`);
  });

  it('answers an empty sheet with no text, which the reader calls an empty file', async () => {
    expect(await workbookToCsv(workbook(XLSX.utils.aoa_to_sheet([])))).toBe('');
  });
});
