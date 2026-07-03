package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /api/v1/students/{id}/photo.
 * photoUrl is required: the repository calls requireText(request.get("photoUrl"), ...) and
 * throws IllegalArgumentException with no fallback when it is absent or blank.
 */
public record AttachPhotoRequest(
        @NotBlank String photoUrl
) {}
