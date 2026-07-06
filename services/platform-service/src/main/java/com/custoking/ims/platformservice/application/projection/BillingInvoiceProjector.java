package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.BillingInvoiceReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Projects {@code billing.invoice-upserted.v1} inbox events into
 * {@code reporting.billing_invoice_read}. Feed-worthy: billing invoice events also create a
 * command_center_feed row (preserves pre-refactor behavior).
 */
@Component
public class BillingInvoiceProjector implements ReportingEventProjector {

    private static final String BILLING_INVOICE_UPSERTED = "billing.invoice-upserted.v1";

    private final BillingInvoiceReadRepository billingInvoiceRead;
    private final ObjectMapper objectMapper;

    public BillingInvoiceProjector(BillingInvoiceReadRepository billingInvoiceRead, ObjectMapper objectMapper) {
        this.billingInvoiceRead = billingInvoiceRead;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(BILLING_INVOICE_UPSERTED);
    }

    @Override
    public boolean feedWorthy() {
        return true;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String orderRef = PayloadJson.textOrNull(payload, "orderRef");
        String school = PayloadJson.textOrNull(payload, "school");
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String description = PayloadJson.textOrNull(payload, "description");
        Integer qty = PayloadJson.intOrNull(payload, "qty");
        Long rate = PayloadJson.longOrNull(payload, "rate");
        Long amount = PayloadJson.longOrNull(payload, "amount");
        Long gstAmount = PayloadJson.longOrNull(payload, "gstAmount");
        String status = PayloadJson.textOrNull(payload, "status");
        BigDecimal total = PayloadJson.decimalOrNull(payload, "total");
        String issuedAt = PayloadJson.textOrNull(payload, "issuedAt");
        String dueAt = PayloadJson.textOrNull(payload, "dueAt");
        String notes = PayloadJson.textOrNull(payload, "notes");
        OffsetDateTime createdAt = PayloadJson.offsetDateTimeOrNull(payload, "createdAt");
        OffsetDateTime occurredAt = event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
        billingInvoiceRead.upsert(id, orderRef, school, schoolId, description, qty, rate, amount, gstAmount,
                total, status, issuedAt, dueAt, notes, createdAt, occurredAt);
    }
}
