package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.FeeFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Projects school-core fee events ({@code fee-assignment.upserted.v1},
 * {@code payment.recorded.v1}) into the {@code reporting.fact_fee_assignment} /
 * {@code reporting.fact_payment} read models. Not feed-worthy: fee fact events must not
 * create command_center_feed rows (mirrors ReferenceDimensionProjector's SP1 posture).
 */
@Component
public class FeeFactProjector implements ReportingEventProjector {

    private static final String FEE_ASSIGNMENT_UPSERTED = "fee-assignment.upserted.v1";
    private static final String PAYMENT_RECORDED = "payment.recorded.v1";

    private final FeeFactReadRepository facts;
    private final ObjectMapper objectMapper;

    public FeeFactProjector(FeeFactReadRepository facts, ObjectMapper objectMapper) {
        this.facts = facts;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(FEE_ASSIGNMENT_UPSERTED, PAYMENT_RECORDED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        String eventType = event.eventType() == null ? "" : event.eventType();
        switch (eventType) {
            case FEE_ASSIGNMENT_UPSERTED -> projectFeeAssignment(event);
            case PAYMENT_RECORDED -> projectPayment(event);
            default -> { /* unreachable: handledEventTypes() restricts routing */ }
        }
    }

    private void projectFeeAssignment(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Long studentId = PayloadJson.longOrNull(payload, "studentId");
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String academicYearId = PayloadJson.textOrNull(payload, "academicYearId");
        Long netPayable = PayloadJson.longOrNull(payload, "netPayable");
        Long paidAmount = PayloadJson.longOrNull(payload, "paidAmount");
        Long dueAmount = PayloadJson.longOrNull(payload, "dueAmount");
        String status = PayloadJson.textOrNull(payload, "status");
        facts.upsertFeeAssignment(id, studentId, schoolId, academicYearId, netPayable, paidAmount, dueAmount, status);
    }

    private void projectPayment(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String assignmentId = PayloadJson.textOrNull(payload, "assignmentId");
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        Long studentId = PayloadJson.longOrNull(payload, "studentId");
        Long amount = PayloadJson.longOrNull(payload, "amount");
        var paidAt = PayloadJson.offsetDateTimeOrNull(payload, "paidAt");
        facts.upsertPayment(id, assignmentId, schoolId, studentId, amount, paidAt);
    }
}
