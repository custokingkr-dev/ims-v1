import { describe, expect, it } from 'vitest';
import { INVALID_INVITE_MESSAGE } from '../shared/invite';
import { MAX_SHEET_ROWS, SHEET_COLUMNS, kindOf, linksToCsv, parseCsv, readLinkSheet } from './groupLinkSheet';

const A = 'https://chat.whatsapp.com/SchoolAGroup0001';
const B = 'https://chat.whatsapp.com/FlierGroup000001';
const V = 'https://chat.whatsapp.com/VendorGroup00001';
const HEADER = SHEET_COLUMNS.join(',');
const CONTEXT = {
  schools: [{ id: 7, name: 'Delhi Public School' }, { id: 8, name: 'St. Mary’s' }, { id: 9, name: 'Twin School' }, { id: 10, name: 'Twin School' }],
  vendors: [{ id: 3, name: 'Shree Boards' }, { id: 4, name: '=Formula Prints' }],
  categories: [{ code: 'FLIERS', label: 'Fliers' }, { code: 'NOTEBOOKS', label: 'Notebooks' }],
};

describe('downloading the saved links', () => {
  it('writes one row per school group, item group and vendor, sorted, with a BOM and CRLF', () => {
    const csv = linksToCsv(
      [
        { id: 8, name: 'St. Mary’s', schoolGroup: null, internalApproval: null, items: [] },
        { id: 7, name: 'Delhi Public School', schoolGroup: A, internalApproval: null, items: [{ categoryCode: 'FLIERS', inviteUrl: B }] },
      ],
      [{ id: 4, name: '=Formula Prints', whatsappGroupUrl: null }, { id: 3, name: 'Shree Boards, Gurgaon', whatsappGroupUrl: V }],
    );
    expect(csv.startsWith('\uFEFF')).toBe(true);
    expect(csv.slice(1).split('\r\n')).toEqual([
      HEADER,
      `School group,7,Delhi Public School,,,,${A}`,
      'Internal approval group,7,Delhi Public School,,,,',
      `Item group,7,Delhi Public School,FLIERS,,,${B}`,
      'School group,8,St. Mary’s,,,,',
      'Internal approval group,8,St. Mary’s,,,,',
      // A name a spreadsheet would run as a formula is written as text; a comma is quoted.
      "Vendor group,,,,4,'=Formula Prints,",
      `Vendor group,,,,3,"Shree Boards, Gurgaon",${V}`,
      '',
    ]);
  });

  it('reads back what it writes', () => {
    const csv = linksToCsv([{ id: 7, name: 'Delhi Public School', schoolGroup: A, internalApproval: null, items: [] }],
      [{ id: 4, name: '=Formula Prints', whatsappGroupUrl: V }]);
    const read = readLinkSheet(csv, CONTEXT);
    expect(read.errors).toEqual([]);
    expect(read.blank).toBe(1);
    expect(read.rows).toEqual([
      { line: 2, kind: 'SCHOOL_GROUP', inviteUrl: A, schoolId: 7 },
      { line: 4, kind: 'VENDOR', inviteUrl: V, vendorId: 4 },
    ]);
  });
});

describe('parsing CSV', () => {
  it('handles quotes, doubled quotes, line breaks inside quotes (one spreadsheet row), CRLF and a missing final newline', () => {
    expect(parseCsv('a,"b,c","say ""hi"""\r\n"multi\nline",x\ny,z')).toEqual([
      { line: 1, cells: ['a', 'b,c', 'say "hi"'] },
      { line: 2, cells: ['multi\nline', 'x'] },
      { line: 3, cells: ['y', 'z'] },
    ]);
  });

  it('reads semicolon and tab separated files as some spreadsheet locales save them', () => {
    expect(parseCsv('Type;Link\nSchool group;x')).toEqual([{ line: 1, cells: ['Type', 'Link'] }, { line: 2, cells: ['School group', 'x'] }]);
    expect(parseCsv('Type\tLink\r\nVendor group\ty\r\n')).toEqual([{ line: 1, cells: ['Type', 'Link'] }, { line: 2, cells: ['Vendor group', 'y'] }]);
  });

  it('knows each type by its label or its code, in any case', () => {
    expect(['School group', 'SCHOOL_GROUP', 'internal approval group', 'INTERNAL_APPROVAL', 'Item group', 'item', 'Vendor group', 'VENDOR'].map(kindOf))
      .toEqual(['SCHOOL_GROUP', 'SCHOOL_GROUP', 'INTERNAL_APPROVAL', 'INTERNAL_APPROVAL', 'ITEM', 'ITEM', 'VENDOR', 'VENDOR']);
    expect(kindOf('Broadcast')).toBeNull();
  });
});

describe('reading a filled-in sheet', () => {
  it('finds schools, items and vendors by id or name, keeps blank links and removes REMOVE', () => {
    const sheet = [
      HEADER,
      `School group,7,,,,,${A}?mode=ems_copy_t`,
      'Internal approval group,,delhi public  school,,,,remove',
      `Item group,,St. Mary’s,fliers,,,${B}`,
      `Item group,7,,Notebooks,,,${B}`,
      `Vendor group,,,,,shree boards,${V}`,
      'Vendor group,,,,4,,',
      '',
      ',,,,,,',
    ].join('\n');
    const read = readLinkSheet(sheet, CONTEXT);
    expect(read.errors).toEqual([]);
    expect(read.blank).toBe(1);
    expect(read.linked).toBe(5);
    expect(read.rows).toEqual([
      { line: 2, kind: 'SCHOOL_GROUP', inviteUrl: A, schoolId: 7 },
      { line: 3, kind: 'INTERNAL_APPROVAL', inviteUrl: null, schoolId: 7 },
      { line: 4, kind: 'ITEM', inviteUrl: B, schoolId: 8, categoryCode: 'FLIERS' },
      { line: 5, kind: 'ITEM', inviteUrl: B, schoolId: 7, categoryCode: 'NOTEBOOKS' },
      { line: 6, kind: 'VENDOR', inviteUrl: V, vendorId: 3 },
    ]);
  });

  it('names the line and the reason for every row it cannot use', () => {
    const sheet = [
      HEADER,
      `Broadcast,7,,,,,${A}`,
      'School group,7,,,,,https://wa.me/919876543210',
      `School group,99,,,,,${A}`,
      `School group,seven,,,,,${A}`,
      `School group,,Twin School,,,,${A}`,
      `School group,,Nowhere,,,,${A}`,
      `School group,,,,,,${A}`,
      `Item group,7,,Calendars,,,${B}`,
      `Item group,7,,,,,${B}`,
      `Vendor group,,,,77,,${V}`,
    ].join('\r\n');
    expect(readLinkSheet(sheet, CONTEXT)).toEqual({
      rows: [],
      linked: 10,
      blank: 0,
      errors: [
        { line: 2, message: 'The type must be one of: School group, Internal approval group, Item group, Vendor group' },
        { line: 3, message: `${INVALID_INVITE_MESSAGE}, or REMOVE to remove the link` },
        { line: 4, message: 'There is no school with ID 99' },
        { line: 5, message: 'The school ID must be a number' },
        { line: 6, message: 'More than one school is named "Twin School": give its school ID' },
        { line: 7, message: 'There is no school named "Nowhere"' },
        { line: 8, message: "Give the school's ID or name" },
        { line: 9, message: 'There is no item "Calendars"' },
        { line: 10, message: 'Give the item, such as FLIERS' },
        { line: 11, message: 'There is no vendor with ID 77' },
      ],
    });
  });

  it('refuses a file without the columns, an empty file, and more links than one import takes', () => {
    expect(readLinkSheet('Name,Phone\nA,1', CONTEXT).errors[0].message).toMatch(/^The first line must name the columns/);
    expect(readLinkSheet('\uFEFF\r\n', CONTEXT).errors).toEqual([{ line: 1, message: 'The file is empty.' }]);
    const many = [HEADER, ...Array.from({ length: MAX_SHEET_ROWS + 1 }, () => `School group,7,,,,,${A}`)].join('\n');
    const read = readLinkSheet(many, CONTEXT);
    expect(read.rows).toEqual([]);
    expect(read.errors[read.errors.length - 1]?.message).toBe(`Import at most ${MAX_SHEET_ROWS} links at a time; this file has ${MAX_SHEET_ROWS + 1}.`);
  });
});
