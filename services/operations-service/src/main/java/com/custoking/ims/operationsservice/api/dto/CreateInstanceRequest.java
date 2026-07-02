package com.custoking.ims.operationsservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /api/v1/workflows/instances (create-or-get workflow instance).
 * Required: entityType, entityId.
 * definitionId is optional — a re-fetch call for an EXISTING (entityType,entityId) pair
 * succeeds without it; the repository only requires definitionId on the CREATE branch.
 * schoolId and initiatedBy are optional; applyResolvedSchool() fills schoolId from
 * TenantScope when absent.
 */
public record CreateInstanceRequest(
        @NotBlank String entityType,
        @NotBlank String entityId,
        String definitionId,
        Long schoolId,
        Long initiatedBy
) {}
