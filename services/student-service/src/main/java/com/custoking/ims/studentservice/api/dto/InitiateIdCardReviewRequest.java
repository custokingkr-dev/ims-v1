package com.custoking.ims.studentservice.api.dto;

import java.util.List;

/**
 * DTO for POST /api/v1/students/reviews/id-card/initiate.
 * schoolId is resolved by applyResolvedSchool() / TenantScope when not provided.
 */
public record InitiateIdCardReviewRequest(
        Long schoolId,
        Long actorId,
        String dueDate,
        List<String> classIds,
        List<String> sectionIds,
        Long assignedToUserId
) {}
