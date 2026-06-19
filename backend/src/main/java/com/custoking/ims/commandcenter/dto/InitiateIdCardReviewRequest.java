package com.custoking.ims.commandcenter.dto;

import java.time.LocalDate;
import java.util.List;

public record InitiateIdCardReviewRequest(
        List<String> classIds,
        List<String> sectionIds,
        LocalDate dueDate,
        Long assignedToUserId
) {}
