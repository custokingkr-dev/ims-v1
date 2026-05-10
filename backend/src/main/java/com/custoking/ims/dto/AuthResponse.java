package com.custoking.ims.dto;

import java.util.List;

public record AuthResponse(
        String accessToken,
        long userId,
        String fullName,
        String email,
        String role,
        Long branchId,
        String branchName,
        Long zoneId,
        String zoneName,
        List<String> roles,
        List<String> permissions
) {}
