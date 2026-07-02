package com.custoking.ims.billingservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateInvoiceRequest(
        @NotBlank String school,
        String orderRef,
        Long schoolId,
        String description,
        Integer qty,
        Long rate,
        Long amount,
        String notes) {
}
