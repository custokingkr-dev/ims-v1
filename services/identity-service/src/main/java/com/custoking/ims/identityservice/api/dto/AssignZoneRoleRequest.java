package com.custoking.ims.identityservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssignZoneRoleRequest(@NotBlank String role, @NotNull Long zoneId, Long assignedBy) {}
