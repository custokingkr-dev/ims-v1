package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.ReportingEventProjector;
import com.custoking.ims.platformservice.observability.TraceContextBridge;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "reporting.event-projection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportingEventInboxProcessor {

    private static final String SOURCE_TYPE = "EVENT_INBOX";

    private final ReportingEventInboxRepository inbox;
    private final ReportingCommandRepository commands;
    private final Map<String, ReportingEventProjector> projectorsByEventType;
    private final TraceContextBridge traceContextBridge;
    private final int batchSize;

    public ReportingEventInboxProcessor(
            ReportingEventInboxRepository inbox,
            ReportingCommandRepository commands,
            List<ReportingEventProjector> projectors,
            int batchSize) {
        this(inbox, commands, projectors, TraceContextBridge.noop(), batchSize);
    }

    @Autowired
    public ReportingEventInboxProcessor(
            ReportingEventInboxRepository inbox,
            ReportingCommandRepository commands,
            List<ReportingEventProjector> projectors,
            TraceContextBridge traceContextBridge,
            @Value("${reporting.event-projection.batch-size:50}") int batchSize) {
        this.inbox = inbox;
        this.commands = commands;
        this.projectorsByEventType = indexByEventType(projectors);
        this.traceContextBridge = traceContextBridge;
        this.batchSize = batchSize;
    }

    private static Map<String, ReportingEventProjector> indexByEventType(List<ReportingEventProjector> projectors) {
        Map<String, ReportingEventProjector> index = new HashMap<>();
        for (ReportingEventProjector projector : projectors) {
            for (String eventType : projector.handledEventTypes()) {
                ReportingEventProjector existing = index.put(eventType, projector);
                if (existing != null && existing != projector) {
                    throw new IllegalStateException("Multiple ReportingEventProjector beans claim event type '"
                            + eventType + "': " + existing.getClass().getName() + " and " + projector.getClass().getName());
                }
            }
        }
        return index;
    }

    @Scheduled(fixedDelayString = "${reporting.event-projection.fixed-delay-ms:10000}")
    public void runScheduled() {
        processBatch();
    }

    public int processBatch() {
        int processed = 0;
        for (var event : inbox.findReceivedForProjection(batchSize)) {
            try {
                traceContextBridge.runInSpan(
                        "reporting.project " + safe(event.eventType(), "event"),
                        event.traceParent(),
                        event.traceState(),
                        () -> processOne(event));
                processed++;
            } catch (RuntimeException ex) {
                inbox.markFailed(event.eventId(), ex.getMessage());
            }
        }
        return processed;
    }

    private void processOne(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        String eventType = event.eventType() == null ? "" : event.eventType();
        ReportingEventProjector projector = projectorsByEventType.get(eventType);
        if (projector != null && projector.feedWorthy()
                && !commands.feedSourceExists(SOURCE_TYPE, event.eventId())) {
            commands.recordProjectedFeed(toFeedCommand(event));
        }
        if (projector != null) {
            projector.project(event);
        }
        inbox.markProcessed(event.eventId());
    }

    private ReportingCommandRepository.ProjectedFeedCommand toFeedCommand(
            ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        String eventType = safe(event.eventType(), "event.received.v1");
        String aggregateType = safe(event.aggregateType(), "Entity");
        String aggregateId = safe(event.aggregateId(), "unknown");
        OffsetDateTime createdAt = event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
        return new ReportingCommandRepository.ProjectedFeedCommand(
                event.schoolId(),
                moduleFor(eventType),
                eventType,
                titleFor(eventType),
                "Received " + eventType + " for " + aggregateType + " " + aggregateId,
                "info",
                aggregateType,
                aggregateId,
                event.actorUserId(),
                createdAt,
                SOURCE_TYPE,
                event.eventId()
        );
    }

    private String moduleFor(String eventType) {
        String domain = eventType == null ? "" : eventType.split("\\.", 2)[0].toLowerCase(Locale.ROOT);
        return switch (domain) {
            case "fees", "billing", "payments" -> "finance";
            case "attendance" -> "attendance";
            case "supply" -> "supply";
            case "firefighting" -> "firefighting";
            case "workflow" -> "workflow";
            case "students", "student" -> "students";
            case "schools", "school" -> "schools";
            case "identity", "auth" -> "identity";
            default -> "system";
        };
    }

    private String titleFor(String eventType) {
        String core = eventType == null ? "Event Received" : eventType;
        int versionStart = core.lastIndexOf(".v");
        if (versionStart > 0 && versionStart < core.length() - 2 && Character.isDigit(core.charAt(versionStart + 2))) {
            core = core.substring(0, versionStart);
        }
        int firstDot = core.indexOf('.');
        if (firstDot >= 0 && firstDot < core.length() - 1) {
            core = core.substring(firstDot + 1);
        }
        String[] tokens = core.replace('-', '.').replace('_', '.').split("\\.");
        StringBuilder title = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                title.append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return title.isEmpty() ? "Event Received" : title.toString();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
