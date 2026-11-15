import { SHEET_COLUMNS } from './groupLinkSheet';

/** More rows than any school network's links; a sheet that reaches it is refused rather than read in part. */
export const WORKBOOK_ROW_LIMIT = 10_000;
/** The downloaded sheet has 7 columns; this leaves room for a user's own notes columns. */
export const WORKBOOK_COLUMN_LIMIT = 40;

/**
 * The first sheet of an Excel or OpenDocument workbook as CSV, so a sheet edited in Excel can be chosen as it is. SheetJS
 * is loaded only when a workbook is chosen.
 * - The used range comes from the cells actually present (`nodim`), not from the file's own dimension tag, which Excel
 *   often bloats (formatting a whole column) and a crafted file can set to the whole grid.
 * - Only the first sheet is parsed, and at most one row more than the limit, so a sheet over the limit is refused, not
 *   silently cut.
 * - The range starts at A1 and blank rows are kept, so CSV line numbers are the sheet's row numbers.
 */
export async function workbookToCsv(data: ArrayBuffer | Uint8Array): Promise<string> {
  const XLSX = await import('xlsx');
  const book = XLSX.read(data, { type: 'array', sheets: 0, dense: true, nodim: true, sheetRows: WORKBOOK_ROW_LIMIT + 1 });
  const first = book.SheetNames[0];
  const sheet = first ? book.Sheets[first] : undefined;
  if (!sheet) throw new Error('The workbook has no sheets.');
  if (!sheet['!ref']) return '';
  const range = XLSX.utils.decode_range(sheet['!ref']);
  if (range.e.r + 1 > WORKBOOK_ROW_LIMIT) {
    throw new Error(`The sheet has more than ${WORKBOOK_ROW_LIMIT.toLocaleString('en-IN')} rows. Choose the sheet you downloaded and filled in.`);
  }
  if (range.e.c + 1 > WORKBOOK_COLUMN_LIMIT) {
    throw new Error(`The sheet is wider than ${WORKBOOK_COLUMN_LIMIT} columns; the downloaded one has ${SHEET_COLUMNS.length}. Remove the extra columns and choose it again.`);
  }
  sheet['!ref'] = XLSX.utils.encode_range({ s: { r: 0, c: 0 }, e: range.e });
  return XLSX.utils.sheet_to_csv(sheet, { blankrows: true });
}
