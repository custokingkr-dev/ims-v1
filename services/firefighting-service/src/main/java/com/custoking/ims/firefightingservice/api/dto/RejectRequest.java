package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional action body for POST reject.
 * Preserves the alias pairs used by the repo:
 *   rejectedBy / actorName  →  stored as rejected_by  (repo: str(rejectedBy, str(actorName, "")))
 *   reason / rejectedReason →  stored as rejected_reason  (repo: firstPresent("reason", "rejectedReason"))
 * All fields are optional; body itself is optional (@RequestBody required=false).
 */
public record RejectRequest(
        String rejectedBy,
        String actorName,
        @Size(max = 2000, message = "reason must be 2000 characters or fewer")
        String reason,
        @Size(max = 2000, message = "rejectedReason must be 2000 characters or fewer")
        String rejectedReason
) {}
