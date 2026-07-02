package com.custoking.ims.workflowservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /instances (create-or-get workflow instance).
 *
 * Repo keys consumed by createOrGetInstance(Map):
 *   entityType   — required (requireText, always)
 *   entityId     — required (requireText, always)
 *   definitionId — required (requireText, only when no existing instance for entityType+entityId;
 *                            omitting it when an existing instance exists would succeed at the repo
 *                            level, but callers should always supply it for idempotent behaviour)
 *   schoolId     — optional Long; stamped via TenantScope.resolveSchoolId before calling repo
 *   initiatedBy  — optional Long; null-gated put
 *
 * Action endpoints (submit/approve/reject/cancel/complete) are deferred — all use
 * {@code @RequestBody(required = false)} with no required fields; bean-validation adds nothing.
 */
public record CreateInstanceRequest(
        @NotBlank(message = "entityType is required") String entityType,
        @NotBlank(message = "entityId is required") String entityId,
        @NotBlank(message = "definitionId is required") String definitionId,
        Long schoolId,
        Long initiatedBy
) {}
