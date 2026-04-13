package com.custoking.ims.model;

public record Customer(
        long id,
        String code,
        String name,
        String email,
        String phone,
        String gstin,
        String addressLine,
        long branchId,
        String branchName,
        boolean active
) {}
