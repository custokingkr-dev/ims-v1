package com.custoking.ims.dto;

public record CustomerCreateRequest(
        String code,
        String name,
        String email,
        String phone,
        String gstin,
        String addressLine,
        Long branchId,
        Boolean active
) {}
