package com.custoking.ims.billingservice.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for POST /api/v1/billing/sa/invoices (create superadmin invoice).
 *
 * Repo keys used by BillingInvoiceRepository.create():
 *   school      – school name (required, no meaningful fallback)
 *   rate        – per-unit rate in paise (required; defaults to 0 which produces a zero-amount invoice)
 *   orderRef    – optional; defaults to ""
 *   schoolId    – optional; defaults to null
 *   description – optional; defaults to ""
 *   qty         – optional; defaults to 1
 *   amount      – optional; overrides qty*rate when provided
 *   notes       – optional
 *
 * No containsKey / alias (firstPresent) checks in the create path.
 * PATCH /{id} (update) is deferred — its containsKey-based partial-update
 * logic matches the fee PUT/PATCH decision and stays as raw Map for now.
 */
public record CreateInvoiceRequest(
        @NotBlank(message = "school name is required") String school,
        @NotNull(message = "rate is required") @Min(value = 1, message = "rate must be at least 1") Long rate,
        String orderRef,
        Long schoolId,
        String description,
        Integer qty,
        Long amount,
        String notes
) {}
