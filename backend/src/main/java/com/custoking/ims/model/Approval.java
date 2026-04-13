package com.custoking.ims.model;

public record Approval(
        long id,
        long invoiceId,
        String invoiceNo,
        String requestType,
        String status,
        String reason
) {}
