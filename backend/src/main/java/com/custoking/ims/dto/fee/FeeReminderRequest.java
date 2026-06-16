package com.custoking.ims.dto.fee;

import jakarta.validation.constraints.NotBlank;

public record FeeReminderRequest(
        @NotBlank String classId,
        @NotBlank String sectionId,
        Long schoolId
) {}
