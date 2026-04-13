package com.custoking.ims.dto.school;

import jakarta.validation.constraints.NotBlank;

public record SchoolAdminRequest(
        @NotBlank String fullName,
        @NotBlank String email,
        @NotBlank String temporaryPassword
) {}
