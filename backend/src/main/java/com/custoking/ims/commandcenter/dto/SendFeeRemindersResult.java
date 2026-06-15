package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record SendFeeRemindersResult(
        int sentCount,
        int failedCount,
        List<FailedItem> failedItems
) {
    public record FailedItem(Long studentId, String reason) {}
}
