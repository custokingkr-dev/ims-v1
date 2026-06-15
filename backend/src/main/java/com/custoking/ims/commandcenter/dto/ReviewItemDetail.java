package com.custoking.ims.commandcenter.dto;

import java.time.OffsetDateTime;

public record ReviewItemDetail(
        String itemId,
        Long studentId,
        String studentName,
        String admissionNo,
        String className,
        String sectionName,
        String currentFullName,
        String suggestedFullName,
        String status,
        boolean verifiedPhoto,
        boolean verifiedFullName,
        boolean verifiedAdmissionNo,
        boolean verifiedClassSection,
        boolean verifiedRollNo,
        boolean verifiedFatherName,
        boolean verifiedFatherContact,
        boolean verifiedAddress,
        boolean verifiedBloodGroup,
        boolean parentConfirmed,
        boolean teacherConfirmed,
        boolean correctionRequested,
        String correctionNotes,
        OffsetDateTime completedAt
) {}
