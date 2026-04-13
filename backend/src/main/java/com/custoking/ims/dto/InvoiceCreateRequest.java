package com.custoking.ims.dto;

import java.util.List;

public record InvoiceCreateRequest(
        Long customerId,
        Long branchId,
        String invoiceDate,
        String dueDate,
        Double discountPercent,
        String notes,
        List<InvoiceItemRequest> items
) {}
