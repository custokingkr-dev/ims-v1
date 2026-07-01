package com.custoking.ims.studentservice.api.dto;

import java.util.List;

/**
 * DTO for POST /api/v1/students/reviews/full-name/initiate.
 * schoolId is resolved by applyResolvedSchool() / TenantScope when not provided.
 */
public record InitiateFullNameReviewRequest(
        Long schoolId,
        Long actorId,
        String dueDate,
        String verifier,
        List<String> classIds,
        List<String> sectionIds
) {}
