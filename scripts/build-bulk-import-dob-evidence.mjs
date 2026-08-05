import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

function argument(name, fallback = '') {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const workbooksDirectory = path.resolve(argument('--workbooks-dir'));
const outputPath = path.resolve(argument('--output'));
const legacyTimeZone = argument('--legacy-timezone');
if (!argument('--workbooks-dir') || !argument('--output') || !legacyTimeZone) {
  throw new Error('Usage: node scripts/build-bulk-import-dob-evidence.mjs --workbooks-dir <dir> --output <json> --legacy-timezone <IANA zone>');
}
if (!fs.existsSync(workbooksDirectory)) {
  throw new Error(`Workbook directory not found: ${workbooksDirectory}`);
}

process.env.TZ = legacyTimeZone;
const requireFromFrontend = createRequire(path.resolve('frontend/package.json'));
const XLSX = requireFromFrontend('xlsx');

function cell(row, aliases) {
  const wanted = new Set(aliases.map((alias) => alias.toLowerCase()));
  for (const [key, value] of Object.entries(row)) {
    if (wanted.has(key.trim().toLowerCase())) return value;
  }
  return '';
}

function localCalendarDate(value) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const records = [];
const workbooks = [];
const uniqueKeys = new Set();
for (const fileName of fs.readdirSync(workbooksDirectory).filter((name) => /\.(xlsx|xls|ods)$/i.test(name)).sort()) {
  const batchId = path.basename(fileName, path.extname(fileName));
  const filePath = path.join(workbooksDirectory, fileName);
  const bytes = fs.readFileSync(filePath);
  const workbook = XLSX.read(bytes, { type: 'buffer', cellDates: true });
  const sheet = workbook.Sheets[workbook.SheetNames[0]];
  if (!sheet) throw new Error(`Workbook has no first sheet: ${fileName}`);
  const rows = XLSX.utils.sheet_to_json(sheet, { defval: '', raw: true });
  let excelDateCells = 0;
  let shiftedDateCells = 0;

  rows.forEach((row, index) => {
    const dateOfBirth = cell(row, ['DateOfBirth', 'Date of Birth', 'DOB']);
    if (!(dateOfBirth instanceof Date) || Number.isNaN(dateOfBirth.getTime())) return;
    excelDateCells += 1;
    const intendedDob = localCalendarDate(dateOfBirth);
    const observedBuggyDob = dateOfBirth.toISOString().slice(0, 10);
    if (intendedDob === observedBuggyDob) return;
    shiftedDateCells += 1;
    const admissionNo = String(cell(row, ['AdmissionNo', 'Admission No', 'admissionNo'])).trim();
    if (!admissionNo) throw new Error(`Missing admission number for shifted DOB in ${fileName}, parsed row ${index + 2}`);
    const rowNumber = index + 2;
    const key = `${batchId}/${rowNumber}/${admissionNo.toLowerCase()}`;
    if (uniqueKeys.has(key)) throw new Error(`Duplicate repair evidence key: ${key}`);
    uniqueKeys.add(key);
    records.push({ batchId, rowNumber, admissionNo, intendedDob, observedBuggyDob });
  });

  workbooks.push({
    batchId,
    fileName,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    parsedRows: rows.length,
    excelDateCells,
    shiftedDateCells,
  });
}

const output = {
  schemaVersion: 1,
  generatedAtUtc: new Date().toISOString(),
  legacyTimeZone,
  workbooks,
  records,
};
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
console.log(`Wrote ${records.length} shifted-date evidence rows from ${workbooks.length} workbook(s) to ${outputPath}.`);
