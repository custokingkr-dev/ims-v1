package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record IdCardReviewStatusResponse(
        String campaignId,
        long totalStudents,
        long completed,
        long pending,
        long needsCorrection,
        double completionPercent,
        List<ClassWiseStatus> classWiseStatus
) {
    public record ClassWiseStatus(
            String classId,
            String className,
            long total,
            long completed,
            long pending,
            long needsCorrection
    ) {}
}
