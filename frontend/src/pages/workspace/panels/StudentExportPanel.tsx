import { useEffect, useMemo, useState } from 'react';
import { Download, FileSpreadsheet, Images, LoaderCircle, ShieldCheck } from 'lucide-react';
import api from '../../../services/api';
import { downloadStudentExport } from '../../../features/students';
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

export function StudentExportPanel() {
  const [context, setContext] = useState<ExportContext | null>(null);
  const [schoolId, setSchoolId] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
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

  const download = async () => {
    if (!selected || downloading) return;
    setDownloading(true);
    setNotice(null);
    try {
      const result = await downloadStudentExport(selected.id, safeArchiveName(selected.shortCode));
      if (result === 'saved') {
        setNotice({
          tone: 'ok',
          text: `Downloaded ${selected.name}: ${selected.studentCount.toLocaleString()} student rows and up to ${selected.photoCount.toLocaleString()} photos.`,
        });
      }
    } catch (error) {
      setNotice({ tone: 'bad', text: errorMessage(error) });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <ModuleShell
      title="Student details & photos"
      subtitle="Download one assigned school's current student records and portraits for ID-card production."
    >
      <div className="ck-card" style={{ maxWidth: 820 }}>
        <div className="ck-card-h">
          <div>
            <div className="ck-card-t">Assigned-school export</div>
            <div className="ck-card-sub">School access is checked again by the server when the archive is requested.</div>
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
                <select aria-label="School" value={schoolId} onChange={event => setSchoolId(event.target.value ? Number(event.target.value) : '')}>
                  <option value="">Select an assigned school</option>
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
            </>
          ) : (
            <div>No active schools are assigned to this operator. Ask a Superadmin to add an operator-school assignment.</div>
          )}
          {notice ? (
            <div role="status" style={{ color: notice.tone === 'ok' ? '#087443' : '#b42318' }}>{notice.text}</div>
          ) : null}
        </div>
      </div>
    </ModuleShell>
  );
}
