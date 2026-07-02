package com.custoking.ims.attendanceservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /daily-entry.
 * Maps to saveDailyAttendance(Map) repo keys:
 *   classId (required), sectionId (required),
 *   date (optional — defaults to today),
 *   totalEnrolled (optional — defaults to countStudents),
 *   presentCount (optional — defaults to 0),
 *   actorId (optional — repo uses containsKey to gate recorded_by),
 *   schoolId (optional — resolved via TenantScope, used for access check).
 */
public record DailyEntryRequest(
        @NotBlank(message = "Class id is required") String classId,
        @NotBlank(message = "Section id is required") String sectionId,
        String date,
        Integer totalEnrolled,
        Integer presentCount,
        Long actorId,
        Long schoolId
) {}
