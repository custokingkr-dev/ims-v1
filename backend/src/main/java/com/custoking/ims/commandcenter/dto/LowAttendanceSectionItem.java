package com.custoking.ims.commandcenter.dto;

public record LowAttendanceSectionItem(
        String sectionId,
        String sectionName,
        String className,
        int presentCount,
        int totalEnrolled,
        double attendancePct,
        long studentsBelowThreshold
) {}
