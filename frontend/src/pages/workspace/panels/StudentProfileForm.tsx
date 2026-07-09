import type { StudentClassOption, StudentProfileFormState, StudentSectionOption } from '../../../features/students';
import { Field } from '../ui';

interface Props {
  form: StudentProfileFormState;
  classes: StudentClassOption[];
  sections: StudentSectionOption[];
  onChange: (patch: Partial<StudentProfileFormState>) => void;
  onClassChange: (classId: string) => void;
  sectionDisabled?: boolean;
}

export function StudentProfileForm({
  form,
  classes,
  sections,
  onChange,
  onClassChange,
  sectionDisabled = false,
}: Props) {
  const update = (patch: Partial<StudentProfileFormState>) => onChange(patch);

  return (
    <>
      <div className="ck-form-section">
        <div className="ck-form-section-title">
          <span className="ck-form-section-icon">👤</span>
          Student Details
        </div>
        <div className="ck-form-grid ck-fg-3">
          <Field label="Admission Number *">
            <input value={form.admissionNumber} onChange={(e) => update({ admissionNumber: e.target.value })} placeholder="Manual unique ID" />
          </Field>
          <Field label="Board Registration Number">
            <input value={form.boardRegistrationNumber} onChange={(e) => update({ boardRegistrationNumber: e.target.value })} placeholder="Alphanumeric" />
          </Field>
          <Field label="Full name *">
            <input value={form.fullName} onChange={(e) => update({ fullName: e.target.value })} placeholder="Student full name" />
          </Field>
          <Field label="Roll No">
            <input value={form.rollNo} onChange={(e) => update({ rollNo: e.target.value })} />
          </Field>
          <Field label="Date of birth">
            <input type="date" value={form.dateOfBirth} onChange={(e) => update({ dateOfBirth: e.target.value })} />
          </Field>
          <Field label="Gender">
            <select value={form.gender} onChange={(e) => update({ gender: e.target.value })}>
              <option>Male</option>
              <option>Female</option>
              <option>Other</option>
            </select>
          </Field>
        </div>
      </div>

      <div className="ck-form-section">
        <div className="ck-form-section-title">
          <span className="ck-form-section-icon">🎓</span>
          Academic Details
        </div>
        <div className="ck-form-grid ck-fg-3">
          <Field label="Class *">
            <select value={form.classId} onChange={(e) => onClassChange(e.target.value)}>
              <option value="">Select class</option>
              {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </Field>
          <Field label="Section *">
            <select value={form.sectionId} onChange={(e) => update({ sectionId: e.target.value })} disabled={sectionDisabled || !form.classId}>
              <option value="">Select section</option>
              {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
          <Field label="Academic year">
            <input value={form.academicYear} onChange={(e) => update({ academicYear: e.target.value })} />
          </Field>
          <Field label="Admission date">
            <input type="date" value={form.admissionDate} onChange={(e) => update({ admissionDate: e.target.value })} />
          </Field>
        </div>
      </div>

      <div className="ck-form-section">
        <div className="ck-form-section-title">
          <span className="ck-form-section-icon">👪</span>
          Parent / Guardian
        </div>
        <div className="ck-form-grid ck-fg-3">
          <Field label="Father name">
            <input value={form.fatherName} onChange={(e) => update({ fatherName: e.target.value })} />
          </Field>
          <Field label="Father contact number">
            <input value={form.fatherContact} onChange={(e) => update({ fatherContact: e.target.value.replace(/\D/g, '').slice(0, 10) })} />
          </Field>
          <Field label="Mother name">
            <input value={form.motherName} onChange={(e) => update({ motherName: e.target.value })} />
          </Field>
          <Field label="Phone">
            <input value={form.phone} onChange={(e) => update({ phone: e.target.value.replace(/\D/g, '').slice(0, 10) })} />
          </Field>
        </div>
      </div>

      <div className="ck-form-section">
        <div className="ck-form-section-title">
          <span className="ck-form-section-icon">📍</span>
          Address
        </div>
        <div className="ck-form-grid ck-fg-3">
          <Field label="House number">
            <input value={form.houseNumber} onChange={(e) => update({ houseNumber: e.target.value })} />
          </Field>
          <Field label="Street">
            <input value={form.street} onChange={(e) => update({ street: e.target.value })} />
          </Field>
          <Field label="Locality">
            <input value={form.locality} onChange={(e) => update({ locality: e.target.value })} />
          </Field>
          <Field label="City">
            <input value={form.city} onChange={(e) => update({ city: e.target.value })} />
          </Field>
          <Field label="State">
            <input value={form.state} onChange={(e) => update({ state: e.target.value })} />
          </Field>
          <Field label="PIN code">
            <input value={form.pinCode} onChange={(e) => update({ pinCode: e.target.value.replace(/\D/g, '').slice(0, 6) })} />
          </Field>
        </div>
      </div>
    </>
  );
}
