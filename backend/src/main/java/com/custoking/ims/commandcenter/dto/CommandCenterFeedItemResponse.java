package com.custoking.ims.commandcenter.dto;

import com.custoking.ims.commandcenter.CommandCenterFeedEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CommandCenterFeedItemResponse(
        UUID id,
        Long schoolId,
        String module,
        String eventType,
        String title,
        String message,
        String severity,
        String entityType,
        String entityId,
        OffsetDateTime createdAt
) {
    public static CommandCenterFeedItemResponse from(CommandCenterFeedEntity e) {
        return new CommandCenterFeedItemResponse(
                e.getId(), e.getSchoolId(), e.getModule(), e.getEventType(),
                e.getTitle(), e.getMessage(), e.getSeverity(),
                e.getEntityType(), e.getEntityId(), e.getCreatedAt()
        );
    }
}
