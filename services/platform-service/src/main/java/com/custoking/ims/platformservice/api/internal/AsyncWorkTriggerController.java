package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationInboxRetryService;
import com.custoking.ims.platformservice.application.ReportingEventInboxProcessor;
import com.custoking.ims.platformservice.application.BroadcastDeliveryWorker;
import com.custoking.ims.platformservice.application.BroadcastLiveWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Wakes request-based Cloud Run compute to drain reporting projections and due notification
 * retries and approved broadcast checks even when the service would otherwise be scaled to zero. Cloud Run IAM is the auth
 * boundary and the API gateway intentionally has no route to this endpoint.
 */
@RestController
@RequestMapping("/api/v1/internal/async")
public class AsyncWorkTriggerController {

    private final ReportingEventInboxProcessor reporting;
    private final NotificationInboxRetryService notifications;
    private final BroadcastDeliveryWorker broadcasts;
    private final BroadcastLiveWorker live;

    public AsyncWorkTriggerController(ReportingEventInboxProcessor reporting,
                                      NotificationInboxRetryService notifications, BroadcastDeliveryWorker broadcasts) {
        this(reporting,notifications,broadcasts,null);
    }
    @Autowired
    public AsyncWorkTriggerController(ReportingEventInboxProcessor reporting,
                                      NotificationInboxRetryService notifications, BroadcastDeliveryWorker broadcasts, BroadcastLiveWorker live) {
        this.reporting = reporting;
        this.notifications = notifications;
        this.broadcasts = broadcasts;
        this.live=live;
    }

    @PostMapping("/drain")
    public Map<String, Integer> drain() {
        return Map.of(
                "reportingProjected", reporting.processBatch(),
                "notificationRetriesAttempted", notifications.retryFailedEvents(),
                "broadcastChecksAttempted", broadcasts.drainBatch(),
                "liveBroadcastChecksAttempted", live==null ? 0 : live.drainBatch());
    }
}
