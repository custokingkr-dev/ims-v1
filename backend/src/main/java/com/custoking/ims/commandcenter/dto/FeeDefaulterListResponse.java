package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record FeeDefaulterListResponse(
        long totalDefaulters,
        long totalOverdueAmount,
        int oldestDueDays,
        List<FeeDefaulterItem> items,
        int page,
        int size,
        long totalElements
) {
    public record FeeDefaulterItem(
            Long studentId,
            String studentName,
            String admissionNo,
            String className,
            String sectionName,
            String parentName,
            String parentPhone,
            long dueAmount,
            LocalDate dueDate,
            int daysOverdue,
            OffsetDateTime lastReminderSentAt,
            String reminderStatus,
            String paymentStatus
    ) {}
}
