package com.custoking.ims.commandcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record BroadcastCreateRequest(
        String title,
        String message,
        String audienceType,
        List<String> channels,
        OffsetDateTime scheduledAt,
        String module
) {}
