package com.custoking.ims.commandcenter.dto;

public record FullNameVerificationStatusResponse(
        String campaignId,
        long totalStudents,
        long confirmed,
        long correctionRequested,
        long pending,
        double completionPercent
) {}
