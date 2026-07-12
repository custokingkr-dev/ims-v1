export interface StudentClassOption {
  id: string;
  name: string;
}

export interface StudentSectionOption {
  id: string;
  name: string;
}

export interface StudentProfileFormState {
  admissionNumber: string;
  boardRegistrationNumber: string;
  fullName: string;
  rollNo: string;
  dateOfBirth: string;
  gender: string;
  classId: string;
  sectionId: string;
  academicYear: string;
  admissionDate: string;
  fatherName: string;
  fatherContact: string;
  motherName: string;
  phone: string;
  houseNumber: string;
  street: string;
  locality: string;
  city: string;
  state: string;
  pinCode: string;
}

export interface StudentProfilePayload {
  admissionNumber: string;
  fullName: string;
  classId: string;
  sectionId: string;
  rollNo?: string;
  boardRegistrationNumber?: string;
  dateOfBirth?: string;
  gender?: string;
  academicYear?: string;
  admissionDate?: string;
  fatherName?: string;
  fatherContact?: string;
  fatherContactNumber?: string;
  motherName?: string;
  phone?: string;
  houseNumber?: string;
  street?: string;
  locality?: string;
  city?: string;
  state?: string;
  pinCode?: string;
}

export function emptyStudentProfileForm(): StudentProfileFormState {
  return {
    admissionNumber: '',
    boardRegistrationNumber: '',
    fullName: '',
    rollNo: '',
    dateOfBirth: '',
    gender: 'Male',
    classId: '',
    sectionId: '',
    academicYear: 'Current academic year',
    admissionDate: '',
    fatherName: '',
    fatherContact: '',
    motherName: '',
    phone: '',
    houseNumber: '',
    street: '',
    locality: '',
    city: 'Hyderabad',
    state: 'Telangana',
    pinCode: '',
  };
}

export function studentDetailToProfileForm(detail: Record<string, any>): StudentProfileFormState {
  const address = detail.address ?? {};
  return {
    ...emptyStudentProfileForm(),
    admissionNumber: text(detail.admissionNumber),
    boardRegistrationNumber: text(detail.boardRegistrationNumber),
    fullName: text(detail.fullName ?? detail.name),
    rollNo: text(detail.rollNo),
    dateOfBirth: text(detail.dateOfBirth),
    gender: text(detail.gender, 'Male'),
    classId: text(detail.classId),
    sectionId: text(detail.sectionId),
    academicYear: text(detail.academicYear, 'Current academic year'),
    admissionDate: text(detail.admissionDate),
    fatherName: text(detail.fatherName),
    fatherContact: text(detail.fatherContact ?? detail.fatherContactNumber ?? detail.parentPhone),
    motherName: text(detail.motherName),
    phone: text(detail.phone),
    houseNumber: text(address.houseNumber),
    street: text(address.street),
    locality: text(address.locality),
    city: text(address.city, 'Hyderabad'),
    state: text(address.state, 'Telangana'),
    pinCode: text(address.pinCode),
  };
}

export function studentProfileFormToCreatePayload(form: StudentProfileFormState): StudentProfilePayload {
  return compactPayload(form);
}

export function studentProfileFormToUpdatePayload(form: StudentProfileFormState): StudentProfilePayload {
  return compactPayload(form);
}

function compactPayload(form: StudentProfileFormState): StudentProfilePayload {
  const payload: StudentProfilePayload = {
    admissionNumber: form.admissionNumber.trim(),
    fullName: form.fullName.trim(),
    classId: form.classId.trim(),
    sectionId: form.sectionId.trim(),
  };
  put(payload, 'rollNo', form.rollNo);
  put(payload, 'boardRegistrationNumber', form.boardRegistrationNumber);
  put(payload, 'dateOfBirth', form.dateOfBirth);
  put(payload, 'gender', form.gender);
  if (form.academicYear.trim() !== 'Current academic year') {
    put(payload, 'academicYear', form.academicYear);
  }
  put(payload, 'admissionDate', form.admissionDate);
  put(payload, 'fatherName', form.fatherName);
  put(payload, 'fatherContact', form.fatherContact);
  put(payload, 'fatherContactNumber', form.fatherContact);
  put(payload, 'motherName', form.motherName);
  put(payload, 'phone', form.phone || form.fatherContact);
  put(payload, 'houseNumber', form.houseNumber);
  put(payload, 'street', form.street);
  put(payload, 'locality', form.locality);
  put(payload, 'city', form.city);
  put(payload, 'state', form.state);
  put(payload, 'pinCode', form.pinCode);
  return payload;
}

function put(payload: StudentProfilePayload, key: keyof StudentProfilePayload, value: string): void {
  const normalized = value.trim();
  if (normalized) {
    payload[key] = normalized;
  }
}

function text(value: unknown, fallback = ''): string {
  if (value == null) return fallback;
  return String(value);
}
