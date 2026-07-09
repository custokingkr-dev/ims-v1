import { describe, expect, it } from 'vitest';
import {
  emptyStudentProfileForm,
  studentDetailToProfileForm,
  studentProfileFormToCreatePayload,
  studentProfileFormToUpdatePayload,
} from './profileForm';

describe('student profile form mappers', () => {
  it('creates canonical classId and sectionId payloads', () => {
    const form = {
      ...emptyStudentProfileForm(),
      admissionNumber: ' ADM-001 ',
      fullName: ' Aarav Sharma ',
      classId: '9',
      sectionId: '4-9-A',
      fatherContact: '9876543210',
    };

    expect(studentProfileFormToCreatePayload(form)).toMatchObject({
      admissionNumber: 'ADM-001',
      fullName: 'Aarav Sharma',
      classId: '9',
      sectionId: '4-9-A',
      fatherContact: '9876543210',
      fatherContactNumber: '9876543210',
      phone: '9876543210',
    });
  });

  it('maps workspace detail into the same edit form shape', () => {
    const form = studentDetailToProfileForm({
      admissionNumber: 'ADM-002',
      boardRegistrationNumber: 'BR-44',
      fullName: 'Meera Rao',
      rollNo: '12',
      dateOfBirth: '2012-04-05',
      gender: 'Female',
      classId: '10',
      sectionId: '4-10-B',
      academicYear: '2026-27',
      fatherName: 'Rao',
      fatherContact: '9000000000',
      motherName: 'Kavya',
      phone: '9111111111',
      address: {
        houseNumber: '12',
        street: 'Lake Road',
        locality: 'North',
        city: 'Hyderabad',
        state: 'Telangana',
        pinCode: '500001',
      },
    });

    expect(form).toMatchObject({
      admissionNumber: 'ADM-002',
      boardRegistrationNumber: 'BR-44',
      fullName: 'Meera Rao',
      classId: '10',
      sectionId: '4-10-B',
      fatherContact: '9000000000',
      city: 'Hyderabad',
    });
    expect(studentProfileFormToUpdatePayload(form)).toMatchObject({
      admissionNumber: 'ADM-002',
      fullName: 'Meera Rao',
      classId: '10',
      sectionId: '4-10-B',
      phone: '9111111111',
    });
  });
});
