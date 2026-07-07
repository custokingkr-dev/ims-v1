package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.StudentReviewFactReadRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Projects school-core student-review events ({@code student-review-item.upserted.v1}) into the
 * {@code reporting.fact_student_review_item} read model, per Reporting Decoupling SP7
 * (student-review). Not feed-worthy: review-item upserts are routine verification workflow
 * updates, not the kind of one-off business event the command center feed surfaces (mirrors the
 * FeeFactProjector / AttendanceFactProjector posture for high-volume/no-feed projections).
 */
@Component
public class StudentReviewProjector implements ReportingEventProjector {

    private static final String STUDENT_REVIEW_ITEM_UPSERTED = "student-review-item.upserted.v1";
    private static final String STUDENT_REVIEW_CAMPAIGN_COMPLETED = "student-review-campaign.completed.v1";

    private final StudentReviewFactReadRepository facts;
    private final ObjectMapper objectMapper;

    public StudentReviewProjector(StudentReviewFactReadRepository facts, ObjectMapper objectMapper) {
        this.facts = facts;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(STUDENT_REVIEW_ITEM_UPSERTED, STUDENT_REVIEW_CAMPAIGN_COMPLETED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        if (STUDENT_REVIEW_CAMPAIGN_COMPLETED.equals(event.eventType())) {
            String campaignId = PayloadJson.textOrNull(payload, "campaignId");
            if (campaignId != null && !campaignId.isBlank()) {
                String status = PayloadJson.textOrNull(payload, "status");
                facts.updateCampaignStatus(campaignId, status == null ? "COMPLETED" : status);
            }
            return;
        }
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String campaignId = PayloadJson.textOrNull(payload, "campaignId");
        String status = PayloadJson.textOrNull(payload, "status");
        facts.upsert(id, schoolId, campaignId, status);
    }
}
