package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Optional action body for POST vendor-paid.
 * schoolId is passed through applyResolvedSchool (TenantScope) after extraction —
 * superadmin may supply it; non-superadmins have it overridden to their authenticated school.
 * paidBy is the actor user ID; notes are stored as vendor_payment_notes.
 */
public record VendorPaidRequest(
        Long schoolId,
        @Positive(message = "paidBy must be positive")
        Long paidBy,
        @Size(max = 2000, message = "notes must be 2000 characters or fewer")
        String notes
) {}
