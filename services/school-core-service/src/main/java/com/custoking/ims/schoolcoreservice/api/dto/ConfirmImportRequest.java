package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /api/v1/students/imports/confirm (and legacy /import/confirm).
 * fileToken is required: the repository calls requireText(request.get("fileToken"), ...) and
 * throws IllegalArgumentException with no fallback when it is absent or blank.
 * schoolId is optional: applyResolvedSchool() / TenantScope fills it from the authenticated
 * context when absent.
 */
public record ConfirmImportRequest(
        @NotBlank String fileToken,
        Long schoolId
) {}
