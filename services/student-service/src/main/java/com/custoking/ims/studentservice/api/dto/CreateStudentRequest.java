package com.custoking.ims.studentservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * DTO for POST /api/v1/students (create student).
 * Required: admissionNumber, fullName.
 * schoolId is optional in the body; applyResolvedSchool() fills it from TenantScope when absent.
 */
public record CreateStudentRequest(
        @NotBlank String admissionNumber,
        @NotBlank String fullName,
        Long schoolId,
        String gradeLevel,
        String className,
        String sectionName,
        String rollNo,
        String boardRegistrationNumber,
        String dateOfBirth,
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
        String photoUrl
) {}
