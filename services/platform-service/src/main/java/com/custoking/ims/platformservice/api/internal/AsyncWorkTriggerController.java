package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationInboxRetryService;
import com.custoking.ims.platformservice.application.ReportingEventInboxProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Wakes request-based Cloud Run compute to drain reporting projections and due notification
 * retries even when the service would otherwise be scaled to zero. Cloud Run IAM is the auth
 * boundary and the API gateway intentionally has no route to this endpoint.
 */
@RestController
@RequestMapping("/api/v1/internal/async")
public class AsyncWorkTriggerController {

    private final ReportingEventInboxProcessor reporting;
    private final NotificationInboxRetryService notifications;

    public AsyncWorkTriggerController(ReportingEventInboxProcessor reporting,
                                      NotificationInboxRetryService notifications) {
        this.reporting = reporting;
        this.notifications = notifications;
    }

    @PostMapping("/drain")
    public Map<String, Integer> drain() {
        return Map.of(
                "reportingProjected", reporting.processBatch(),
                "notificationRetriesAttempted", notifications.retryFailedEvents());
    }
}
