package com.custoking.ims.dto.zone;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ZoneAdminRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank String temporaryPassword
) {}
