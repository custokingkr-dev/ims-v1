package com.custoking.ims.model;

public record Payment(
        long id,
        long invoiceId,
        String invoiceNo,
        long branchId,
        String branchName,
        String paymentDate,
        double amount,
        String paymentMode,
        String referenceNo,
        String notes,
        String receivedBy
) {}
