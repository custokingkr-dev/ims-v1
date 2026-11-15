import { INVALID_INVITE_MESSAGE, normaliseInvite } from '../shared/invite';
import type { WhatsAppGroupImportRow } from '../../../generated/orderWorkflowClient';

/**
 * The WhatsApp group links as a spreadsheet (design D34, Workflow set-up → Groups). Superadmin downloads every link the
 * system keeps as CSV, fills in the "WhatsApp group link" column in Excel or Google Sheets and imports it (as CSV, or as
 * the Excel workbook, whose first sheet the import screen turns back into CSV).
 * One row is one link: a school's group, a school's internal approval group, a school's group for one item, or a
 * vendor's group. A blank link keeps what is saved; the word REMOVE removes it. Nothing here talks to the server: these
 * functions only write and read the file, and resolve names to the ids the import sends.
 */

export type SheetKind = 'SCHOOL_GROUP' | 'INTERNAL_APPROVAL' | 'ITEM' | 'VENDOR';

export const KIND_LABELS: Record<SheetKind, string> = {
  SCHOOL_GROUP: 'School group',
  INTERNAL_APPROVAL: 'Internal approval group',
  ITEM: 'Item group',
  VENDOR: 'Vendor group',
};

export const SHEET_COLUMNS = ['Type', 'School ID', 'School', 'Item', 'Vendor ID', 'Vendor', 'WhatsApp group link'] as const;
/** Written in the link column to remove a saved link (a blank cell keeps it). */
export const REMOVE_WORD = 'REMOVE';
/** The server's limit on one import (GroupLinkService.MAX_IMPORT_ROWS). */
export const MAX_SHEET_ROWS = 2000;
/** A school network's links fit in far less; anything bigger is not this file. */
export const MAX_SHEET_BYTES = 2_000_000;

export interface SheetSchool {
  id: number;
  name: string;
  schoolGroup: string | null;
  internalApproval: string | null;
  items: Array<{ categoryCode: string; inviteUrl: string }>;
}
export interface SheetVendor { id: number; name: string; whatsappGroupUrl?: string | null }
export interface SheetCategory { code: string; label: string }
export interface SheetError { line: number; message: string }

// ---- writing ---------------------------------------------------------------------------------------------------------

/**
 * One CSV cell. Quoted when it holds a comma, quote or line break; a value a spreadsheet would run as a formula
 * (starting =, +, -, @, tab or CR) is prefixed with an apostrophe, which spreadsheets show as text.
 */
function cell(value: string | number | null | undefined): string {
  let text = value === null || value === undefined ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

/**
 * Every link as CSV: each school's group and internal approval group (blank when none is saved), then its item groups,
 * then every vendor. A byte order mark leads, so Excel reads non-English names correctly; lines end CRLF.
 */
export function linksToCsv(schools: SheetSchool[], vendors: SheetVendor[]): string {
  const lines: Array<Array<string | number | null>> = [[...SHEET_COLUMNS]];
  for (const school of [...schools].sort(byName)) {
    lines.push([KIND_LABELS.SCHOOL_GROUP, school.id, school.name, '', '', '', school.schoolGroup]);
    lines.push([KIND_LABELS.INTERNAL_APPROVAL, school.id, school.name, '', '', '', school.internalApproval]);
    for (const item of [...school.items].sort((a, b) => a.categoryCode.localeCompare(b.categoryCode))) {
      lines.push([KIND_LABELS.ITEM, school.id, school.name, item.categoryCode, '', '', item.inviteUrl]);
    }
  }
  for (const vendor of [...vendors].sort(byName)) {
    lines.push([KIND_LABELS.VENDOR, '', '', '', vendor.id, vendor.name, vendor.whatsappGroupUrl ?? '']);
  }
  return `\uFEFF${lines.map((line) => line.map(cell).join(',')).join('\r\n')}\r\n`;
}

// ---- reading ---------------------------------------------------------------------------------------------------------

/** The delimiter of the first line: comma, or the semicolon or tab some spreadsheet locales save with. */
function delimiterOf(text: string): string {
  const first = text.split(/\r\n|\n|\r/, 1)[0].replace(/"[^"]*"/g, '');
  const counts = [',', ';', '\t'].map((d) => [d, first.split(d).length - 1] as const);
  const [best, count] = counts.reduce((a, b) => (b[1] > a[1] ? b : a));
  return count > 0 ? best : ',';
}

/**
 * CSV records (RFC 4180, quoted cells may hold line breaks), each numbered as the spreadsheet row it is: one record is
 * one row, blank rows included, however many line breaks its cells hold. That is the number the user sees in Excel.
 */
export function parseCsv(input: string): Array<{ line: number; cells: string[] }> {
  const text = input.replace(/^\uFEFF/, '');
  const delimiter = delimiterOf(text);
  const records: Array<{ line: number; cells: string[] }> = [];
  let cells: string[] = [];
  let value = '';
  let quoted = false;
  const endCell = () => { cells.push(value); value = ''; };
  const endRecord = () => { endCell(); records.push({ line: records.length + 1, cells }); cells = []; };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { value += '"'; i++; }
      else if (c === '"') quoted = false;
      else value += c;
    } else if (c === '"' && value === '') quoted = true;
    else if (c === delimiter) endCell();
    else if (c === '\r' || c === '\n') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      endRecord();
    } else value += c;
  }
  if (value !== '' || cells.length) endRecord();
  return records;
}

const headerKey = (text: string) => text.toLowerCase().replace(/[^a-z0-9]/g, '');
const nameKey = (text: string) => text.replace(/^'/, '').trim().replace(/\s+/g, ' ').toLowerCase();

/** The kind a Type cell names: the downloaded labels, or the import codes, in any case. */
export function kindOf(text: string): SheetKind | null {
  const key = headerKey(text);
  if (['schoolgroup', 'school'].includes(key)) return 'SCHOOL_GROUP';
  if (['internalapprovalgroup', 'internalapproval', 'schoolinternalapprovalgroup'].includes(key)) return 'INTERNAL_APPROVAL';
  if (['itemgroup', 'item'].includes(key)) return 'ITEM';
  if (['vendorgroup', 'vendor'].includes(key)) return 'VENDOR';
  return null;
}

function findColumns(header: string[]) {
  const keys = header.map(headerKey);
  const index = (...names: string[]) => keys.findIndex((key) => names.includes(key));
  return {
    type: index('type', 'grouptype', 'kind'),
    schoolId: index('schoolid'),
    school: index('school', 'schoolname'),
    item: index('item', 'itemcode', 'category', 'categorycode'),
    vendorId: index('vendorid'),
    vendor: index('vendor', 'vendorname'),
    link: keys.findIndex((key) => /link|url|invite/.test(key)),
  };
}

/** Finds one record by its id cell, else by its name cell; the reason it cannot, as the line's error. */
function resolve<T extends { id: number; name: string }>(
  options: T[], idText: string, nameText: string, noun: string,
): { id: number } | { error: string } {
  if (idText) {
    if (!/^\d+$/.test(idText)) return { error: `The ${noun} ID must be a number` };
    const id = Number(idText);
    return options.some((option) => option.id === id) ? { id } : { error: `There is no ${noun} with ID ${id}` };
  }
  if (!nameText) return { error: `Give the ${noun}'s ID or name` };
  const matches = options.filter((option) => nameKey(option.name) === nameKey(nameText));
  if (matches.length === 1) return { id: matches[0].id };
  return { error: matches.length ? `More than one ${noun} is named "${nameText}": give its ${noun} ID` : `There is no ${noun} named "${nameText}"` };
}

/**
 * The item a cell names, by code or by label. `categories` must hold the catalogue's inactive categories too: an item
 * group saved for a category the catalogue later switched off is still saved, so it is still in the downloaded file.
 */
function resolveItem(categories: SheetCategory[], text: string): { code: string } | { error: string } {
  if (!text) return { error: 'Give the item, such as FLIERS' };
  const byCode = categories.find((category) => category.code === text.toUpperCase());
  const byLabel = categories.filter((category) => nameKey(category.label) === nameKey(text));
  if (byCode) return { code: byCode.code };
  if (byLabel.length === 1) return { code: byLabel[0].code };
  return { error: `There is no item "${text}"` };
}

export interface SheetReadResult {
  /** The rows to import, each with the line it came from. */
  rows: WhatsAppGroupImportRow[];
  /** Lines that cannot be imported, and why. */
  errors: SheetError[];
  /** Lines with a link (or REMOVE), whether or not they could be read. */
  linked: number;
  /** Lines whose link was left blank: they keep what is saved. */
  blank: number;
}

/**
 * Reads a filled-in sheet. Rows with a blank link are left out (they keep what is saved); REMOVE removes the link; any
 * other link must be a WhatsApp group invite. Schools, items and vendors are found by their id, else by their name. The
 * server checks every row again, and also refuses one group named twice.
 */
export function readLinkSheet(text: string, context: { schools: Array<{ id: number; name: string }>; vendors: SheetVendor[]; categories: SheetCategory[] }): SheetReadResult {
  const records = parseCsv(text).filter((record) => record.cells.some((value) => value.trim() !== ''));
  if (!records.length) return { rows: [], errors: [{ line: 1, message: 'The file is empty.' }], linked: 0, blank: 0 };
  const columns = findColumns(records[0].cells);
  if (columns.type < 0 || columns.link < 0) {
    return { rows: [], linked: 0, blank: 0, errors: [{ line: records[0].line, message: `The first line must name the columns, as the downloaded file does: ${SHEET_COLUMNS.join(', ')}.` }] };
  }
  const rows: WhatsAppGroupImportRow[] = [];
  const errors: SheetError[] = [];
  let linked = 0;
  let blank = 0;
  for (const { line, cells } of records.slice(1)) {
    const get = (index: number) => (index >= 0 ? (cells[index] ?? '').trim() : '');
    const link = get(columns.link);
    if (!link) { blank++; continue; }
    linked++;
    const fail = (message: string) => { errors.push({ line, message }); };
    const kind = kindOf(get(columns.type));
    if (!kind) { fail(`The type must be one of: ${Object.values(KIND_LABELS).join(', ')}`); continue; }
    const remove = link.toUpperCase() === REMOVE_WORD;
    const invite = remove ? null : normaliseInvite(link);
    if (!remove && !invite) { fail(`${INVALID_INVITE_MESSAGE}, or ${REMOVE_WORD} to remove the link`); continue; }
    const row: WhatsAppGroupImportRow = { line, kind, inviteUrl: invite };
    if (kind === 'VENDOR') {
      const vendor = resolve(context.vendors, get(columns.vendorId), get(columns.vendor), 'vendor');
      if ('error' in vendor) { fail(vendor.error); continue; }
      row.vendorId = vendor.id;
    } else {
      const school = resolve(context.schools, get(columns.schoolId), get(columns.school), 'school');
      if ('error' in school) { fail(school.error); continue; }
      row.schoolId = school.id;
      if (kind === 'ITEM') {
        const item = resolveItem(context.categories, get(columns.item));
        if ('error' in item) { fail(item.error); continue; }
        row.categoryCode = item.code;
      }
    }
    rows.push(row);
  }
  if (rows.length > MAX_SHEET_ROWS) {
    return { rows: [], linked, blank, errors: [...errors, { line: 1, message: `Import at most ${MAX_SHEET_ROWS} links at a time; this file has ${rows.length}.` }] };
  }
  return { rows, errors, linked, blank };
}
