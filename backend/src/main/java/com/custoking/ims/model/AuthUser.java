package com.custoking.ims.model;

public record AuthUser(
        long userId,
        String fullName,
        String email,
        Role role,
        Long branchId,
        String branchName,
        String password
) {}
