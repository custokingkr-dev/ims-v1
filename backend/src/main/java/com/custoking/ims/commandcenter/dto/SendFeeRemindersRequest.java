package com.custoking.ims.commandcenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SendFeeRemindersRequest(
        @NotEmpty List<Long> studentIds,
        @NotBlank String channel,
        @NotBlank String message,
        String context
) {}
