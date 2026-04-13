package com.custoking.ims.model;

public record DashboardStats(
        int totalInvoices,
        int pendingApprovals,
        int activeCustomers,
        double totalRevenue,
        double collectedAmount,
        double outstandingAmount
) {}
