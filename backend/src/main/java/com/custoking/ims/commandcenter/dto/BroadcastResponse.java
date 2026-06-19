package com.custoking.ims.commandcenter.dto;

import com.custoking.ims.commandcenter.NotificationBroadcastEntity;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record BroadcastResponse(
        UUID id,
        Long schoolId,
        String module,
        String title,
        String message,
        String audienceType,
        List<String> channels,
        String status,
        OffsetDateTime scheduledAt,
        OffsetDateTime sentAt,
        OffsetDateTime createdAt
) {
    public static BroadcastResponse from(NotificationBroadcastEntity e) {
        List<String> ch = e.getChannels() != null
                ? Arrays.asList(e.getChannels().split(","))
                : List.of();
        return new BroadcastResponse(
                e.getId(), e.getSchoolId(), e.getModule(), e.getTitle(), e.getMessage(),
                e.getAudienceType(), ch, e.getStatus(), e.getScheduledAt(),
                e.getSentAt(), e.getCreatedAt()
        );
    }
}
