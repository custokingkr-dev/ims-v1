package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;
import java.util.List;

public record InitiateFullNameVerificationRequest(
        List<String> classIds,
        List<String> sectionIds,
        String verifier,
        LocalDate dueDate
) {}
