package com.custoking.ims.model;

import java.util.List;

public record Invoice(
        long id,
        String invoiceNo,
        long customerId,
        String customerName,
        long branchId,
        String branchName,
        String invoiceDate,
        String dueDate,
        double subtotal,
        double discountPercent,
        double discountAmount,
        double taxAmount,
        double grandTotal,
        double paidAmount,
        double balanceAmount,
        String status,
        String paymentStatus,
        String approvalStatus,
        String notes,
        List<InvoiceItem> items
) {}
