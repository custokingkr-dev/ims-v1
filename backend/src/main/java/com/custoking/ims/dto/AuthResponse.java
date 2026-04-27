package com.custoking.ims.dto;

public record AuthResponse(
        String accessToken,
        long userId,
        String fullName,
        String email,
        String role,
        Long branchId,
        String branchName
) {}
