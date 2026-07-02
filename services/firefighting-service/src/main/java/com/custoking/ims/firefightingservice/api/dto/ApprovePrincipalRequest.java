package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional action body for POST approve-principal.
 * selectedQuotationId is a UUID string referencing the winning quotation — optional.
 * note is stored as principal_note.
 */
public record ApprovePrincipalRequest(
        @Size(max = 100, message = "selectedQuotationId must be 100 characters or fewer")
        String selectedQuotationId,
        @Size(max = 2000, message = "note must be 2000 characters or fewer")
        String note
) {}
