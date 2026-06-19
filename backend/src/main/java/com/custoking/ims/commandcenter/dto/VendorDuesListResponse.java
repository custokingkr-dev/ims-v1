package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record VendorDuesListResponse(
        long catalogOrderCount,
        long catalogOrderTotalPaise,
        long firefightingCount,
        long firefightingTotalPaise,
        long totalDuesPaise,
        List<VendorDueItem> items
) {}
