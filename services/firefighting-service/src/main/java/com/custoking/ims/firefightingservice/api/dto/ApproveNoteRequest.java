package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional action body for POST approve-bursar.
 * Carries an optional note that is stored as bursar_note on the request row.
 * Body itself is optional (@RequestBody required=false); this DTO is only validated
 * when a body is actually sent.
 */
public record ApproveNoteRequest(
        @Size(max = 2000, message = "note must be 2000 characters or fewer")
        String note
) {}
