package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ClassPhotographyPaymentStatusResponse(
        String eventId,
        String title,
        LocalDate eventDate,
        long totalBudget,
        long schoolContribution,
        long studentContributionTarget,
        long collectedAmount,
        long pendingAmount,
        List<ContributionItem> students,
        int page,
        int size,
        long totalElements
) {
    public record ContributionItem(
            Long studentId,
            String studentName,
            String admissionNo,
            String className,
            String sectionName,
            String parentPhone,
            long expectedAmount,
            long paidAmount,
            long pendingAmount,
            String status,
            OffsetDateTime lastReminderSentAt
    ) {}
}
