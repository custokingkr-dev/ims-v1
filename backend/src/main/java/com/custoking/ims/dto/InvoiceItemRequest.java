package com.custoking.ims.dto;

public record InvoiceItemRequest(
        String description,
        Integer quantity,
        Double unitPrice,
        Double taxRate
) {}
