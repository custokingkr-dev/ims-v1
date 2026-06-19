package com.custoking.ims.commandcenter.dto;

import java.time.OffsetDateTime;

public record LowAttendanceStudentItem(
        Long studentId,
        String studentName,
        String admissionNo,
        String className,
        String sectionName,
        String fatherName,
        String fatherContact,
        Double attendancePercent,
        OffsetDateTime lastInviteSentAt
) {}
