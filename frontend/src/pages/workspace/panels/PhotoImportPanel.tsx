import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Ban,
  Check,
  Copy,
  Download,
  Eye,
  ExternalLink,
  FolderCog,
  FolderSearch,
  Link2,
  LoaderCircle,
  LockKeyhole,
  Play,
  Pencil,
  RefreshCw,
  ScanSearch,
  ShieldCheck,
  ShieldOff,
  XCircle,
} from 'lucide-react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';

interface SchoolContext {
  id: number;
  schoolUid: string;
  name: string;
  shortCode: string;
  academicYearId: string;
  academicYearLabel: string;
  driveFolderStatus: 'NOT_PROVISIONED' | 'PROVISIONING' | 'READY' | 'FAILED';
  driveFolderId?: string;
  driveFolderName?: string;
  driveFolderUrl?: string;
  driveFolderError?: string;
}

interface ImportContext {
  driveConfigured: boolean;
  managedDriveConfigured: boolean;
  schools: SchoolContext[];
  mappingColumns: string[];
  mappingFileFormats: string[];
  mappingRowLimit?: number;
  imageFileLimit?: string;
  fileNameRule: string;
}

interface ImportBatch {
  id: string;
  schoolId: number;
  schoolName: string;
  academicYearId: string;
  academicYearLabel: string;
  driveFolderId: string;
  driveFolderName?: string;
  workbookFileName?: string;
  status: string;
  totalRows: number;
  readyCount: number;
  heldCount: number;
  errorCount: number;
  appliedCount: number;
  failedCount: number;
  createdAt: string;
  photographerAccessExpiresAt?: string;
  photographerAccessRevokedAt?: string;
}

interface AccessState {
  expiresAt?: string;
  revokedAt?: string;
  overdue: boolean;
}

interface ImportRow {
  id: string;
  excelRow: number;
  admissionNo: string;
  workbookName: string;
  className: string;
  sectionName: string;
  imageNo?: string;
  driveFileName?: string;
  studentId?: number;
  status: 'READY' | 'HELD' | 'ERROR' | 'EXCLUDED' | 'APPLIED' | 'FAILED';
  message?: string;
  cropX: number;
  cropY: number;
  manuallyReviewed: boolean;
  sourceObjectKey?: string;
}

interface RecoveryRowResult {
  rowId: string;
  status: 'RECOVERED' | 'ALREADY_RECOVERED' | 'IN_PROGRESS' | 'FAILED';
  photoKey?: string;
  message?: string;
}

interface RecoveryBatchResult {
  selectedCount: number;
  recoveredCount: number;
  alreadyRecoveredCount: number;
  inProgressCount: number;
  failedCount: number;
  rows: RecoveryRowResult[];
}

const FILTERS = ['ALL', 'READY', 'HELD', 'ERROR', 'APPLIED', 'FAILED'] as const;
type RowFilter = typeof FILTERS[number];
const PHOTO_IMPORT_REQUEST_CONFIG = { timeout: 120000 };

function isTimeoutError(error: any): boolean {
  return error?.code === 'ECONNABORTED' || String(error?.message || '').toLowerCase().includes('timeout');
}

function errorMessage(error: any): string {
  const data = error?.response?.data;
  if (typeof data === 'string' && data.trim()) return data;
  if (data?.message) return data.message;
  if (data?.detail) return data.detail;
  if (data?.code) return data.code;
  return error?.message || 'The request could not be completed.';
}

function statusTone(status: string): string {
  if (status === 'READY' || status === 'COMPLETED' || status === 'APPLIED') return 'ok';
  if (status === 'HELD' || status === 'FROZEN' || status === 'EXECUTING') return 'warn';
  if (status === 'ERROR' || status === 'FAILED' || status === 'PARTIAL') return 'bad';
  return 'neutral';
}

function workflowStep(status?: string): number {
  if (!status || status === 'DRAFT') return 1;
  if (status === 'REVIEW') return 2;
  if (status === 'FROZEN') return 3;
  return 4;
}

function duplicateSchoolNames(schools: SchoolContext[]): Set<string> {
  const counts = new Map<string, number>();
  schools.forEach(school => counts.set(school.name, (counts.get(school.name) || 0) + 1));
  return new Set([...counts.entries()]
    .filter(([, count]) => count > 1)
    .map(([name]) => name));
}

function schoolOptionLabel(school: SchoolContext, duplicateNames: Set<string>): string {
  if (!duplicateNames.has(school.name)) {
    return `${school.name} (${school.shortCode})`;
  }
  return `${school.name} (${school.shortCode}, #${school.id})`;
}

export function PhotoImportPanel() {
  const [context, setContext] = useState<ImportContext | null>(null);
  const [schoolId, setSchoolId] = useState<number | ''>('');
  const [driveFolderUrl, setDriveFolderUrl] = useState('');
  const [batches, setBatches] = useState<ImportBatch[]>([]);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [filter, setFilter] = useState<RowFilter>('ALL');
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState<{ tone: 'ok' | 'bad'; text: string } | null>(null);
  const [preview, setPreview] = useState<{ row: ImportRow; url: string } | null>(null);
  const [executionConfirmed, setExecutionConfirmed] = useState(false);
  const [access, setAccess] = useState<AccessState | null>(null);
  const [editing, setEditing] = useState<{
    row: ImportRow;
    admissionNo: string;
    imageNo: string;
    excluded: boolean;
    cropX: number;
    cropY: number;
  } | null>(null);

  const selectedSchool = context?.schools.find(school => school.id === Number(schoolId));
  const duplicateNames = useMemo(
    () => duplicateSchoolNames(context?.schools || []),
    [context?.schools],
  );
  const currentStep = workflowStep(batch?.status);
  const visibleRows = useMemo(
    () => filter === 'ALL' ? rows : rows.filter(row => row.status === filter),
    [filter, rows],
  );
  const appliedRows = useMemo(
    () => rows.filter(row => row.status === 'APPLIED'),
    [rows],
  );

  const loadContext = async () => {
    setBusy('context');
    try {
      const response = await api.get<ImportContext>('/student-photo-imports/context', PHOTO_IMPORT_REQUEST_CONFIG);
      setContext(response.data);
      const firstSchool = response.data.schools[0];
      if (firstSchool) setSchoolId(current => current || firstSchool.id);
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const loadBatches = async (selectedId: number) => {
    try {
      const response = await api.get<ImportBatch[]>('/student-photo-imports', {
        ...PHOTO_IMPORT_REQUEST_CONFIG,
        params: { schoolId: selectedId },
      });
      setBatches(response.data || []);
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    }
  };

  const refreshDetail = async (id: string, options: { resetFilter?: boolean; showBusy?: boolean; showError?: boolean } = {}) => {
    if (options.showBusy) setBusy('detail');
    try {
      const response = await api.get<{ batch: ImportBatch; rows: ImportRow[]; access: AccessState }>(
        `/student-photo-imports/${id}`,
        PHOTO_IMPORT_REQUEST_CONFIG,
      );
      setBatch(response.data.batch);
      setRows(response.data.rows || []);
      setAccess(response.data.access || null);
      if (options.resetFilter) setFilter('ALL');
      return response.data.batch;
    } catch (error) {
      if (options.showError !== false) setNotice({ tone: 'bad', text: errorMessage(error) });
      throw error;
    } finally {
      if (options.showBusy) setBusy('');
    }
  };

  const loadDetail = async (id: string) => {
    try {
      await refreshDetail(id, { resetFilter: true, showBusy: true });
    } catch {
      // refreshDetail already surfaced the error to the user.
    }
  };

  useEffect(() => {
    void loadContext();
  }, []);

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url);
  }, [preview?.url]);

  useEffect(() => {
    if (schoolId) void loadBatches(Number(schoolId));
    setBatch(null);
    setRows([]);
    setAccess(null);
  }, [schoolId]);

  useEffect(() => {
    setExecutionConfirmed(false);
  }, [batch?.id, batch?.status]);

  useEffect(() => {
    if (!batch || batch.status !== 'EXECUTING') return undefined;
    const intervalId = window.setInterval(() => {
      void refreshDetail(batch.id, { showError: false }).catch(() => undefined);
      void loadBatches(batch.schoolId);
    }, 5000);
    return () => window.clearInterval(intervalId);
  }, [batch?.id, batch?.schoolId, batch?.status]);

  const createBatch = async () => {
    if (!selectedSchool) return;
    const managedReady = selectedSchool.driveFolderStatus === 'READY';
    if (!managedReady && !driveFolderUrl.trim()) return;
    setBusy('create');
    setNotice(null);
    try {
      const payload: {
        schoolId: number;
        academicYearId: string;
        driveFolderUrl?: string;
      } = {
        schoolId: selectedSchool.id,
        academicYearId: selectedSchool.academicYearId,
      };
      if (!managedReady) payload.driveFolderUrl = driveFolderUrl.trim();
      const response = await api.post<ImportBatch>('/student-photo-imports', { ...payload }, PHOTO_IMPORT_REQUEST_CONFIG);
      setBatch(response.data);
      setRows([]);
      setAccess(null);
      setDriveFolderUrl('');
      await loadBatches(selectedSchool.id);
      setNotice({ tone: 'ok', text: 'Managed Drive folder bound. The batch is ready to scan.' });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const provisionFolder = async () => {
    if (!selectedSchool) return;
    setBusy('provision');
    setNotice(null);
    try {
      const response = await api.post(
        `/student-photo-imports/folders/${selectedSchool.id}/provision`,
        undefined,
        PHOTO_IMPORT_REQUEST_CONFIG,
      );
      await loadContext();
      const ready = response.data?.status === 'READY';
      setNotice({
        tone: ready ? 'ok' : 'bad',
        text: ready
          ? 'The school and academic-year Drive folders are ready.'
          : response.data?.error || 'The Drive folder could not be provisioned.',
      });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const copyFolderLink = async () => {
    if (!selectedSchool?.driveFolderUrl) return;
    try {
      await navigator.clipboard.writeText(selectedSchool.driveFolderUrl);
      setNotice({ tone: 'ok', text: 'Photographer folder link copied.' });
    } catch {
      setNotice({ tone: 'bad', text: 'The folder link could not be copied. Open Drive and copy it there.' });
    }
  };

  const runAction = async (action: 'scan' | 'freeze' | 'execute') => {
    if (!batch) return;
    if (action === 'execute' && !executionConfirmed) return;
    setBusy(action);
    setNotice(null);
    try {
      const postAction = () => api.post<ImportBatch>(
        `/student-photo-imports/${batch.id}/${action}`,
        undefined,
        action === 'execute' ? PHOTO_IMPORT_REQUEST_CONFIG : undefined,
      );
      let current: ImportBatch;
      try {
        current = (await postAction()).data;
      } catch (error) {
        if (action !== 'execute' || !isTimeoutError(error)) throw error;
        current = await refreshDetail(batch.id, { showError: false });
      }
      while (action === 'execute' && current.status === 'EXECUTING') {
        setBatch(current);
        try {
          current = (await postAction()).data;
        } catch (error) {
          if (!isTimeoutError(error)) throw error;
          current = await refreshDetail(batch.id, { showError: false });
        }
      }
      await loadDetail(batch.id);
      await loadBatches(batch.schoolId);
      setNotice({
        tone: 'ok',
        text: action === 'scan'
          ? 'Drive and workbook scan completed.'
          : action === 'freeze'
            ? 'Source snapshot frozen. The batch is ready for execution.'
            : 'Execution finished. Review the applied and failed totals.',
      });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
      if (action === 'execute') await loadDetail(batch.id);
    } finally {
      setBusy('');
    }
  };

  const cancelBatch = async () => {
    if (!batch || !window.confirm('Cancel this import? Applied photos are not rolled back.')) return;
    setBusy('cancel');
    setNotice(null);
    try {
      const response = await api.post<ImportBatch>(`/student-photo-imports/${batch.id}/cancel`);
      setBatch(response.data);
      await loadDetail(batch.id);
      await loadBatches(batch.schoolId);
      setNotice({ tone: 'ok', text: 'Import cancelled. The intake folder can be used for a new job.' });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const markAccessRevoked = async () => {
    if (!batch) return;
    setBusy('revoke');
    try {
      const response = await api.post<AccessState>(`/student-photo-imports/${batch.id}/access-revoked`);
      setAccess(response.data);
      setNotice({ tone: 'ok', text: 'Photographer Drive access recorded as revoked.' });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const downloadResult = async () => {
    if (!batch) return;
    setBusy('result');
    try {
      const response = await api.get(`/student-photo-imports/${batch.id}/result`, {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(response.data);
      const link = document.createElement('a');
      link.href = url;
      link.download = `student-photo-import-${batch.id}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const recoverAppliedPhotos = async () => {
    if (!batch || appliedRows.length === 0) return;
    const confirmed = window.confirm(
      `Restore ${appliedRows.length} applied student photo${appliedRows.length === 1 ? '' : 's'} `
      + 'from the retained Drive originals? Photos changed after this import will be left untouched.',
    );
    if (!confirmed) return;

    setBusy('recover');
    setNotice(null);
    try {
      const totals = {
        recovered: 0,
        alreadyRecovered: 0,
        inProgress: 0,
        failed: 0,
      };
      for (let offset = 0; offset < appliedRows.length; offset += 100) {
        const rowIds = appliedRows.slice(offset, offset + 100).map(row => row.id);
        const response = await api.post<RecoveryBatchResult>(
          `/student-photo-imports/${batch.id}/recover`,
          { rowIds },
          PHOTO_IMPORT_REQUEST_CONFIG,
        );
        totals.recovered += response.data.recoveredCount;
        totals.alreadyRecovered += response.data.alreadyRecoveredCount;
        totals.inProgress += response.data.inProgressCount;
        totals.failed += response.data.failedCount;
      }

      await refreshDetail(batch.id, { showError: false });
      const successful = totals.recovered + totals.alreadyRecovered;
      const requiresAttention = totals.failed + totals.inProgress;
      setNotice({
        tone: requiresAttention > 0 ? 'bad' : 'ok',
        text: requiresAttention > 0
          ? `Full-frame recovery completed for ${successful} photo${successful === 1 ? '' : 's'}; `
            + `${totals.failed} could not be safely replaced and ${totals.inProgress} `
            + `${totals.inProgress === 1 ? 'is' : 'are'} already in progress. `
            + 'Retry after reviewing Drive access and source files.'
          : `Full-frame recovery completed for ${successful} photo${successful === 1 ? '' : 's'}. `
            + 'Existing newer photo changes were protected.',
      });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const saveRowReview = async (previewAfterSave = false) => {
    if (!batch || !editing) return;
    setBusy(`edit:${editing.row.id}`);
    try {
      const response = await api.post<{ batch: ImportBatch; row: ImportRow }>(
        `/student-photo-imports/${batch.id}/rows/${editing.row.id}`,
        {
          admissionNo: editing.admissionNo,
          imageNo: editing.imageNo,
          excluded: editing.excluded,
          cropX: editing.cropX,
          cropY: editing.cropY,
        },
      );
      setBatch(response.data.batch);
      setRows(current => current.map(row => row.id === response.data.row.id ? response.data.row : row));
      setEditing(null);
      setNotice({ tone: 'ok', text: 'Row review saved and batch totals recalculated.' });
      if (previewAfterSave && response.data.row.driveFileName) {
        await openPreview(response.data.row);
      }
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  const openPreview = async (row: ImportRow) => {
    if (!batch || !row.driveFileName) return;
    setBusy(`preview:${row.id}`);
    try {
      const response = await api.get(`/student-photo-imports/${batch.id}/rows/${row.id}/preview`, {
        responseType: 'blob',
      });
      if (preview) URL.revokeObjectURL(preview.url);
      setPreview({ row, url: URL.createObjectURL(response.data) });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setBusy('');
    }
  };

  return (
    <ModuleShell
      title="Student photo import"
      subtitle="Controlled Google Drive mapping for the current academic year."
      actions={batch && (
        <button className="ck-btn ck-btn-ghost" onClick={() => loadDetail(batch.id)} disabled={!!busy}>
          <RefreshCw size={15} aria-hidden /> Refresh
        </button>
      )}
    >
      <div className="pi-workspace">
        {notice && (
          <div className={`pi-notice ${notice.tone}`} role={notice.tone === 'bad' ? 'alert' : 'status'}>
            {notice.tone === 'bad' ? <AlertTriangle size={17} /> : <Check size={17} />}
            <span>{notice.text}</span>
          </div>
        )}

        {busy === 'context' && !context && (
          <div className="pi-loading"><LoaderCircle className="pi-spin" size={20} /> Loading import access...</div>
        )}

        {context && !context.driveConfigured && (
          <div className="pi-notice bad" role="alert">
            <AlertTriangle size={17} />
            <span>A personal Google Drive account is not connected in this environment.</span>
          </div>
        )}

        {context && context.schools.length === 0 && (
          <div className="pi-empty">
            <ShieldCheck size={24} />
            <strong>No assigned schools</strong>
            <span>A Superadmin must assign at least one school to this Operations account.</span>
          </div>
        )}

        {context && context.schools.length > 0 && (
          <>
            <section className="pi-scope-band" aria-label="Import scope">
              <div className="pi-scope-control">
                <label htmlFor="pi-school">School</label>
                <select
                  id="pi-school"
                  value={schoolId}
                  onChange={event => setSchoolId(Number(event.target.value))}
                  disabled={!!batch && !['COMPLETED', 'PARTIAL', 'FAILED'].includes(batch.status)}
                >
                  {context.schools.map(school => (
                    <option key={school.id} value={school.id}>{schoolOptionLabel(school, duplicateNames)}</option>
                  ))}
                </select>
              </div>
              <div className="pi-scope-value">
                <span>Academic year</span>
                <strong>{selectedSchool?.academicYearLabel || '-'}</strong>
              </div>
              <div className="pi-scope-value">
                <span>School identity</span>
                <strong>{selectedSchool?.shortCode || '-'}</strong>
              </div>
              <div className="pi-scope-lock">
                <LockKeyhole size={16} />
                <span>Platform-bound scope</span>
              </div>
            </section>

            {!batch && (
              <section className="pi-source-panel">
                <div className="pi-section-title">
                  <FolderSearch size={18} />
                  <div>
                    <h2>Photographer intake folder</h2>
                    <p>Provisioned for this school and academic year.</p>
                  </div>
                </div>
                <div className="pi-folder-source">
                  <div className="pi-folder-identity">
                    <span className="pi-folder-icon"><FolderCog size={19} aria-hidden /></span>
                    <div>
                      <strong>{selectedSchool?.driveFolderName || 'Student Photo Intake'}</strong>
                      <span>
                        {selectedSchool?.shortCode} / {selectedSchool?.academicYearLabel} / Student Photo Intake
                      </span>
                    </div>
                    <span className={`pi-status ${statusTone(selectedSchool?.driveFolderStatus || '')}`}>
                      {selectedSchool?.driveFolderStatus?.replace('_', ' ') || 'NOT PROVISIONED'}
                    </span>
                  </div>
                  {selectedSchool?.driveFolderStatus === 'READY' && selectedSchool.driveFolderUrl ? (
                    <div className="pi-folder-actions">
                      <button
                        className="ck-btn ck-btn-ghost"
                        onClick={copyFolderLink}
                        disabled={!!busy}
                      >
                        <Copy size={15} aria-hidden /> Copy link
                      </button>
                      <a
                        className="ck-btn ck-btn-ghost"
                        href={selectedSchool.driveFolderUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        <ExternalLink size={15} aria-hidden /> Open in Drive
                      </a>
                      <button
                        className="ck-btn ck-btn-g pi-primary-action"
                        disabled={!!busy || !context.driveConfigured}
                        onClick={createBatch}
                      >
                        {busy === 'create' ? <LoaderCircle className="pi-spin" size={16} /> : <ShieldCheck size={16} />}
                        Start manual import
                      </button>
                    </div>
                  ) : context.managedDriveConfigured ? (
                    <div className="pi-folder-recovery">
                      <span>
                        {selectedSchool?.driveFolderError
                          || 'The managed folder has not been created yet.'}
                      </span>
                      <button
                        className="ck-btn ck-btn-g"
                        onClick={provisionFolder}
                        disabled={!!busy || !context.managedDriveConfigured}
                      >
                        {busy === 'provision'
                          ? <LoaderCircle className="pi-spin" size={16} />
                          : <FolderCog size={16} />}
                        Provision folder
                      </button>
                    </div>
                  ) : (
                    <div className="pi-folder-fallback">
                      <span>Personal Drive is not connected or its intake root is not configured.</span>
                      <div>
                        <label className="pi-input-group">
                          <span>Google Drive folder</span>
                          <div className="pi-input-icon">
                            <Link2 size={16} aria-hidden />
                            <input
                              value={driveFolderUrl}
                              onChange={event => setDriveFolderUrl(event.target.value)}
                              placeholder="https://drive.google.com/drive/folders/..."
                            />
                          </div>
                        </label>
                        <button
                          className="ck-btn ck-btn-g"
                          onClick={createBatch}
                          disabled={!driveFolderUrl.trim() || !!busy || !context.driveConfigured}
                        >
                          {busy === 'create'
                            ? <LoaderCircle className="pi-spin" size={16} />
                            : <ShieldCheck size={16} />}
                          Verify and start
                        </button>
                      </div>
                    </div>
                  )}
                </div>
                <div className="pi-contract">
                  <span><strong>Access:</strong> Restricted; share with the photographer as Editor</span>
                  <span>
                    <strong>Mapping file:</strong> {context.mappingFileFormats?.join(', ') || 'XLSX, XLS, CSV, TSV'}
                    {' / '}{context.mappingColumns.join(', ')}
                    {context.mappingRowLimit ? ` / up to ${context.mappingRowLimit} rows` : ''}
                  </span>
                  <span><strong>Images:</strong> {context.fileNameRule}{context.imageFileLimit ? ` / ${context.imageFileLimit}` : ''}</span>
                </div>
              </section>
            )}

            {batch && (
              <>
                <section className="pi-progress" aria-label="Import progress">
                  {[
                    ['Source', 'Folder bound'],
                    ['Review', 'Workbook matched'],
                    ['Freeze', 'Files locked'],
                    ['Execute', 'Portraits applied'],
                  ].map(([label, detail], index) => {
                    const number = index + 1;
                    const complete = number < currentStep || (number === 4 && batch.status === 'COMPLETED');
                    return (
                      <div className={`pi-step ${number === currentStep ? 'active' : ''} ${complete ? 'complete' : ''}`} key={label}>
                        <span className="pi-step-number">{complete ? <Check size={14} /> : number}</span>
                        <span><strong>{label}</strong><small>{detail}</small></span>
                      </div>
                    );
                  })}
                </section>

                <section className="pi-batch-head">
                  <div>
                    <div className="pi-eyebrow">{batch.driveFolderName || 'Drive folder'}</div>
                    <h2>{batch.schoolName} / {batch.academicYearLabel}</h2>
                    <p>{batch.workbookFileName || 'Workbook not scanned'} <span>Batch {batch.id.slice(0, 8)}</span></p>
                  </div>
                  <span className={`pi-status ${statusTone(batch.status)}`}>{batch.status}</span>
                </section>

                <section className="pi-metrics" aria-label="Batch totals">
                  {[
                    ['Rows', batch.totalRows],
                    ['Ready', batch.readyCount],
                    ['Held', batch.heldCount],
                    ['Errors', batch.errorCount],
                    ['Applied', batch.appliedCount],
                    ['Failed', batch.failedCount],
                  ].map(([label, value]) => (
                    <div key={label}><span>{label}</span><strong>{value}</strong></div>
                  ))}
                </section>

                <section className="pi-action-band">
                  <div>
                    <strong>
                      {batch.status === 'DRAFT' && 'Scan Drive and validate the workbook.'}
                      {batch.status === 'REVIEW' && 'Review every held or error row before freezing.'}
                      {batch.status === 'FROZEN' && 'The source snapshot is unchanged and ready.'}
                      {batch.status === 'EXECUTING' && 'Execution can safely resume from the remaining ready rows.'}
                      {['COMPLETED', 'PARTIAL', 'FAILED'].includes(batch.status) && 'Execution result is recorded per row.'}
                    </strong>
                    <span>{batch.readyCount} ready, {batch.heldCount} held, {batch.errorCount} blocking errors</span>
                  </div>
                  <div className="pi-actions">
                    {['DRAFT', 'REVIEW'].includes(batch.status) && (
                      <button className="ck-btn ck-btn-ghost" onClick={() => runAction('scan')} disabled={!!busy}>
                        {busy === 'scan' ? <LoaderCircle className="pi-spin" size={16} /> : <ScanSearch size={16} />}
                        {batch.status === 'DRAFT' ? 'Scan folder' : 'Rescan'}
                      </button>
                    )}
                    {batch.status === 'REVIEW' && (
                      <button
                        className="ck-btn ck-btn-g"
                        onClick={() => runAction('freeze')}
                        disabled={!!busy || batch.errorCount > 0 || batch.readyCount === 0}
                      >
                        {busy === 'freeze' ? <LoaderCircle className="pi-spin" size={16} /> : <LockKeyhole size={16} />}
                        Freeze source
                      </button>
                    )}
                    {['FROZEN', 'EXECUTING', 'PARTIAL', 'FAILED'].includes(batch.status) && (
                      <>
                        <label className="pi-execute-confirm">
                          <input
                            type="checkbox"
                            checked={executionConfirmed}
                            onChange={event => setExecutionConfirmed(event.target.checked)}
                            disabled={!!busy}
                          />
                          <span>
                            Confirm {batch.schoolName}, {batch.academicYearLabel}, and {batch.readyCount} ready portraits
                          </span>
                        </label>
                        <button
                          className="ck-btn ck-btn-g"
                          onClick={() => runAction('execute')}
                          disabled={!!busy || !executionConfirmed}
                        >
                          {busy === 'execute' ? <LoaderCircle className="pi-spin" size={16} /> : <Play size={16} />}
                          {batch.status === 'FROZEN'
                            ? 'Execute import'
                            : batch.status === 'EXECUTING' ? 'Resume import' : 'Retry failed'}
                        </button>
                      </>
                    )}
                    {['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(batch.status) && (
                      <>
                        {appliedRows.length > 0 && (
                          <button className="ck-btn ck-btn-g" onClick={recoverAppliedPhotos} disabled={!!busy}>
                            {busy === 'recover'
                              ? <LoaderCircle className="pi-spin" size={16} />
                              : <RefreshCw size={16} />}
                            Restore full-frame photos
                          </button>
                        )}
                        <button className="ck-btn ck-btn-ghost" onClick={downloadResult} disabled={!!busy}>
                          {busy === 'result' ? <LoaderCircle className="pi-spin" size={16} /> : <Download size={16} />}
                          Download result
                        </button>
                      </>
                    )}
                    {['DRAFT', 'REVIEW', 'FROZEN', 'PARTIAL', 'FAILED'].includes(batch.status) && (
                      <button className="ck-btn ck-btn-ghost pi-danger-action" onClick={cancelBatch} disabled={!!busy}>
                        {busy === 'cancel' ? <LoaderCircle className="pi-spin" size={16} /> : <XCircle size={16} />}
                        Cancel import
                      </button>
                    )}
                    <button className="ck-btn ck-btn-ghost" onClick={() => { setBatch(null); setRows([]); setAccess(null); }} disabled={!!busy}>
                      New batch
                    </button>
                  </div>
                </section>

                {(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(batch.status) || access?.overdue) && (
                  <section className={`pi-access-band ${access?.overdue ? 'overdue' : ''}`}>
                    <ShieldOff size={18} aria-hidden />
                    <div>
                      <strong>{access?.revokedAt
                        ? 'Photographer access closed'
                        : ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(batch.status)
                          ? 'Revoke photographer Drive access'
                          : 'Photographer access is overdue'}</strong>
                      <span>
                        {access?.revokedAt
                          ? `Recorded ${new Date(access.revokedAt).toLocaleString()}`
                          : access?.expiresAt
                            ? `Reminder due ${new Date(access.expiresAt).toLocaleDateString()}`
                            : 'Remove the photographer as an Editor after delivery.'}
                      </span>
                    </div>
                    {!access?.revokedAt && ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(batch.status) && (
                      <button className="ck-btn ck-btn-ghost" onClick={markAccessRevoked} disabled={!!busy}>
                        {busy === 'revoke' ? <LoaderCircle className="pi-spin" size={16} /> : <Check size={16} />}
                        Mark revoked
                      </button>
                    )}
                  </section>
                )}

                {rows.length > 0 && (
                  <section className="pi-review">
                    <div className="pi-review-toolbar">
                      <div>
                        <h2>Mapping review</h2>
                        <p>{visibleRows.length} of {rows.length} rows</p>
                      </div>
                      <div className="pi-segments" aria-label="Row status filter">
                        {FILTERS.map(value => (
                          <button
                            key={value}
                            className={filter === value ? 'active' : ''}
                            onClick={() => setFilter(value)}
                          >
                            {value}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className="pi-table-wrap">
                      <table className="pi-table">
                        <thead>
                          <tr>
                            <th>Row</th>
                            <th>Student</th>
                            <th>Class</th>
                            <th>Image mapping</th>
                            <th>Status</th>
                            <th aria-label="Preview" />
                          </tr>
                        </thead>
                        <tbody>
                          {visibleRows.map(row => (
                            <tr key={row.id}>
                              <td>{row.excelRow}</td>
                              <td>
                                <strong>{row.workbookName || '-'}</strong>
                                <span>Admission {row.admissionNo || '-'}</span>
                              </td>
                              <td>{row.className || '-'} / {row.sectionName || '-'}</td>
                              <td>
                                <strong>{row.imageNo ? `Image ${row.imageNo}` : 'No image number'}</strong>
                                <span>{row.driveFileName || row.message || 'Not mapped'}</span>
                              </td>
                              <td>
                                <span className={`pi-status ${statusTone(row.status)}`}>{row.status}</span>
                                {row.message && <small>{row.message}</small>}
                              </td>
                              <td>
                                <div className="pi-row-actions">
                                  <button
                                    className="pi-icon-button"
                                    aria-label={`Preview portrait for ${row.workbookName}`}
                                    title="Preview preserved photo"
                                    disabled={!row.driveFileName || busy === `preview:${row.id}`}
                                    onClick={() => openPreview(row)}
                                  >
                                    {busy === `preview:${row.id}`
                                      ? <LoaderCircle className="pi-spin" size={16} />
                                      : <Eye size={16} />}
                                  </button>
                                  {batch.status === 'REVIEW' && (
                                    <button
                                      className="pi-icon-button"
                                      aria-label={`Review mapping for ${row.workbookName}`}
                                      title="Review mapping"
                                      onClick={() => setEditing({
                                        row,
                                        admissionNo: row.admissionNo || '',
                                        imageNo: row.imageNo || '',
                                        excluded: row.status === 'EXCLUDED',
                                        cropX: row.cropX ?? 0.5,
                                        cropY: row.cropY ?? 0.5,
                                      })}
                                      disabled={!!busy}
                                    >
                                      <Pencil size={16} />
                                    </button>
                                  )}
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </section>
                )}
              </>
            )}

            {!batch && batches.length > 0 && (
              <section className="pi-history">
                <div className="pi-section-title">
                  <RefreshCw size={17} />
                  <div><h2>Recent batches</h2><p>{selectedSchool?.name}</p></div>
                </div>
                <div className="pi-history-list">
                  {batches.map(item => (
                    <button key={item.id} onClick={() => loadDetail(item.id)}>
                      <span>
                        <strong>{item.driveFolderName || 'Drive folder'}</strong>
                        <small>{item.academicYearLabel} / {item.id.slice(0, 8)}</small>
                        {!item.photographerAccessRevokedAt
                          && item.photographerAccessExpiresAt
                          && new Date(item.photographerAccessExpiresAt).getTime() < Date.now()
                          && <small className="pi-access-due">Drive access overdue</small>}
                      </span>
                      <span className={`pi-status ${statusTone(item.status)}`}>{item.status}</span>
                    </button>
                  ))}
                </div>
              </section>
            )}
          </>
        )}
      </div>

      {preview && (
        <div className="pi-preview-backdrop" role="presentation" onMouseDown={() => {
          URL.revokeObjectURL(preview.url);
          setPreview(null);
        }}>
          <div className="pi-preview-dialog" role="dialog" aria-modal="true" aria-label="Full-frame photo preview" onMouseDown={event => event.stopPropagation()}>
            <div>
              <strong>{preview.row.workbookName}</strong>
              <span>Admission {preview.row.admissionNo} / {preview.row.driveFileName}</span>
            </div>
            <img src={preview.url} alt={`Full-frame photo for ${preview.row.workbookName}`} />
            <button className="ck-btn ck-btn-ghost" onClick={() => {
              URL.revokeObjectURL(preview.url);
              setPreview(null);
            }}>Close</button>
          </div>
        </div>
      )}

      {editing && (
        <div className="pi-preview-backdrop" role="presentation" onMouseDown={() => setEditing(null)}>
          <div className="pi-review-dialog" role="dialog" aria-modal="true" aria-label="Review photo mapping" onMouseDown={event => event.stopPropagation()}>
            <div className="pi-dialog-head">
              <div>
                <strong>{editing.row.workbookName || 'Workbook row'}</strong>
                <span>Excel row {editing.row.excelRow}</span>
              </div>
              <button className="pi-icon-button" aria-label="Close review" onClick={() => setEditing(null)}>
                <XCircle size={16} />
              </button>
            </div>
            <div className="pi-review-fields">
              <label>
                <span>Admission number</span>
                <input type="text" value={editing.admissionNo} disabled={editing.excluded} onChange={event => setEditing(current => current && ({ ...current, admissionNo: event.target.value }))} />
              </label>
              <label>
                <span>Image number</span>
                <input type="text" value={editing.imageNo} disabled={editing.excluded} onChange={event => setEditing(current => current && ({ ...current, imageNo: event.target.value }))} />
              </label>
            </div>
            <label className="pi-exclude-control">
              <input type="checkbox" checked={editing.excluded} onChange={event => setEditing(current => current && ({ ...current, excluded: event.target.checked }))} />
              <Ban size={16} aria-hidden />
              <span>Exclude this row from the import</span>
            </label>
            <div className="ts">The complete source frame is preserved; no automatic crop is applied.</div>
            <div className="pi-dialog-actions">
              <button className="ck-btn ck-btn-ghost" onClick={() => setEditing(null)} disabled={!!busy}>Cancel</button>
              <button className="ck-btn ck-btn-ghost" onClick={() => saveRowReview(true)} disabled={!!busy || editing.excluded || !editing.imageNo.trim()}>
                <Eye size={16} /> Save and preview
              </button>
              <button className="ck-btn ck-btn-g" onClick={() => saveRowReview(false)} disabled={!!busy}>
                {busy === `edit:${editing.row.id}` ? <LoaderCircle className="pi-spin" size={16} /> : <Check size={16} />}
                Save review
              </button>
            </div>
          </div>
        </div>
      )}
    </ModuleShell>
  );
}
