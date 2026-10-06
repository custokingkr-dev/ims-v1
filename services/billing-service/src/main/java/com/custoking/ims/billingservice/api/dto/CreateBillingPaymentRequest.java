package com.custoking.ims.billingservice.api.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Monetary amounts use existing integer ledger units; attribution and branch are server-owned. */
public record CreateBillingPaymentRequest(
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @NotNull @Positive Long invoiceId,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @NotNull @Positive Long amount,
        @PastOrPresent LocalDate paymentDate,
        @NotBlank @Pattern(regexp = "CASH|UPI|BANK_TRANSFER|CHEQUE|CARD|OTHER") String paymentMode,
        @Size(max = 255) String referenceNo,
        @Size(max = 2000) String notes,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}") String idempotencyKey) {}
