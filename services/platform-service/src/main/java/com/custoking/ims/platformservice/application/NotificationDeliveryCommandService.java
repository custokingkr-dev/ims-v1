package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Synchronous "deliver this now and tell me what happened" for a peer service that owns its own
 * retry loop (school-core's absentee-notification drainer, over HTTP with Cloud Run OIDC).
 *
 * <p>It deliberately reuses the persisted notification inbox instead of calling the provider
 * directly: {@link NotificationInboxProcessor#process} gives the row lock, the
 * {@code NotificationPolicyGuard} verification, the delivery-attempt audit trail and — because
 * the inbox row is keyed by the caller's service-prefixed event id — idempotency: a replay of an
 * event that already reached a terminal state is answered from that state without another provider
 * attempt. Because the caller paces retries, a retry here refreshes the stored payload (fresh policy
 * evidence) and clears the inbox's own backoff gate before processing.
 *
 * <p>This method is intentionally not {@code @Transactional}: the processor's
 * {@code noRollbackFor} semantics must apply at its own boundary.
 */
@Service
public class NotificationDeliveryCommandService {

    private final NotificationInboxRepository inbox;
    private final NotificationInboxProcessor processor;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final boolean msg91DryRun;

    @Autowired
    public NotificationDeliveryCommandService(NotificationInboxRepository inbox,
                                              NotificationInboxProcessor processor,
                                              ObjectMapper objectMapper,
                                              @Value("${notification.delivery.provider:logging}") String provider,
                                              @Value("${notification.msg91.dry-run:true}") boolean msg91DryRun) {
        this.inbox = inbox;
        this.processor = processor;
        this.objectMapper = objectMapper;
        this.provider = provider == null || provider.isBlank() ? "logging" : provider.trim();
        this.msg91DryRun = msg91DryRun;
    }

    /** Only MSG91 with {@code MSG91_DRY_RUN=false} is a real send; every other configuration is a dry-run. */
    public boolean dryRun() {
        return !"msg91".equals(provider) || msg91DryRun;
    }

    public DeliveryAnswer deliverNow(DeliverNowCommand command) {
        NotificationInboxEvent event = inbox.findById(command.eventId()).orElse(null);
        if (event != null && terminal(event)) {
            return answer(event);
        }
        String payload = objectMapper.writeValueAsString(command.payload());
        if (event == null) {
            event = new NotificationInboxEvent();
            event.setEventId(command.eventId());
            event.setEventType(command.eventType());
            event.setEventKey(command.eventKey());
            event.setAggregateType(command.aggregateType());
            event.setAggregateId(command.aggregateId());
        } else {
            event.setStatus(NotificationInboxEvent.STATUS_RECEIVED);
            event.setNextAttemptAt(null);
        }
        event.setPayload(payload);
        event = inbox.save(event);
        try {
            processor.process(event);
        } catch (NotificationDeliveryFailedException ignored) {
            // The processor has already persisted FAILED / DEAD_LETTER; the answer is read from the row.
        }
        NotificationInboxEvent after = inbox.findById(command.eventId()).orElse(event);
        return answer(after);
    }

    private DeliveryAnswer answer(NotificationInboxEvent event) {
        String status = switch (event.getStatus() == null ? "" : event.getStatus()) {
            case NotificationInboxEvent.STATUS_PROCESSED -> "DELIVERED";
            case NotificationInboxEvent.STATUS_SUPPRESSED -> "SUPPRESSED";
            case NotificationInboxEvent.STATUS_DEAD_LETTER -> "DEAD_LETTER";
            default -> "FAILED";
        };
        return new DeliveryAnswer(event.getEventId(), status, dryRun(), provider, event.getAttemptCount(),
                event.getLastError(), null);
    }

    private static boolean terminal(NotificationInboxEvent event) {
        return NotificationInboxEvent.STATUS_PROCESSED.equals(event.getStatus())
                || NotificationInboxEvent.STATUS_DEAD_LETTER.equals(event.getStatus())
                || NotificationInboxEvent.STATUS_SUPPRESSED.equals(event.getStatus());
    }

    public record DeliverNowCommand(
            String eventId,
            String eventType,
            String eventKey,
            String aggregateType,
            String aggregateId,
            JsonNode payload) {
    }

    /**
     * @param providerMessageId always {@code null} today: the MSG91 provider has no live dispatch
     *                          path (see NOTIFICATION-CONSENT-ENFORCEMENT) and therefore no id to
     *                          return. The field exists so the contract does not change when it does.
     */
    public record DeliveryAnswer(
            String eventId,
            String status,
            boolean dryRun,
            String provider,
            int attempts,
            String error,
            String providerMessageId) {
    }
}
