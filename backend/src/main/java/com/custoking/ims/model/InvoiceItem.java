package com.custoking.ims.model;

public record InvoiceItem(
        long id,
        String description,
        int quantity,
        double unitPrice,
        double taxRate,
        double lineTotal
) {}
