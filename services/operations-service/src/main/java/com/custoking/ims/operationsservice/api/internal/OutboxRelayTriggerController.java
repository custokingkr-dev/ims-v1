package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.outbox.OutboxRelay;
import com.custoking.ims.operationsservice.application.QuotationDocumentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Request-driven relay trigger protected by Cloud Run IAM for a dedicated OIDC Scheduler identity. */
@RestController
@RequestMapping("/api/v1/internal/outbox")
public class OutboxRelayTriggerController {

    private final OutboxRelay relay;
    private final QuotationDocumentService documents;

    public OutboxRelayTriggerController(OutboxRelay relay, QuotationDocumentService documents) {
        this.relay = relay;
        this.documents = documents;
    }

    @PostMapping("/relay")
    public Map<String, Integer> relay() {
        int published = relay.publishBatch();
        return Map.of("published", published, "quotationDocumentsChecked", documents.cleanup());
    }
}
