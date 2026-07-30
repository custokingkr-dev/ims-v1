import { DragEvent, useEffect, useRef, useState } from 'react';
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
  const [studentForm, setStudentForm] = useState<StudentProfileFormState>(emptyStudentProfileForm());
  const [saving, setSaving] = useState(false);
  const [photoFile, setPhotoFile] = useState<File | null>(null);
  const [photoPreviewUrl, setPhotoPreviewUrl] = useState('');
  const [photoError, setPhotoError] = useState('');
  const [photoFeedback, setPhotoFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [photoDragActive, setPhotoDragActive] = useState(false);
  const [photoZoom, setPhotoZoom] = useState(1);
  const [photoOffsetX, setPhotoOffsetX] = useState(0);
  const [photoOffsetY, setPhotoOffsetY] = useState(0);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [classes, setClasses] = useState<StudentClassOption[]>([]);
  const [sections, setSections] = useState<StudentSectionOption[]>([]);
  const [activeSection, setActiveSection] = useState('student-form-details');
  const schoolId = schoolScopedParams?.schoolId;

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
      .then((res) => { if (alive) setSections(Array.isArray(res.data) ? res.data : []); })
      .catch(() => { if (alive) setSections([]); });
    return () => { alive = false; };
  }, [studentForm.classId, schoolId]);

  useEffect(() => {
    setStudentForm((prev) => {
      if (sections.length === 0) return prev.sectionId ? { ...prev, sectionId: '' } : prev;
      return sections.some((s) => s.id === prev.sectionId)
        ? prev
        : { ...prev, sectionId: sections[0].id };
    });
  }, [sections]);

  const updateStudentForm = (patch: Partial<StudentProfileFormState>) => {
    setStudentForm((prev) => ({ ...prev, ...patch }));
  };

  const onClassChange = (classId: string) => {
    setStudentForm((prev) => ({ ...prev, classId, sectionId: '' }));
  };

  const resetPhotoState = () => {
    setPhotoFile(null);
    if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
    setPhotoPreviewUrl('');
    setPhotoError('');
    setPhotoDragActive(false);
    setPhotoZoom(1);
    setPhotoOffsetX(0);
    setPhotoOffsetY(0);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const resetStudentForm = () => {
    setStudentForm(emptyStudentProfileForm());
    resetPhotoState();
    setPhotoFeedback(null);
  };

  const validateImageFile = (file: File) => {
    const error = validateStudentPhotoFile(file);
    if (error) throw new Error(error);
  };

  const selectPhoto = (file: File) => {
    try {
      validateImageFile(file);
      if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
      setPhotoFile(file);
      setPhotoPreviewUrl(URL.createObjectURL(file));
      setPhotoError('');
      setPhotoFeedback(null);
      setPhotoZoom(1);
      setPhotoOffsetX(0);
      setPhotoOffsetY(0);
    } catch (err: unknown) {
      setPhotoFile(null);
      setPhotoPreviewUrl('');
      setPhotoError(err instanceof Error ? err.message : 'Invalid photo file.');
    }
  };

  const handlePhotoDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setPhotoDragActive(false);
    const file = event.dataTransfer.files?.[0];
    if (file) selectPhoto(file);
  };

  const createCroppedImageBlob = async (): Promise<Blob | null> => {
    if (!photoPreviewUrl || !photoFile) return null;
    const image = new Image();
    image.src = photoPreviewUrl;
    await new Promise<void>((resolve, reject) => { image.onload = () => resolve(); image.onerror = reject; });
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Could not prepare the photo for upload.');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    const baseScale = Math.max(canvas.width / image.width, canvas.height / image.height);
    const finalScale = baseScale * photoZoom;
    const drawWidth = image.width * finalScale;
    const drawHeight = image.height * finalScale;
    context.drawImage(image, (canvas.width - drawWidth) / 2 + photoOffsetX, (canvas.height - drawHeight) / 2 + photoOffsetY, drawWidth, drawHeight);
    const mimeType = photoFile.type === 'image/png' ? 'image/png' : photoFile.type === 'image/webp' ? 'image/webp' : 'image/jpeg';
    const blob: Blob | null = await new Promise((resolve) => canvas.toBlob(resolve, mimeType, 0.92));
    if (!blob) throw new Error('Could not generate cropped photo.');
    return blob;
  };

  const handleSaveStudent = async () => {
    try {
      setSaving(true);
      setPhotoError('');
      setPhotoFeedback(null);
      if (!studentForm.admissionNumber.trim() || !studentForm.fullName.trim()) {
        throw new Error('Admission number and full name are required.');
      }
      if (!studentForm.classId || !studentForm.sectionId) {
        throw new Error('Class and section are required.');
      }

      const studentResponse = await api.post<{ student?: { id: number }; id?: number }>(
        '/workspace/students',
        { ...studentProfileFormToCreatePayload(studentForm), ...(schoolScopedParams || {}) },
      );
      const createdStudent = (studentResponse.data as { student?: { id: number }; id?: number })?.student || studentResponse.data;
      if (photoFile) {
        const croppedBlob = await createCroppedImageBlob();
        if (!croppedBlob) throw new Error('Photo preview is not ready yet.');
        const ext = photoFile.name.split('.').pop() || (photoFile.type === 'image/png' ? 'png' : photoFile.type === 'image/webp' ? 'webp' : 'jpg');
        const uploadFile = new File([croppedBlob], `student-photo.${ext}`, { type: croppedBlob.type || photoFile.type });
        const formData = new FormData();
        formData.append('file', uploadFile);
        await api.post(`/students/${(createdStudent as { id: number }).id}/photo`, formData, { headers: { 'Content-Type': 'multipart/form-data' } });
        setPhotoFeedback({ type: 'success', message: 'Student saved and photo uploaded successfully.' });
      } else {
        setPhotoFeedback({ type: 'success', message: 'Student saved successfully.' });
      }
      await onRefresh();
      resetStudentForm();
      setPanel('students');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Unable to save student.');
      setPhotoFeedback({ type: 'error', message: msg });
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
        <div className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}>
          <span>{photoFeedback.type === 'success' ? <Check size={16} /> : '!'}</span>
          <div>{photoFeedback.message}</div>
        </div>
      ) : null}

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
            <span className="ck-status sgr">Draft</span>
          </div>
          <div className="ck-form-body">
            <StudentProfileForm
              form={studentForm}
              classes={classes}
              sections={sections}
              onChange={updateStudentForm}
              onClassChange={onClassChange}
            />

            <div className="ck-photo-panel">
              <div className="ck-photo-panel-copy">
                <h3>Student profile photo</h3>
                <p>Upload a clear face photo. JPG, PNG, or WEBP up to {STUDENT_PHOTO_MAX_LABEL}.</p>
              </div>
              <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) selectPhoto(file); }} />
              <div className={`ck-photo-dropzone ${photoDragActive ? 'drag' : ''} ${photoPreviewUrl ? 'has-image' : ''}`} onDragOver={(e) => { e.preventDefault(); setPhotoDragActive(true); }} onDragLeave={() => setPhotoDragActive(false)} onDrop={handlePhotoDrop}>
                <div className="ck-photo-drop-icon"><ImagePlus size={24} aria-hidden="true" /></div>
                <div className="ck-photo-drop-title">Drop the student photo here</div>
                <div className="ck-photo-drop-sub">The photo can be cropped before it is saved.</div>
                <div className="ck-actions-inline">
                  <button type="button" className="ck-btn ck-btn-g ck-icon-label" onClick={() => fileInputRef.current?.click()}><ImagePlus size={15} />Choose photo</button>
                  {photoFile ? <button type="button" className="ck-btn ck-btn-ghost" onClick={resetPhotoState}>Remove</button> : null}
                </div>
              </div>
              {photoError ? <div className="ck-photo-error">{photoError}</div> : null}
              {photoPreviewUrl ? (
                <div className="ck-photo-editor">
                  <div>
                    <div className="ck-photo-frame">
                      <img src={photoPreviewUrl} alt="Student preview" className="ck-photo-preview-image" style={{ transform: `translate(${photoOffsetX}px, ${photoOffsetY}px) scale(${photoZoom})` }} />
                    </div>
                    <div className="ck-photo-help">Photo preview</div>
                  </div>
                  <div className="ck-photo-controls">
                    <Field label="Zoom"><input type="range" min="1" max="2.5" step="0.01" value={photoZoom} onChange={(e) => setPhotoZoom(Number(e.target.value))} /></Field>
                    <Field label="Move left / right"><input type="range" min="-140" max="140" step="1" value={photoOffsetX} onChange={(e) => setPhotoOffsetX(Number(e.target.value))} /></Field>
                    <Field label="Move up / down"><input type="range" min="-140" max="140" step="1" value={photoOffsetY} onChange={(e) => setPhotoOffsetY(Number(e.target.value))} /></Field>
                  </div>
                </div>
              ) : null}
            </div>

            <div className="ck-admission-footer">
              <button className="ck-btn ck-btn-ghost ck-icon-label" type="button" onClick={resetStudentForm}>
                <RotateCcw size={15} aria-hidden="true" />Clear form
              </button>
              <button className="ck-btn ck-btn-g ck-icon-label" disabled={saving} onClick={() => void handleSaveStudent()}>
                <Save size={15} aria-hidden="true" />{saving ? 'Saving...' : 'Save & enroll student'}
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
