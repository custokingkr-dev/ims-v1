package com.custoking.ims.commandcenter.dto;

public record UpdateReviewItemRequest(
        Boolean verifiedPhoto,
        Boolean verifiedFullName,
        Boolean verifiedAdmissionNo,
        Boolean verifiedClassSection,
        Boolean verifiedRollNo,
        Boolean verifiedFatherName,
        Boolean verifiedFatherContact,
        Boolean verifiedAddress,
        Boolean verifiedBloodGroup,
        String correctionNotes,
        String status
) {}
