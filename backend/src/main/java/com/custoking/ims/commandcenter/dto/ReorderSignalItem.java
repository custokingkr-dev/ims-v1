package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;

public record ReorderSignalItem(
        String category,
        LocalDate lastOrderDate,
        int daysSinceLastOrder,
        Integer avgIntervalDays,
        LocalDate predictedNextOrderDate,
        String alertLevel
) {}
