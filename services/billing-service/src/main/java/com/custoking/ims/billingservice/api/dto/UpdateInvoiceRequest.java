package com.custoking.ims.billingservice.api.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * PATCH /billing/sa/invoices/{id} — all fields optional.
 *
 * <p>All fields are nullable so callers can omit any field they don't want to update.
 * Format constraints are applied only when the field is present (non-null); @NotNull and
 * @NotBlank are intentionally absent from every field.
 *
 * <p><b>Known limitation (deferred):</b> JSON null (e.g. {@code "notes": null}) is
 * indistinguishable from field-absent at the DTO level — both deserialise to Java null,
 * so neither puts the key in the forwarded map (the column keeps its current value).
 * Only {@code notes} can be cleared to database-null by sending {@code "notes": ""}
 * (empty string), which the repository's {@code trimToNull} converts to null. Note that
 * {@code description} and {@code school} do NOT use {@code trimToNull}, so sending
 * {@code ""} for those stores an empty string (not null); there is no way to clear them
 * to null via this endpoint.
 */
public record UpdateInvoiceRequest(
        @Size(max = 500) String description,
        @PositiveOrZero Integer qty,
        @PositiveOrZero Long rate,
        @Size(max = 500) String school,
        @Size(max = 100) String status,
        @Size(max = 2000) String notes) {
}
