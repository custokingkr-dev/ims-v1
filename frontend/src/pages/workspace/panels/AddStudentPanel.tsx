import { DragEvent, useEffect, useRef, useState } from 'react';
import { studentClient } from '../../../services/studentApi';
import api from '../../../services/api';
import {
  emptyStudentProfileForm,
  STUDENT_PHOTO_MAX_LABEL,
  studentProfileFormToCreatePayload,
  type StudentClassOption,
  type StudentProfileFormState,
  type StudentSectionOption,
  validateStudentPhotoFile,
} from '../../../features/students';
import { ModuleShell, Field } from '../ui';
import type { PanelKey } from '../config';
import { StudentProfileForm } from './StudentProfileForm';
import { StudentModuleTabs } from './StudentModuleTabs';
import { useDraftDirty, useWorkspaceDraft } from '../WorkspaceDrafts';
import {
  Check,
  FileSpreadsheet,
  GraduationCap,
  ImagePlus,
  MapPin,
  RotateCcw,
  Save,
  ShieldCheck,
  UserRound,
  UsersRound,
} from 'lucide-react';

interface Props {
  setPanel: (key: PanelKey) => void;
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
  canImportStudents?: boolean;
}

export function AddStudentPanel({ setPanel, onRefresh, schoolScopedParams, canImportStudents = true }: Props) {
  const [studentForm, setStudentForm] = useWorkspaceDraft<StudentProfileFormState>('admission.form', emptyStudentProfileForm);
  const [createdStudentId, setCreatedStudentId] = useWorkspaceDraft<number | null>('admission.createdId', null);
  const [dirty, setDirty] = useWorkspaceDraft('admission.dirty', false);
  const clearDraftDirty = useDraftDirty('admission', dirty);
  const [saving, setSaving] = useWorkspaceDraft('admission.saving', false);
  const [photoFile, setPhotoFile] = useWorkspaceDraft<File | null>('admission.photo', null);
  const [photoBitmap, setPhotoBitmap] = useState<ImageBitmap | null>(null);
  const [photoError, setPhotoError] = useState('');
  const [photoFeedback, setPhotoFeedback] = useWorkspaceDraft<{ type: 'success' | 'error'; message: string } | null>('admission.feedback', null);
  const [photoDragActive, setPhotoDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const photoPreviewCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const photoBitmapRef = useRef<ImageBitmap | null>(null);
  const photoSelectionRef = useRef(0);
  const [classes, setClasses] = useState<StudentClassOption[]>([]);
  const [sections, setSections] = useState<StudentSectionOption[]>([]);
  const [activeSection, setActiveSection] = useState('student-form-details');
  const schoolId = schoolScopedParams?.schoolId;

  useEffect(() => { if (photoFile) void selectPhoto(photoFile); }, []);

  useEffect(() => {
    let alive = true;
    void api.get<StudentClassOption[]>('/classes', { params: schoolScopedParams })
      .then((res) => { if (alive) setClasses(Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setClasses([]); });
    return () => { alive = false; };
  }, [schoolId]);

  useEffect(() => {
    if (classes.length === 0) return;
    setStudentForm((prev) =>
      classes.some((c) => c.id === prev.classId)
        ? prev
        : { ...prev, classId: classes[0].id, sectionId: '' });
  }, [classes]);

  useEffect(() => {
    if (!studentForm.classId) {
      setSections([]);
      return;
    }
    let alive = true;
    void api.get<StudentSectionOption[]>(
      `/classes/${encodeURIComponent(studentForm.classId)}/sections`,
      { params: { ...(schoolScopedParams || {}), active: true } },
    )
      .then((res) => {
        if (!alive) return;
        const options = Array.isArray(res.data) ? res.data : [];
        setSections(options);
        setStudentForm(previous => options.some(section => section.id === previous.sectionId) ? previous : { ...previous, sectionId: options[0]?.id || '' });
      })
      .catch(() => { if (alive) setSections([]); });
    return () => { alive = false; };
  }, [studentForm.classId, schoolId]);

  useEffect(() => {
    const canvas = photoPreviewCanvasRef.current;
    if (!canvas || !photoBitmap) return;
    const context = canvas.getContext('2d');
    if (!context) return;

    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    const baseScale = Math.min(canvas.width / photoBitmap.width, canvas.height / photoBitmap.height);
    const drawWidth = photoBitmap.width * baseScale;
    const drawHeight = photoBitmap.height * baseScale;
    context.drawImage(
      photoBitmap,
      (canvas.width - drawWidth) / 2,
      (canvas.height - drawHeight) / 2,
      drawWidth,
      drawHeight,
    );
  }, [photoBitmap]);

  useEffect(() => () => {
    photoSelectionRef.current += 1;
    photoBitmapRef.current?.close();
    photoBitmapRef.current = null;
  }, []);

  const updateStudentForm = (patch: Partial<StudentProfileFormState>) => {
    setDirty(true);
    setStudentForm((prev) => ({ ...prev, ...patch }));
  };

  const onClassChange = (classId: string) => {
    setDirty(true);
    setStudentForm((prev) => ({ ...prev, classId, sectionId: '' }));
  };

  const resetPhotoState = () => {
    photoSelectionRef.current += 1;
    photoBitmapRef.current?.close();
    photoBitmapRef.current = null;
    setPhotoBitmap(null);
    setPhotoFile(null);
    setPhotoError('');
    setPhotoDragActive(false);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const resetStudentForm = () => {
    setStudentForm(emptyStudentProfileForm());
    resetPhotoState();
    setPhotoFeedback(null);
    setCreatedStudentId(null);
    setDirty(false);
    clearDraftDirty();
  };

  const validateImageFile = (file: File) => {
    const error = validateStudentPhotoFile(file);
    if (error) throw new Error(error);
  };

  const selectPhoto = async (file: File) => {
    const selection = photoSelectionRef.current + 1;
    photoSelectionRef.current = selection;
    try {
      validateImageFile(file);
      const decoded = await createImageBitmap(file);
      if (selection !== photoSelectionRef.current) {
        decoded.close();
        return;
      }
      if (decoded.width < 1 || decoded.height < 1) {
        decoded.close();
        throw new Error('The selected photo could not be decoded.');
      }
      photoBitmapRef.current?.close();
      photoBitmapRef.current = decoded;
      setPhotoBitmap(decoded);
      setPhotoFile(file);
      setDirty(true);
      setPhotoError('');
      if (!createdStudentId) setPhotoFeedback(null);
    } catch (err: unknown) {
      if (selection !== photoSelectionRef.current) return;
      photoBitmapRef.current?.close();
      photoBitmapRef.current = null;
      setPhotoBitmap(null);
      setPhotoFile(null);
      setPhotoError(err instanceof Error ? err.message : 'Invalid photo file.');
    }
  };

  const handlePhotoDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setPhotoDragActive(false);
    if (saving) return;
    const file = event.dataTransfer.files?.[0];
    if (file) void selectPhoto(file);
  };

  const handleSaveStudent = async () => {
    if (saving) return;
    let savedId = createdStudentId;
    try {
      setSaving(true);
      setPhotoError('');
      setPhotoFeedback(null);
      if (!savedId) {
        if (!studentForm.admissionNumber.trim() || !studentForm.fullName.trim()) {
          throw new Error('Admission number and full name are required.');
        }
        if (!studentForm.classId || !studentForm.sectionId) {
          throw new Error('Class and section are required.');
        }
        const studentResponse = await studentClient.createStudent({ ...studentProfileFormToCreatePayload(studentForm), ...(schoolScopedParams || {}) });
        savedId = studentResponse.id ?? null;
        if (!savedId) throw new Error('The save response did not include a student ID. Check the student list before trying again.');
        setCreatedStudentId(savedId);
      }
      if (photoFile) {
        await studentClient.uploadStudentPhoto({ id: savedId }, { file: photoFile });
        setPhotoFeedback({ type: 'success', message: 'Student saved and photo uploaded successfully.' });
      } else {
        setPhotoFeedback({ type: 'success', message: 'Student saved successfully.' });
      }
      // Refreshing the dashboard is independent of the committed admission.
      void Promise.resolve().then(onRefresh).catch(() => undefined);
      resetStudentForm();
      setPanel('students');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Unable to save student.');
      setPhotoFeedback({ type: 'error', message: savedId ? `Student saved (record ${savedId}). The photo could not be uploaded: ${msg} Retry the photo upload or continue to the student list.` : msg });
    } finally {
      setSaving(false);
    }
  };

  const requiredFields = [
    studentForm.admissionNumber,
    studentForm.fullName,
    studentForm.classId,
    studentForm.sectionId,
  ];
  const completedRequiredFields = requiredFields.filter((value) => value.trim()).length;
  const selectedClass = classes.find((item) => item.id === studentForm.classId)?.name || 'Not selected';
  const selectedSection = sections.find((item) => item.id === studentForm.sectionId)?.name || 'Not selected';
  const navigateToSection = (id: string) => {
    setActiveSection(id);
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <ModuleShell
      title="Add student"
      subtitle="Create a complete, reliable student record for the current school"
      actions={canImportStudents ? (
        <button className="ck-btn ck-btn-ghost ck-icon-label ck-student-workflow-action" onClick={() => setPanel('bulkimport')}>
          <FileSpreadsheet size={15} aria-hidden="true" />Add many students
        </button>
      ) : null}
    >
      <StudentModuleTabs active="addstudent" setPanel={setPanel} canImport={canImportStudents} />

      {photoFeedback ? (
        <div role={photoFeedback.type === 'error' ? 'alert' : 'status'} className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}>
          <span>{photoFeedback.type === 'success' ? <Check size={16} /> : '!'}</span>
          <div>{photoFeedback.message}</div>
        </div>
      ) : null}
      {dirty && <p className="ts" role="status">Your unfinished admission stays available while you move between workspace panels. Save it before refreshing or signing out.</p>}
      {createdStudentId && <button className="ck-btn ck-btn-ghost" disabled={saving} onClick={() => { resetStudentForm(); setPanel('students'); }}>Continue to student list without photo</button>}

      <div className="ck-admission-layout">
        <aside className="ck-admission-rail" aria-label="Student form sections">
          <div className="ck-admission-rail-head">
            <strong>New admission</strong>
            <span>{completedRequiredFields} of 4 required fields complete</span>
          </div>
          {[
            { id: 'student-form-details', label: 'Student details', sub: 'Identity and admission', icon: UserRound },
            { id: 'student-form-academic', label: 'Academic details', sub: 'Class and section', icon: GraduationCap },
            { id: 'student-form-guardian', label: 'Parent / guardian', sub: 'Primary contacts', icon: UsersRound },
            { id: 'student-form-address', label: 'Address', sub: 'Home location', icon: MapPin },
          ].map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                type="button"
                className={activeSection === item.id ? 'on' : ''}
                onClick={() => navigateToSection(item.id)}
              >
                <span><Icon size={16} aria-hidden="true" /></span>
                <span><strong>{item.label}</strong><small>{item.sub}</small></span>
              </button>
            );
          })}
        </aside>

        <div className="ck-form-card ck-admission-form-card">
          <div className="ck-form-head">
            <div>
              <strong>Student profile</strong>
              <div className="ck-card-sub">Fields marked with * are required before enrollment.</div>
            </div>
            <span className="ck-status sgr">{createdStudentId ? 'Student saved · photo pending' : 'Draft'}</span>
          </div>
          <div className="ck-form-body">
            <fieldset disabled={saving || !!createdStudentId} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
            <StudentProfileForm
              form={studentForm}
              classes={classes}
              sections={sections}
              onChange={updateStudentForm}
              onClassChange={onClassChange}
            />
            </fieldset>

            <div className="ck-photo-panel">
              <div className="ck-photo-panel-copy">
                <h3>Student profile photo</h3>
                <p>Upload a clear face photo. JPG, PNG, or WEBP up to {STUDENT_PHOTO_MAX_LABEL}.</p>
              </div>
              <input ref={fileInputRef} type="file" disabled={saving} accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) void selectPhoto(file); }} />
              <div className={`ck-photo-dropzone ${photoDragActive ? 'drag' : ''} ${photoBitmap ? 'has-image' : ''}`} onDragOver={(e) => { e.preventDefault(); setPhotoDragActive(true); }} onDragLeave={() => setPhotoDragActive(false)} onDrop={handlePhotoDrop}>
                <div className="ck-photo-drop-icon"><ImagePlus size={24} aria-hidden="true" /></div>
                <div className="ck-photo-drop-title">Drop the student photo here</div>
                <div className="ck-photo-drop-sub">The complete photo frame is preserved when it is saved.</div>
                <div className="ck-actions-inline">
                  <button type="button" className="ck-btn ck-btn-g ck-icon-label" disabled={saving} onClick={() => fileInputRef.current?.click()}><ImagePlus size={15} />Choose photo</button>
                  {photoFile ? <button type="button" className="ck-btn ck-btn-ghost" disabled={saving} onClick={resetPhotoState}>Remove</button> : null}
                </div>
              </div>
              {photoError ? <div className="ck-photo-error">{photoError}</div> : null}
              {photoBitmap ? (
                <div className="ck-photo-editor">
                  <div>
                    <div className="ck-photo-frame">
                      <canvas ref={photoPreviewCanvasRef} width="512" height="512" role="img" aria-label="Student preview" className="ck-photo-preview-image" />
                    </div>
                    <div className="ck-photo-help">Photo preview</div>
                  </div>
                  <div className="ck-photo-controls ts">Full-frame preview; no automatic crop is applied.</div>
                </div>
              ) : null}
            </div>

            <div className="ck-admission-footer">
              <button className="ck-btn ck-btn-ghost ck-icon-label" type="button" disabled={saving} onClick={() => { if (!dirty || window.confirm('Clear this unfinished admission? Any already saved student record will remain.')) resetStudentForm(); }}>
                <RotateCcw size={15} aria-hidden="true" />Clear form
              </button>
              <button className="ck-btn ck-btn-g ck-icon-label" disabled={saving} onClick={() => void handleSaveStudent()}>
                <Save size={15} aria-hidden="true" />{saving ? 'Saving...' : createdStudentId ? photoFile ? 'Retry photo upload' : 'Finish without photo' : 'Save & enroll student'}
              </button>
            </div>
          </div>
        </div>

        <aside className="ck-admission-summary">
          <div className="ck-admission-summary-card">
            <h3><ShieldCheck size={17} aria-hidden="true" />Record summary</h3>
            <dl>
              <div><dt>Admission no.</dt><dd>{studentForm.admissionNumber || 'Not entered'}</dd></div>
              <div><dt>Student</dt><dd>{studentForm.fullName || 'Not entered'}</dd></div>
              <div><dt>Class</dt><dd>{selectedClass}</dd></div>
              <div><dt>Section</dt><dd>{selectedSection}</dd></div>
              <div><dt>Guardian</dt><dd>{studentForm.fatherName || studentForm.motherName || 'Not entered'}</dd></div>
            </dl>
          </div>
          <div className="ck-admission-summary-card">
            <h3><Check size={17} aria-hidden="true" />Before you save</h3>
            <ul>
              <li>Use the name shown on official records.</li>
              <li>Confirm class and section placement.</li>
              <li>Check the guardian's primary mobile number.</li>
            </ul>
          </div>
        </aside>
      </div>
    </ModuleShell>
  );
}
