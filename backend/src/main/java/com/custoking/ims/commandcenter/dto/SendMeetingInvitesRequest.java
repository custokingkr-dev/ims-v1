package com.custoking.ims.commandcenter.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record SendMeetingInvitesRequest(
        @NotEmpty List<Long> studentIds,
        @NotNull String channel,
        @NotNull String message,
        LocalDate meetingDate
) {}
