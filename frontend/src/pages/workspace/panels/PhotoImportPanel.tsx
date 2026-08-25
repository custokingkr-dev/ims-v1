import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Check,
  Copy,
  ExternalLink,
  FolderCog,
  FolderSearch,
  Link2,
  LoaderCircle,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { PhotoImportDialogs } from './photo-import/PhotoImportDialogs';
import {
  PHOTO_IMPORT_REQUEST_CONFIG,
  PHOTO_RECOVERY_CHUNK_SIZE,
  type AccessState,
  type EditingState,
  type ImportBatch,
  type ImportContext,
  type ImportRow,
  type OperationProgress,
  type PreviewState,
  type RecoveryBatchResult,
  type RecoveryProgress,
  type RowFilter,
  duplicateSchoolNames,
  errorMessage,
  executionOperationProgress,
  isTimeoutError,
  schoolOptionLabel,
  statusTone,
} from './photo-import/model';

const PhotoImportBatchSummary = lazy(() => import('./photo-import/PhotoImportBatchSummary')
  .then(module => ({ default: module.PhotoImportBatchSummary })));
const PhotoImportReviewTable = lazy(() => import('./photo-import/PhotoImportReviewTable')
  .then(module => ({ default: module.PhotoImportReviewTable })));

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
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [executionConfirmed, setExecutionConfirmed] = useState(false);
  const [access, setAccess] = useState<AccessState | null>(null);
  const [recoveryProgress, setRecoveryProgress] = useState<RecoveryProgress | null>(null);
  const [operationProgress, setOperationProgress] = useState<OperationProgress | null>(null);
  const [editing, setEditing] = useState<EditingState | null>(null);

  const selectedSchool = context?.schools.find(school => school.id === Number(schoolId));
  const duplicateNames = useMemo(
    () => duplicateSchoolNames(context?.schools || []),
    [context?.schools],
  );
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

  const loadBatches = async (selectedId: number, signal?: AbortSignal) => {
    try {
      const response = await api.get<ImportBatch[]>('/student-photo-imports', {
        ...PHOTO_IMPORT_REQUEST_CONFIG,
        signal,
        params: { schoolId: selectedId },
      });
      if (signal?.aborted) return;
      setBatches(response.data || []);
    } catch (error) {
      if (signal?.aborted || (error as { code?: string })?.code === 'ERR_CANCELED') return;
      setNotice({ tone: 'bad', text: errorMessage(error) });
    }
  };

  const refreshDetail = async (id: string, options: { resetFilter?: boolean; showBusy?: boolean; showError?: boolean } = {}) => {
    if (options.showBusy) setBusy('detail');
    try {
      const response = await api.get<{
        batch: ImportBatch;
        rows: ImportRow[];
        access: AccessState;
        recoveryProgress?: RecoveryProgress;
      }>(
        `/student-photo-imports/${id}`,
        PHOTO_IMPORT_REQUEST_CONFIG,
      );
      setBatch(response.data.batch);
      setRows(response.data.rows || []);
      setAccess(response.data.access || null);
      if (response.data.recoveryProgress !== undefined) {
        setRecoveryProgress(response.data.recoveryProgress || null);
      }
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
    setRecoveryProgress(null);
    setOperationProgress(null);
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
    const controller = new AbortController();
    if (schoolId) void loadBatches(Number(schoolId), controller.signal);
    setBatch(null);
    setRows([]);
    setAccess(null);
    setRecoveryProgress(null);
    setOperationProgress(null);
    return () => controller.abort();
  }, [schoolId]);

  useEffect(() => {
    setExecutionConfirmed(false);
  }, [batch?.id, batch?.status]);

  useEffect(() => {
    setOperationProgress(null);
  }, [batch?.id]);

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
      setRecoveryProgress(null);
      setOperationProgress(null);
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
    setOperationProgress(action === 'execute'
      ? executionOperationProgress(batch)
      : {
          label: action === 'scan' ? 'Scanning Drive source' : 'Freezing source snapshot',
          detail: action === 'scan'
            ? 'Validating the workbook, image matches, and source checksums…'
            : 'Confirming the reviewed files have not changed…',
        });
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
      if (action === 'execute') setOperationProgress(executionOperationProgress(current));
      while (action === 'execute' && current.status === 'EXECUTING') {
        setBatch(current);
        try {
          current = (await postAction()).data;
        } catch (error) {
          if (!isTimeoutError(error)) throw error;
          current = await refreshDetail(batch.id, { showError: false });
        }
        setOperationProgress(executionOperationProgress(current));
      }
      await loadDetail(batch.id);
      if (action === 'execute') setOperationProgress(executionOperationProgress(current));
      await loadBatches(batch.schoolId);
      setNotice({
        tone: 'ok',
        text: action === 'scan'
          ? 'Drive and workbook scan completed.'
          : action === 'freeze'
            ? 'Source snapshot frozen. The batch is ready for execution.'
            : 'Execution finished. Review the applied and failed totals.',
      });
      if (action !== 'execute') {
        setOperationProgress({
          label: action === 'scan' ? 'Drive scan complete' : 'Source snapshot frozen',
          detail: action === 'scan'
            ? 'Workbook and image validation results are ready for review.'
            : 'The reviewed source is locked and ready to execute.',
          value: 100,
          valueLabel: '100%',
          tone: 'complete',
        });
      }
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
      if (action === 'execute') await loadDetail(batch.id);
      setOperationProgress(current => current ? {
        ...current,
        detail: `The operation stopped: ${errorMessage(error)}`,
        tone: 'error',
      } : null);
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
    setOperationProgress({
      label: 'Downloading reconciliation result',
      detail: 'Preparing the batch CSV…',
    });
    try {
      const response = await api.get(`/student-photo-imports/${batch.id}/result`, {
        responseType: 'blob',
        onDownloadProgress: event => {
          const value = event.total ? (event.loaded / event.total) * 100 : undefined;
          setOperationProgress({
            label: 'Downloading reconciliation result',
            detail: event.total
              ? `${event.loaded.toLocaleString()} of ${event.total.toLocaleString()} bytes received.`
              : `${event.loaded.toLocaleString()} bytes received; final size is not known yet.`,
            value,
            valueLabel: value == null ? `${event.loaded.toLocaleString()} bytes` : `${Math.round(value)}%`,
          });
        },
      });
      const url = URL.createObjectURL(response.data);
      const link = document.createElement('a');
      link.href = url;
      link.download = `student-photo-import-${batch.id}.csv`;
      link.click();
      URL.revokeObjectURL(url);
      setOperationProgress({
        label: 'Reconciliation result downloaded',
        detail: 'The batch CSV is ready in your downloads.',
        value: 100,
        valueLabel: '100%',
        tone: 'complete',
      });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
      setOperationProgress({
        label: 'Result download stopped',
        detail: errorMessage(error),
        tone: 'error',
      });
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
    setOperationProgress(null);
    setRecoveryProgress(current => current || {
      totalCount: appliedRows.length,
      processedCount: 0,
      recoveredCount: 0,
      protectedCount: 0,
      failedCount: 0,
      inProgressCount: 0,
      pendingCount: appliedRows.length,
      percentComplete: 0,
      resumable: true,
    });
    try {
      const totals = {
        recovered: 0,
        alreadyRecovered: 0,
        inProgress: 0,
        protected: 0,
        failed: 0,
      };
      for (let offset = 0; offset < appliedRows.length; offset += PHOTO_RECOVERY_CHUNK_SIZE) {
        const rowIds = appliedRows
          .slice(offset, offset + PHOTO_RECOVERY_CHUNK_SIZE)
          .map(row => row.id);
        const response = await api.post<RecoveryBatchResult>(
          `/student-photo-imports/${batch.id}/recover`,
          { rowIds },
          PHOTO_IMPORT_REQUEST_CONFIG,
        );
        totals.recovered += response.data.recoveredCount;
        totals.alreadyRecovered += response.data.alreadyRecoveredCount;
        totals.inProgress += response.data.inProgressCount;
        totals.protected += response.data.protectedCount || 0;
        totals.failed += response.data.failedCount;
        const locallyProcessed = Math.min(offset + rowIds.length, appliedRows.length);
        setRecoveryProgress(response.data.progress || {
          totalCount: appliedRows.length,
          processedCount: locallyProcessed,
          recoveredCount: totals.recovered + totals.alreadyRecovered,
          protectedCount: totals.protected,
          failedCount: totals.failed,
          inProgressCount: totals.inProgress,
          pendingCount: Math.max(0, appliedRows.length - locallyProcessed),
          percentComplete: Math.round(locallyProcessed * 100 / appliedRows.length),
          resumable: locallyProcessed < appliedRows.length || totals.failed > 0,
        });
      }

      await refreshDetail(batch.id, { showError: false });
      const successful = totals.recovered + totals.alreadyRecovered;
      const requiresAttention = totals.failed + totals.inProgress;
      setNotice({
        tone: requiresAttention > 0 ? 'bad' : 'ok',
        text: requiresAttention > 0
          ? `Full-frame recovery completed for ${successful} photo${successful === 1 ? '' : 's'}; `
            + `${totals.protected} newer photo${totals.protected === 1 ? ' was' : 's were'} protected, `
            + `${totals.failed} failed, and ${totals.inProgress} `
            + `${totals.inProgress === 1 ? 'is' : 'are'} already in progress. `
            + 'Only failed rows need Drive/source review; in-progress rows can be resumed shortly.'
          : `Full-frame recovery completed for ${successful} photo${successful === 1 ? '' : 's'}. `
            + (totals.protected > 0
              ? `${totals.protected} newer photo${totals.protected === 1 ? ' was' : 's were'} kept untouched.`
              : 'No newer photo changes were overwritten.'),
      });
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
      setRecoveryProgress(current => current ? { ...current, resumable: true } : current);
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

  const closePreview = () => {
    if (preview) URL.revokeObjectURL(preview.url);
    setPreview(null);
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
      <div className="pi-workspace" aria-busy={!!busy}>
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
              <Suspense fallback={<div className="pi-loading"><LoaderCircle className="pi-spin" size={18} /> Loading batch workspace…</div>}>
                <PhotoImportBatchSummary
                  batch={batch}
                  appliedRowCount={appliedRows.length}
                  busy={busy}
                  executionConfirmed={executionConfirmed}
                  operationProgress={operationProgress}
                  recoveryProgress={recoveryProgress}
                  access={access}
                  onExecutionConfirmedChange={setExecutionConfirmed}
                  onRunAction={action => { void runAction(action); }}
                  onRecover={() => { void recoverAppliedPhotos(); }}
                  onDownloadResult={() => { void downloadResult(); }}
                  onCancel={() => { void cancelBatch(); }}
                  onMarkAccessRevoked={() => { void markAccessRevoked(); }}
                  onNewBatch={() => {
                    setBatch(null);
                    setRows([]);
                    setAccess(null);
                    setRecoveryProgress(null);
                    setOperationProgress(null);
                  }}
                />
                {rows.length > 0 && (
                  <PhotoImportReviewTable
                    batch={batch}
                    rows={rows}
                    visibleRows={visibleRows}
                    filter={filter}
                    busy={busy}
                    onFilterChange={setFilter}
                    onOpenPreview={row => { void openPreview(row); }}
                    onEdit={setEditing}
                  />
                )}
              </Suspense>
            )}

            {!batch && batches.length > 0 && (
              <section className="pi-history">
                <div className="pi-section-title">
                  <RefreshCw size={17} />
                  <div><h2>Recent batches</h2><p>{selectedSchool?.name}</p></div>
                </div>
                <div className="pi-history-list">
                  {batches.map(item => (
                    <button key={item.id} onClick={() => loadDetail(item.id)} disabled={!!busy}>
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

      <PhotoImportDialogs
        preview={preview}
        editing={editing}
        busy={busy}
        onClosePreview={closePreview}
        onEditingChange={setEditing}
        onSaveReview={previewAfterSave => { void saveRowReview(previewAfterSave); }}
      />
    </ModuleShell>
  );
}
