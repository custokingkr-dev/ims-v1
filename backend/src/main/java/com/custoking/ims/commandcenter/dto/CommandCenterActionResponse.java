package com.custoking.ims.commandcenter.dto;

import com.custoking.ims.commandcenter.CommandCenterActionEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CommandCenterActionResponse(
        UUID id,
        Long schoolId,
        String module,
        String urgency,
        int confidence,
        String title,
        String reason,
        String impact,
        String currentState,
        String targetState,
        String ctaLabel,
        String status,
        String sourceType,
        String sourceId,
        OffsetDateTime createdAt
) {
    public static CommandCenterActionResponse from(CommandCenterActionEntity e) {
        return new CommandCenterActionResponse(
                e.getId(), e.getSchoolId(), e.getModule(), e.getUrgency(), e.getConfidence(),
                e.getTitle(), e.getReason(), e.getImpact(), e.getCurrentState(), e.getTargetState(),
                e.getCtaLabel(), e.getStatus(), e.getSourceType(), e.getSourceId(), e.getCreatedAt()
        );
    }
}
