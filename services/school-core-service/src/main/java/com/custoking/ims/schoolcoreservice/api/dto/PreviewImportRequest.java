package com.custoking.ims.schoolcoreservice.api.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for POST /api/v1/students/imports/preview (and legacy /import/preview).
 * schoolId is optional: applyResolvedSchool() / TenantScope fills it from the authenticated
 * context when absent. Non-superadmin callers always have a bound school.
 * rows is optional: the repository defaults to an empty list when absent.
 * Row content is fully dynamic (one Map per CSV row), so no per-field constraints are applied.
 */
public record PreviewImportRequest(
        Long schoolId,
        List<Map<String, Object>> rows
) {}
