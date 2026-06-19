package com.custoking.ims.commandcenter.dto;

import java.time.OffsetDateTime;

public record VendorDueItem(
        String sourceType,
        String id,
        String title,
        String category,
        String vendorName,
        long amountPaise,
        String status,
        OffsetDateTime createdAt
) {}
