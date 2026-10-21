package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.outbox.OutboxRelay;
import com.custoking.ims.schoolcoreservice.persistence.NotebookTrackerCreationQueue;
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

    /** Notebook trackers are created a few a day; each attempt is bounded by the peers' timeouts. */
    static final int TRACKER_CREATIONS_PER_TRIGGER = 5;

    private final OutboxRelay relay;
    private final com.custoking.ims.schoolcoreservice.outbox.PhotoCleanupWorker photoCleanup;
    private final NotebookTrackerCreationQueue trackers;

    public OutboxRelayTriggerController(OutboxRelay relay) {
        this(relay, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRelayTriggerController(OutboxRelay relay, com.custoking.ims.schoolcoreservice.outbox.PhotoCleanupWorker photoCleanup,
                                        NotebookTrackerCreationQueue trackers) {
        this.relay = relay;
        this.photoCleanup = photoCleanup;
        this.trackers = trackers;
    }

    @PostMapping("/relay")
    public Map<String, Integer> relay() {
        int completed = photoCleanup == null ? 0 : photoCleanup.drain();
        int published = relay.publishBatch();
        // After publishing, so slow tracker calls never delay the outbox.
        int trackersCreated = trackers == null ? 0 : trackers.drain(TRACKER_CREATIONS_PER_TRIGGER);
        return Map.of("published", published, "photoCleanupCompleted", completed, "trackersCreated", trackersCreated);
    }
}
