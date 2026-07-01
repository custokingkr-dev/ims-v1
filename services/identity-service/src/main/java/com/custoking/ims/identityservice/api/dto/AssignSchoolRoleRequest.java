package com.custoking.ims.identityservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssignSchoolRoleRequest(@NotBlank String role, @NotNull Long schoolId, Long assignedBy) {}
