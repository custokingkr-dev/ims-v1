package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.outbox.OutboxRelay;
import com.custoking.ims.operationsservice.application.QuotationDocumentService;
import com.custoking.ims.operationsservice.persistence.TrackerStatusOutbox;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Request-driven relay trigger protected by Cloud Run IAM for a dedicated OIDC Scheduler identity. */
@RestController
@RequestMapping("/api/v1/internal/outbox")
public class OutboxRelayTriggerController {

    /** Tracker statuses change a few times a day per order; each delivery is bounded by school-core's client timeouts. */
    static final int TRACKER_STATUSES_PER_TRIGGER = 10;

    private final OutboxRelay relay;
    private final QuotationDocumentService documents;
    private final TrackerStatusOutbox statuses;

    public OutboxRelayTriggerController(OutboxRelay relay, QuotationDocumentService documents, TrackerStatusOutbox statuses) {
        this.relay = relay;
        this.documents = documents;
        this.statuses = statuses;
    }

    @PostMapping("/relay")
    public Map<String, Integer> relay() {
        int published = relay.publishBatch();
        int checked = documents.cleanup();
        // Last, so slow catalogue calls never delay the outbox.
        int delivered = statuses.drain(TRACKER_STATUSES_PER_TRIGGER);
        return Map.of("published", published, "quotationDocumentsChecked", checked, "trackerStatusesDelivered", delivered);
    }
}
