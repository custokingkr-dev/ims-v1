package com.custoking.ims.identityservice.application;

public record AuthenticatedUserSnapshot(
        Long id,
        String fullName,
        String email,
        String role,
        Long branchId,
        String branchName,
        Long zoneId,
        String zoneName) {
}
