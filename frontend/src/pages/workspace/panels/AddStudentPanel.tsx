import { DragEvent, useRef, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import type { PanelKey } from '../config';

interface Props {
  setPanel: (key: PanelKey) => void;
  onRefresh: () => Promise<void>;
}

const emptyForm = () => ({
  admissionNumber: '', boardRegistrationNumber: '', fullName: '', dateOfBirth: '', gender: 'Male',
  gradeLevel: 'Class 9', sectionName: 'A', academicYear: '2025–26', admissionDate: '',
  houseNumber: '', street: '', locality: '', city: 'Hyderabad', state: 'Telangana', pinCode: '',
  fatherName: '', fatherContactNumber: '', paymentSchedule: 'Monthly', manualDiscountOverride: '0',
});

export function AddStudentPanel({ setPanel, onRefresh }: Props) {
  const [studentForm, setStudentForm] = useState(emptyForm());
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

  const resetPhotoState = () => {
    setPhotoFile(null);
    if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
    setPhotoPreviewUrl('');
    setPhotoError('');
    setPhotoDragActive(false);
    setPhotoZoom(1); setPhotoOffsetX(0); setPhotoOffsetY(0);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const resetStudentForm = () => { setStudentForm(emptyForm()); resetPhotoState(); setPhotoFeedback(null); };

  const validateImageFile = (file: File) => {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) throw new Error('Only JPG, PNG, or WEBP files are allowed.');
    if (file.size > 2 * 1024 * 1024) throw new Error('Photo must be 2MB or smaller.');
  };

  const selectPhoto = (file: File) => {
    try {
      validateImageFile(file);
      if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
      setPhotoFile(file);
      setPhotoPreviewUrl(URL.createObjectURL(file));
      setPhotoError('');
      setPhotoFeedback(null);
      setPhotoZoom(1); setPhotoOffsetX(0); setPhotoOffsetY(0);
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
    canvas.width = 512; canvas.height = 512;
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
      const studentResponse = await api.post<{ student?: { id: number }; id?: number }>('/workspace/students', { ...studentForm });
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

  return (
    <ModuleShell title="Add student" subtitle="Capture complete student master data, validate unique IDs and auto-create fee assignment" actions={<button className="ck-btn ck-btn-ghost" onClick={() => setPanel('bulkimport')}>Bulk import instead</button>}>
      {photoFeedback ? <div className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}><span>{photoFeedback.type === 'success' ? '✓' : '!'}</span><div>{photoFeedback.message}</div></div> : null}
      <div className="ck-form-card">
        <div className="ck-form-head">Student profile</div>
        <div className="ck-form-body">
          <div className="ck-form-grid ck-fg-3">
            <Field label="Admission Number *"><input value={studentForm.admissionNumber} onChange={(e) => setStudentForm({ ...studentForm, admissionNumber: e.target.value })} placeholder="Manual unique ID" /></Field>
            <Field label="Board Registration Number"><input value={studentForm.boardRegistrationNumber} onChange={(e) => setStudentForm({ ...studentForm, boardRegistrationNumber: e.target.value })} placeholder="Alphanumeric" /></Field>
            <Field label="Full name *"><input value={studentForm.fullName} onChange={(e) => setStudentForm({ ...studentForm, fullName: e.target.value })} placeholder="Student full name" /></Field>
            <Field label="Date of birth"><input type="date" value={studentForm.dateOfBirth} onChange={(e) => setStudentForm({ ...studentForm, dateOfBirth: e.target.value })} /></Field>
            <Field label="Gender"><select value={studentForm.gender} onChange={(e) => setStudentForm({ ...studentForm, gender: e.target.value })}><option>Male</option><option>Female</option><option>Other</option></select></Field>
            <Field label="Admission date"><input type="date" value={studentForm.admissionDate} onChange={(e) => setStudentForm({ ...studentForm, admissionDate: e.target.value })} /></Field>
            <Field label="Class *"><select value={studentForm.gradeLevel} onChange={(e) => setStudentForm({ ...studentForm, gradeLevel: e.target.value })}><option>Class 1</option><option>Class 2</option><option>Class 3</option><option>Class 4</option><option>Class 5</option><option>Class 6</option><option>Class 7</option><option>Class 8</option><option>Class 9</option><option>Class 10</option><option>Class 11</option><option>Class 12</option></select></Field>
            <Field label="Section"><select value={studentForm.sectionName} onChange={(e) => setStudentForm({ ...studentForm, sectionName: e.target.value })}><option>A</option><option>B</option><option>C</option><option>D</option></select></Field>
            <Field label="Academic year"><input value={studentForm.academicYear} onChange={(e) => setStudentForm({ ...studentForm, academicYear: e.target.value })} /></Field>
          </div>
          <div className="ck-form-grid ck-fg-3" style={{ marginTop: 16 }}>
            <Field label="House number"><input value={studentForm.houseNumber} onChange={(e) => setStudentForm({ ...studentForm, houseNumber: e.target.value })} /></Field>
            <Field label="Street"><input value={studentForm.street} onChange={(e) => setStudentForm({ ...studentForm, street: e.target.value })} /></Field>
            <Field label="Locality"><input value={studentForm.locality} onChange={(e) => setStudentForm({ ...studentForm, locality: e.target.value })} /></Field>
            <Field label="City"><input value={studentForm.city} onChange={(e) => setStudentForm({ ...studentForm, city: e.target.value })} /></Field>
            <Field label="State"><input value={studentForm.state} onChange={(e) => setStudentForm({ ...studentForm, state: e.target.value })} /></Field>
            <Field label="PIN code"><input value={studentForm.pinCode} onChange={(e) => setStudentForm({ ...studentForm, pinCode: e.target.value.replace(/\D/g, '').slice(0, 6) })} /></Field>
            <Field label="Father name"><input value={studentForm.fatherName} onChange={(e) => setStudentForm({ ...studentForm, fatherName: e.target.value })} /></Field>
            <Field label="Father contact number"><input value={studentForm.fatherContactNumber} onChange={(e) => setStudentForm({ ...studentForm, fatherContactNumber: e.target.value.replace(/\D/g, '').slice(0, 10) })} /></Field>
            <Field label="Default payment schedule"><select value={studentForm.paymentSchedule} onChange={(e) => setStudentForm({ ...studentForm, paymentSchedule: e.target.value })}><option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option></select></Field>
          </div>

          <div className="ck-photo-panel">
            <div className="ck-photo-panel-copy">
              <h3>Student profile photo</h3>
              <p>Upload a clear face photo. Accepted formats: JPG, PNG, WEBP. Maximum 2MB.</p>
            </div>
            <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) selectPhoto(file); }} />
            <div className={`ck-photo-dropzone ${photoDragActive ? 'drag' : ''} ${photoPreviewUrl ? 'has-image' : ''}`} onDragOver={(e) => { e.preventDefault(); setPhotoDragActive(true); }} onDragLeave={() => setPhotoDragActive(false)} onDrop={handlePhotoDrop}>
              <div className="ck-photo-drop-icon">🖼</div><div className="ck-photo-drop-title">Drag and drop the student photo here</div><div className="ck-photo-drop-sub">JPG, PNG or WEBP · up to 2MB</div>
              <div className="ck-actions-inline"><button type="button" className="ck-btn ck-btn-g" onClick={() => fileInputRef.current?.click()}>Browse file</button>{photoFile ? <button type="button" className="ck-btn ck-btn-ghost" onClick={resetPhotoState}>Remove photo</button> : null}</div>
            </div>
            {photoError ? <div className="ck-photo-error">{photoError}</div> : null}
            {photoPreviewUrl ? <div className="ck-photo-editor"><div><div className="ck-photo-frame"><img src={photoPreviewUrl} alt="Student preview" className="ck-photo-preview-image" style={{ transform: `translate(${photoOffsetX}px, ${photoOffsetY}px) scale(${photoZoom})` }} /></div><div className="ck-photo-help">Live preview before saving</div></div><div className="ck-photo-controls"><Field label="Zoom"><input type="range" min="1" max="2.5" step="0.01" value={photoZoom} onChange={(e) => setPhotoZoom(Number(e.target.value))} /></Field><Field label="Move left / right"><input type="range" min="-140" max="140" step="1" value={photoOffsetX} onChange={(e) => setPhotoOffsetX(Number(e.target.value))} /></Field><Field label="Move up / down"><input type="range" min="-140" max="140" step="1" value={photoOffsetY} onChange={(e) => setPhotoOffsetY(Number(e.target.value))} /></Field></div></div> : null}
          </div>

          <div className="ck-alert ck-alert-g" style={{ marginTop: 16 }}><span>✓</span><div><strong>Validation rules</strong><div>Admission number and full name are required. Student records are saved against the admin's school automatically, and fee assignment will be created on save using the selected payment schedule.</div></div></div>
          <div className="ck-actions-inline"><button className="ck-btn ck-btn-ghost" type="button" onClick={resetStudentForm}>Clear form</button><button className="ck-btn ck-btn-g" disabled={saving} onClick={() => void handleSaveStudent()}>{saving ? 'Saving…' : 'Save & enrol student →'}</button></div>
        </div>
      </div>
    </ModuleShell>
  );
}
