package com.custoking.ims.dto;

public record PaymentCreateRequest(
        Long invoiceId,
        Long branchId,
        String paymentDate,
        Double amount,
        String paymentMode,
        String referenceNo,
        String notes
) {}
