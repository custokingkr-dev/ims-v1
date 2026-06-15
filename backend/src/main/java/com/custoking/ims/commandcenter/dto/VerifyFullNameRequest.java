package com.custoking.ims.commandcenter.dto;

public record VerifyFullNameRequest(
        boolean confirmed,
        String suggestedFullName,
        String correctionNotes
) {}
