package com.custoking.ims.identityservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(@NotBlank @Size(min = 8) String password, Long actorId, String actorEmail) {}
