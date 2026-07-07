package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.CatalogFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Projects {@code catalog-order.upserted.v1} inbox events (emitted by school-core-service's
 * catalog order mutations) into {@code reporting.fact_catalog_order}, per Reporting
 * Decoupling SP4. Not feed-worthy: catalog order upserts already surface via the
 * superadmin-approvals and command-center-feed flows through other means, and mirroring SP1's
 * reference-dimension posture, a raw fact-table projection should not itself create a
 * command_center_feed row.
 */
@Component
public class CatalogFactProjector implements ReportingEventProjector {

    private static final String CATALOG_ORDER_UPSERTED = "catalog-order.upserted.v1";

    private final CatalogFactReadRepository catalogFactRead;
    private final ObjectMapper objectMapper;

    public CatalogFactProjector(CatalogFactReadRepository catalogFactRead, ObjectMapper objectMapper) {
        this.catalogFactRead = catalogFactRead;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(CATALOG_ORDER_UPSERTED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String category = PayloadJson.textOrNull(payload, "category");
        String status = PayloadJson.textOrNull(payload, "status");
        Long totalAmount = PayloadJson.longOrNull(payload, "totalAmount");
        String superadminApprovalStatus = PayloadJson.textOrNull(payload, "superadminApprovalStatus");
        OffsetDateTime vendorPaidAt = PayloadJson.offsetDateTimeOrNull(payload, "vendorPaidAt");
        OffsetDateTime createdAt = PayloadJson.offsetDateTimeOrNull(payload, "createdAt");
        LocalDate requiredByDate = PayloadJson.localDateOrNull(payload, "requiredByDate");
        String designStatus = PayloadJson.textOrNull(payload, "designStatus");
        String notes = PayloadJson.textOrNull(payload, "notes");
        catalogFactRead.upsert(id, schoolId, category, status, totalAmount, superadminApprovalStatus,
                vendorPaidAt, createdAt, requiredByDate, designStatus, notes);
    }
}
