package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /api/v1/students (create student).
 * Required: admissionNumber, fullName.
 * schoolId is optional in the body; applyResolvedSchool() fills it from TenantScope when absent.
 */
public record CreateStudentRequest(
        @NotBlank String admissionNumber,
        @NotBlank String fullName,
        Long schoolId,
        String classId,
        String sectionId,
        String gradeLevel,
        String className,
        String sectionName,
        String rollNo,
        String boardRegistrationNumber,
        String dateOfBirth,
        String admissionDate,
        String gender,
        String fatherName,
        String fatherContactNumber,
        String fatherContact,
        String motherName,
        String phone,
        String houseNumber,
        String street,
        String locality,
        String city,
        String state,
        String pinCode,
        String photoUrl,
        String address
) {
    public CreateStudentRequest(String admissionNumber, String fullName, Long schoolId, String classId, String sectionId, String gradeLevel, String className, String sectionName, String rollNo, String boardRegistrationNumber, String dateOfBirth, String admissionDate, String gender, String fatherName, String fatherContactNumber, String fatherContact, String motherName, String phone, String houseNumber, String street, String locality, String city, String state, String pinCode, String photoUrl) {
        this(admissionNumber, fullName, schoolId, classId, sectionId, gradeLevel, className, sectionName, rollNo, boardRegistrationNumber, dateOfBirth, admissionDate, gender, fatherName, fatherContactNumber, fatherContact, motherName, phone, houseNumber, street, locality, city, state, pinCode, photoUrl, null);
    }
}
