package com.custoking.ims.attendanceservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for PUT /section-register.
 * Maps to saveSectionRegister(Map) repo keys:
 *   classId (required), sectionId (required),
 *   date (optional — defaults to today),
 *   actorId (optional — repo uses containsKey to gate recorded_by),
 *   schoolId (optional — resolved via TenantScope, used for access check),
 *   records (optional — list of per-student status entries).
 */
public record SaveSectionRegisterRequest(
        @NotBlank(message = "Class id is required") String classId,
        @NotBlank(message = "Section id is required") String sectionId,
        String date,
        Long actorId,
        Long schoolId,
        Object records
) {}
