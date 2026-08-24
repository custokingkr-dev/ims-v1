import { useEffect, useMemo, useState } from 'react';
import { Download, FileSpreadsheet, Images, LoaderCircle, ShieldCheck } from 'lucide-react';
import api from '../../../services/api';
import { downloadStudentExport, type StudentExportProgress } from '../../../features/students';
import { TransferProgress } from '../../../components/TransferProgress';
import { Field, ModuleShell } from '../ui';

interface ExportSchool {
  id: number;
  name: string;
  shortCode: string;
  studentCount: number;
  photoCount: number;
}

interface ExportContext {
  schools: ExportSchool[];
  fileNameRule: string;
  workbookFileName: string;
}

function safeArchiveName(shortCode: string): string {
  const code = shortCode.toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/(^-+|-+$)/g, '') || 'school';
  return `${code}-student-details-and-photos-${new Date().toISOString().slice(0, 10)}.zip`;
}

function errorMessage(error: any): string {
  return error?.response?.data?.message || error?.message || 'Unable to prepare the student export.';
}

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(0, bytes / 1024).toFixed(bytes >= 1024 ? 1 : 0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function StudentExportPanel() {
  const [context, setContext] = useState<ExportContext | null>(null);
  const [schoolId, setSchoolId] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [exportProgress, setExportProgress] = useState<StudentExportProgress | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'bad'; text: string } | null>(null);

  useEffect(() => {
    let active = true;
    api.get<ExportContext>('/students/export/context')
      .then(response => {
        if (!active) return;
        setContext(response.data);
        if (response.data.schools.length === 1) setSchoolId(response.data.schools[0].id);
      })
      .catch(error => active && setNotice({ tone: 'bad', text: errorMessage(error) }))
      .finally(() => active && setLoading(false));
    return () => { active = false; };
  }, []);

  const selected = useMemo(
    () => context?.schools.find(school => school.id === Number(schoolId)),
    [context?.schools, schoolId],
  );
  const missingPhotos = selected ? Math.max(0, selected.studentCount - selected.photoCount) : 0;
  const exportPercent = exportProgress?.percent
    ?? (exportProgress?.totalBytes
      ? (exportProgress.loadedBytes / exportProgress.totalBytes) * 100
      : exportProgress?.phase === 'saving' && !downloading ? 100 : undefined);

  const progressLabel = exportProgress?.serverPhase === 'PHOTOS'
    ? 'Packing student photos'
    : exportProgress?.serverPhase === 'WORKBOOK' ? 'Building Excel workbook'
      : exportProgress?.serverPhase === 'FINALIZING' ? 'Finalizing secure archive'
        : exportProgress?.serverPhase === 'COMPLETED' ? 'Student export complete'
          : exportProgress?.serverPhase === 'FAILED' ? 'Student export stopped'
          : exportProgress?.phase === 'preparing' ? 'Preparing secure archive'
            : exportProgress?.phase === 'downloading' ? 'Downloading archive' : 'Saving archive';

  const download = async () => {
    if (!selected || downloading) return;
    setDownloading(true);
    setExportProgress({ phase: 'preparing', loadedBytes: 0 });
    setNotice(null);
    try {
      const result = await downloadStudentExport(
        selected.id,
        safeArchiveName(selected.shortCode),
        progress => setExportProgress(progress),
      );
      if (result === 'saved') {
        setExportProgress(current => ({
          phase: 'saving',
          loadedBytes: current?.totalBytes || current?.loadedBytes || 0,
          totalBytes: current?.totalBytes,
          percent: 100,
          serverPhase: 'COMPLETED',
          processedStudents: current?.totalStudents,
          totalStudents: current?.totalStudents,
          exportedPhotos: current?.exportedPhotos,
          missingPhotos: current?.missingPhotos,
        }));
        setNotice({
          tone: 'ok',
          text: `Downloaded ${selected.name}: ${selected.studentCount.toLocaleString()} student rows and up to ${selected.photoCount.toLocaleString()} photos.`,
        });
      } else {
        setExportProgress(null);
      }
    } catch (error) {
      setExportProgress(current => current ? { ...current, serverPhase: 'FAILED' } : null);
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <ModuleShell
      title="Student details & photos"
      subtitle="Download a school's current student records and portraits for ID-card production."
    >
      <div className="ck-card" style={{ maxWidth: 820 }}>
        <div className="ck-card-h">
          <div>
            <div className="ck-card-t">School export</div>
            <div className="ck-card-sub">Your school access is checked again by the server when the archive is requested.</div>
          </div>
          <ShieldCheck size={24} aria-hidden="true" />
        </div>
        <div className="ck-form-body" style={{ display: 'grid', gap: 18 }}>
          {loading ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <LoaderCircle className="spin" size={18} /> Loading assigned schools…
            </div>
          ) : context?.schools.length ? (
            <>
              <Field label="School">
                <select aria-label="School" value={schoolId} onChange={event => {
                  setSchoolId(event.target.value ? Number(event.target.value) : '');
                  setExportProgress(null);
                  setNotice(null);
                }}>
                  <option value="">Select a school</option>
                  {context.schools.map(school => (
                    <option key={school.id} value={school.id}>
                      {school.name} ({school.shortCode})
                    </option>
                  ))}
                </select>
              </Field>

              {selected ? (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
                  <div className="ck-card" style={{ padding: 14, boxShadow: 'none' }}>
                    <FileSpreadsheet size={20} aria-hidden="true" />
                    <div className="ck-card-t" style={{ marginTop: 8 }}>{selected.studentCount.toLocaleString()}</div>
                    <div className="ck-card-sub">Student rows in Excel</div>
                  </div>
                  <div className="ck-card" style={{ padding: 14, boxShadow: 'none' }}>
                    <Images size={20} aria-hidden="true" />
                    <div className="ck-card-t" style={{ marginTop: 8 }}>{selected.photoCount.toLocaleString()}</div>
                    <div className="ck-card-sub">Photos currently mapped</div>
                  </div>
                  <div className="ck-card" style={{ padding: 14, boxShadow: 'none' }}>
                    <Images size={20} aria-hidden="true" />
                    <div className="ck-card-t" style={{ marginTop: 8 }}>{missingPhotos.toLocaleString()}</div>
                    <div className="ck-card-sub">Rows without a mapped photo</div>
                  </div>
                </div>
              ) : null}

              <div className="ts" style={{ lineHeight: 1.6 }}>
                The ZIP contains <strong>{context.workbookFileName}</strong> and a <strong>photos</strong> folder.
                {' '}{context.fileNameRule} Use Chrome or Edge for large schools so the file streams directly to disk.
              </div>
              <div>
                <button type="button" className="ck-btn ck-btn-primary" disabled={!selected || downloading} onClick={download}>
                  {downloading ? <LoaderCircle className="spin" size={16} /> : <Download size={16} />}
                  {downloading ? 'Preparing and downloading…' : 'Download Excel and all photos'}
                </button>
              </div>
              {exportProgress ? (
                <TransferProgress
                  label={progressLabel}
                  value={exportPercent}
                  valueLabel={exportPercent == null ? undefined : `${Math.round(exportPercent)}%`}
                  detail={exportProgress.serverPhase === 'PHOTOS' && exportProgress.totalStudents
                    ? `${exportProgress.processedStudents?.toLocaleString() || 0} of ${exportProgress.totalStudents.toLocaleString()} student records packed; `
                      + `${exportProgress.exportedPhotos?.toLocaleString() || 0} photos added and ${exportProgress.missingPhotos?.toLocaleString() || 0} missing.`
                    : exportProgress.serverPhase === 'WORKBOOK'
                      ? 'Photos are packed. Building the student-details workbook…'
                      : exportProgress.serverPhase === 'FINALIZING'
                        ? 'Closing the ZIP archive and verifying its final entries…'
                        : exportProgress.phase === 'preparing'
                          ? `Gathering ${selected?.studentCount.toLocaleString() || 0} rows and up to ${selected?.photoCount.toLocaleString() || 0} photos…`
                          : exportProgress.phase === 'downloading'
                      ? exportProgress.totalBytes
                        ? `${formatBytes(exportProgress.loadedBytes)} of ${formatBytes(exportProgress.totalBytes)} transferred directly to your selected file.`
                        : 'The server is streaming the archive directly to your selected file; its final size is not known yet.'
                      : downloading ? 'Finishing the ZIP file on disk…' : 'The ZIP file is ready on disk.'}
                  tone={exportProgress.serverPhase === 'FAILED'
                    ? 'error'
                    : !downloading && notice?.tone === 'ok' ? 'complete' : 'active'}
                />
              ) : null}
            </>
          ) : (
            <div>No active schools are available for export. Operators should ask a Superadmin to check their school assignments.</div>
          )}
          {notice ? (
            <div role="status" style={{ color: notice.tone === 'ok' ? '#087443' : '#b42318' }}>{notice.text}</div>
          ) : null}
        </div>
      </div>
    </ModuleShell>
  );
}
