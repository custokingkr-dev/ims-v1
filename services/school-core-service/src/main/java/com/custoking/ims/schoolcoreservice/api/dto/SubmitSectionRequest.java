package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /submit-section.
 * Maps to submitAttendanceSection(Map) repo keys:
 *   classId (required), sectionId (required),
 *   date (optional — defaults to today),
 *   actorId (optional — repo uses containsKey to gate updated_by),
 *   schoolId (optional — resolved via TenantScope, used for access check).
 */
public record SubmitSectionRequest(
        @NotBlank(message = "Class id is required") String classId,
        @NotBlank(message = "Section id is required") String sectionId,
        String date,
        Long actorId,
        Long schoolId
) {}
