package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.FirefightingFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Projects {@code firefighting-request.upserted.v1} inbox events into
 * {@code reporting.fact_firefighting_request} (SP6 reporting-outbox decoupling: operations-service
 * now owns firefighting.firefighting_requests, so reporting no longer cross-schema-reads it for
 * KPI / vendor-dues / approvals queries). Not feed-worthy: firefighting requests already have
 * their own dedicated command_center_feed rows recorded elsewhere in the domain, and mirroring
 * the ReferenceDimensionProjector precedent, fact projections here are not feed-worthy either.
 */
@Component
public class FirefightingFactProjector implements ReportingEventProjector {

    private static final String FIREFIGHTING_REQUEST_UPSERTED = "firefighting-request.upserted.v1";

    private final FirefightingFactReadRepository firefightingFactRead;
    private final ObjectMapper objectMapper;

    public FirefightingFactProjector(FirefightingFactReadRepository firefightingFactRead, ObjectMapper objectMapper) {
        this.firefightingFactRead = firefightingFactRead;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(FIREFIGHTING_REQUEST_UPSERTED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String code = PayloadJson.textOrNull(payload, "code");
        if (code == null || code.isBlank()) {
            return;
        }
        String title = PayloadJson.textOrNull(payload, "title");
        String category = PayloadJson.textOrNull(payload, "category");
        String urgency = PayloadJson.textOrNull(payload, "urgency");
        String status = PayloadJson.textOrNull(payload, "status");
        Long estimatedBudget = PayloadJson.longOrNull(payload, "estimatedBudget");
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String winnerVendor = PayloadJson.textOrNull(payload, "winnerVendor");
        Long winnerAmount = PayloadJson.longOrNull(payload, "winnerAmount");
        OffsetDateTime createdAt = PayloadJson.offsetDateTimeOrNull(payload, "createdAt");
        OffsetDateTime bursarApprovedAt = PayloadJson.offsetDateTimeOrNull(payload, "bursarApprovedAt");
        OffsetDateTime principalApprovedAt = PayloadJson.offsetDateTimeOrNull(payload, "principalApprovedAt");
        String rejectedReason = PayloadJson.textOrNull(payload, "rejectedReason");
        OffsetDateTime vendorPaidAt = PayloadJson.offsetDateTimeOrNull(payload, "vendorPaidAt");
        Long vendorPaidBy = PayloadJson.longOrNull(payload, "vendorPaidBy");
        String vendorPaymentNotes = PayloadJson.textOrNull(payload, "vendorPaymentNotes");
        OffsetDateTime occurredAt = event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
        firefightingFactRead.upsert(code, title, category, urgency, status, estimatedBudget, schoolId,
                winnerVendor, winnerAmount, createdAt, bursarApprovedAt, principalApprovedAt, rejectedReason,
                vendorPaidAt, vendorPaidBy, vendorPaymentNotes, occurredAt);
    }
}
