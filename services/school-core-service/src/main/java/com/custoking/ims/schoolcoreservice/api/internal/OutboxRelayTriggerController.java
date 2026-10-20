package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.outbox.OutboxRelay;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Request-driven relay trigger for Cloud Scheduler. Cloud Run IAM must protect this service;
 * the route deliberately has no shared credential and is not exposed by the API gateway.
 */
@RestController
@RequestMapping("/api/v1/internal/outbox")
public class OutboxRelayTriggerController {

    private final OutboxRelay relay;
    private final com.custoking.ims.schoolcoreservice.outbox.PhotoCleanupWorker photoCleanup;

    public OutboxRelayTriggerController(OutboxRelay relay) {
        this(relay, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRelayTriggerController(OutboxRelay relay, com.custoking.ims.schoolcoreservice.outbox.PhotoCleanupWorker photoCleanup) {
        this.relay = relay;
        this.photoCleanup = photoCleanup;
    }

    @PostMapping("/relay")
    public Map<String, Integer> relay() {
        int completed = photoCleanup == null ? 0 : photoCleanup.drain();
        return Map.of("published", relay.publishBatch(), "photoCleanupCompleted", completed);
    }
}
