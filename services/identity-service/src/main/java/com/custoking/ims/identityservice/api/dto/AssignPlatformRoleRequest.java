package com.custoking.ims.identityservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignPlatformRoleRequest(@NotBlank String role, Long assignedBy) {}
