import { useRef, useState } from 'react';
import { Download, Upload } from 'lucide-react';
import { setupApi, workflowError, type SchoolOption, type WhatsAppGroupImportChange, type WhatsAppGroupImportResult, type WhatsAppGroupImportRow } from './api';
import {
  KIND_LABELS, MAX_SHEET_BYTES, REMOVE_WORD, linksToCsv, readLinkSheet, type SheetCategory, type SheetError, type SheetSchool, type SheetVendor,
} from './groupLinkSheet';
import { workbookToCsv } from './groupLinkWorkbook';

interface Preview {
  fileName: string;
  rows: WhatsAppGroupImportRow[];
  linked: number;
  blank: number;
  errors: SheetError[];
  result: WhatsAppGroupImportResult | null;
  vendors: SheetVendor[];
  categories: SheetCategory[];
}

type Outcome = WhatsAppGroupImportChange['outcome'];
const OUTCOME_LABELS: Record<Outcome, string> = { ADDED: 'to add', CHANGED: 'to change', REMOVED: 'to remove', UNCHANGED: 'already saved' };

function countOf(changes: WhatsAppGroupImportChange[], outcome: Outcome): number {
  return changes.filter((change) => change.outcome === outcome).length;
}

/** The file's text; FileReader, so it works wherever Blob.text() does not. */
function readText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(new Error('The file could not be read.'));
    reader.readAsText(file);
  });
}

const WORKBOOK = /\.(xlsx|xls|ods)$/i;
/** How many schools' links the download reads at once: well inside the gateway's per-user burst at any school count. */
const DOWNLOAD_CONCURRENCY = 4;

/** `work` for every item, at most `limit` at a time, answered in the items' order; the first failure fails the lot. */
async function mapLimited<T, R>(items: T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const lane = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await work(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane));
  return results;
}

function saveCsv(csv: string, fileName: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Workflow set-up → Groups → Import from a spreadsheet (design D34). Superadmin downloads every saved link, fills in the
 * link column and chooses the file again; the screen reads it, the server works out what would change (a dry run), and
 * nothing is saved until "Apply" sends the same rows for real. One line in error applies nothing.
 */
export function GroupLinkImport({ schools, onApplied }: { schools: SchoolOption[]; onApplied: () => void }) {
  const [busy, setBusy] = useState<'' | 'download' | 'read' | 'apply'>('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [preview, setPreview] = useState<Preview | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  const download = async () => {
    setBusy('download'); setError(''); setNotice('');
    try {
      const vendors = await setupApi.vendors();
      const perSchool = await mapLimited(schools, DOWNLOAD_CONCURRENCY, async (school): Promise<SheetSchool> => {
        const [links, items] = await Promise.all([setupApi.schoolGroups(school.id), setupApi.schoolItemGroups(school.id)]);
        return {
          id: school.id, name: school.name, schoolGroup: links.SCHOOL_GROUP ?? null, internalApproval: links.INTERNAL_APPROVAL ?? null,
          items: items.map((item) => ({ categoryCode: item.categoryCode, inviteUrl: item.inviteUrl })),
        };
      });
      saveCsv(linksToCsv(perSchool, vendors), `whatsapp-group-links-${new Date().toISOString().slice(0, 10)}.csv`);
      setNotice('Downloaded. Fill in the WhatsApp group link column, then choose the file below.');
    } catch (e) {
      setError(workflowError(e, 'The saved links could not be downloaded.'));
    } finally {
      setBusy('');
    }
  };

  const read = async (file: File) => {
    setBusy('read'); setError(''); setNotice(''); setPreview(null);
    try {
      if (file.size > MAX_SHEET_BYTES) throw new Error('This file is too large to be a list of group links. Choose the sheet you filled in.');
      // Every catalogue item, switched-off ones included, so a downloaded sheet always reads back.
      const [text, vendors, categories] = await Promise.all([
        WORKBOOK.test(file.name) ? file.arrayBuffer().then(workbookToCsv) : readText(file), setupApi.vendors(), setupApi.allCategories(),
      ]);
      const sheet = readLinkSheet(text, { schools, vendors, categories });
      const result = sheet.rows.length ? await setupApi.importGroupLinks({ dryRun: true, rows: sheet.rows }) : null;
      setPreview({ fileName: file.name, ...sheet, result, vendors, categories });
    } catch (e) {
      setError(workflowError(e, 'The file could not be checked.'));
    } finally {
      setBusy('');
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const apply = async () => {
    if (!preview?.result) return;
    setBusy('apply'); setError(''); setNotice('');
    try {
      const result = await setupApi.importGroupLinks({ dryRun: false, rows: preview.rows });
      if (!result.applied) {
        setPreview({ ...preview, result });
        setError('Nothing was imported. Fix the lines listed below and choose the file again.');
        return;
      }
      setPreview(null);
      setNotice(`Imported: ${countOf(result.changes, 'ADDED')} added, ${countOf(result.changes, 'CHANGED')} changed, ${countOf(result.changes, 'REMOVED')} removed.`);
      heading.current?.focus();
      onApplied();
    } catch (e) {
      setError(workflowError(e, 'The links could not be imported. Nothing was saved.'));
    } finally {
      setBusy('');
    }
  };

  const errors = preview ? [...preview.errors, ...(preview.result?.errors ?? [])].sort((a, b) => a.line - b.line) : [];
  const changes = preview?.result?.changes ?? [];
  const pending = changes.filter((change) => change.outcome !== 'UNCHANGED');
  const schoolName = (id: number | null) => schools.find((school) => school.id === id)?.name ?? `School ${id}`;
  const describe = (change: WhatsAppGroupImportChange) => {
    if (change.kind === 'VENDOR') return `${KIND_LABELS.VENDOR} · ${preview?.vendors.find((vendor) => vendor.id === change.vendorId)?.name ?? `Vendor ${change.vendorId}`}`;
    if (change.kind === 'ITEM') {
      const item = preview?.categories.find((category) => category.code === change.categoryCode)?.label ?? change.categoryCode;
      return `${item} group · ${schoolName(change.schoolId)}`;
    }
    return `${KIND_LABELS[change.kind]} · ${schoolName(change.schoolId)}`;
  };

  return (
    <div className="ck-setup-block">
      <h3 ref={heading} tabIndex={-1}>Import from a spreadsheet</h3>
      <p className="ck-setup-muted">
        Download the saved links, fill in the WhatsApp group link column in Excel or Google Sheets, and choose the file here
        (Excel or CSV). You see every change before anything is saved. A blank link keeps what is saved; {REMOVE_WORD} removes it.
      </p>
      <div className="ck-setup-actions">
        <button type="button" className="ck-btn ck-btn-ghost" disabled={busy !== '' || !schools.length} onClick={() => void download()}>
          <Download size={15} aria-hidden="true" /> {busy === 'download' ? 'Downloading…' : 'Download saved links (CSV)'}
        </button>
        <label className={`ck-btn ck-btn-ghost ck-setup-file${busy ? ' is-disabled' : ''}`}>
          <Upload size={15} aria-hidden="true" /> {busy === 'read' ? 'Checking…' : 'Choose filled-in sheet'}
          <input ref={fileInput} className="ck-sr-only" type="file" accept=".csv,.xlsx,.xls,.ods,text/csv" disabled={busy !== ''}
            onChange={(e) => { const file = e.target.files?.[0]; if (file) void read(file); }} />
        </label>
      </div>
      {error && <p className="ck-setup-error" role="alert">{error}</p>}
      {notice && <p className="ck-setup-notice" role="status">{notice}</p>}
      {preview && (
        <div className="ck-setup-import" aria-label={`Check of ${preview.fileName}`} role="region">
          <p className="ck-setup-muted">
            {preview.fileName}: {preview.linked} {preview.linked === 1 ? 'line has' : 'lines have'} a link
            {preview.blank === 1 ? '; 1 line left blank keeps what is saved' : preview.blank ? `; ${preview.blank} lines left blank keep what is saved` : ''}.
          </p>
          {errors.length > 0 && (
            <div className="ck-alert ck-alert-re" role="alert">
              <strong>{errors.length === 1 ? '1 line needs fixing' : `${errors.length} lines need fixing`} before anything can be imported:</strong>
              <ul className="ck-setup-import-errors">
                {errors.map((item, index) => <li key={`${item.line}-${index}`}>Line {item.line}: {item.message}</li>)}
              </ul>
            </div>
          )}
          {preview.result && (
            <p className="ck-setup-muted">
              {(['ADDED', 'CHANGED', 'REMOVED', 'UNCHANGED'] as const).map((outcome) => `${countOf(changes, outcome)} ${OUTCOME_LABELS[outcome]}`).join(' · ')}
            </p>
          )}
          {pending.length > 0 && (
            <div className="ck-table-wrap"><table className="ck-table ck-setup-import-table">
              <caption className="ck-sr-only">Changes the file would make</caption>
              <thead><tr><th>Line</th><th>Group</th><th>Saved now</th><th>After import</th></tr></thead>
              <tbody>{pending.map((change) => (
                <tr key={`${change.line}-${change.kind}`}>
                  <td>{change.line}</td>
                  <td>{describe(change)}</td>
                  <td>{change.before ?? '—'}</td>
                  <td>{change.after ?? 'Removed'}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
          {preview.result && !errors.length && !pending.length && <p className="ck-setup-muted" role="status">Nothing to change: every link in the file is already saved.</p>}
          <div className="ck-setup-actions">
            <button type="button" className="ck-btn ck-btn-g" disabled={busy !== '' || errors.length > 0 || !pending.length} onClick={() => void apply()}>
              {busy === 'apply' ? 'Importing…' : `Apply ${pending.length} ${pending.length === 1 ? 'change' : 'changes'}`}
            </button>
            <button type="button" className="ck-btn ck-btn-ghost" disabled={busy !== ''} onClick={() => { setPreview(null); heading.current?.focus(); }}>Cancel</button>
          </div>
        </div>
      )}
    </div>
  );
}
