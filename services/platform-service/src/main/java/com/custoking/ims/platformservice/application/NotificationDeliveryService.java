package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryService {

    private final ObjectMapper objectMapper;
    private final NotificationDeliveryProvider deliveryProvider;
    private final NotificationPolicyGuard policyGuard;

    public NotificationDeliveryService(ObjectMapper objectMapper, NotificationDeliveryProvider deliveryProvider) {
        this.objectMapper = objectMapper;
        this.deliveryProvider = deliveryProvider;
        this.policyGuard = new NotificationPolicyGuard();
    }

    public void deliver(NotificationInboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            policyGuard.requireAllowed(event, payload);
            deliveryProvider.deliver(new NotificationDeliveryRequest(
                    event.getEventId(),
                    text(payload, "template"),
                    text(payload, "channel"),
                    text(payload, "recipientType"),
                    text(payload, "recipientId"),
                    event.getPayload()));
        } catch (NotificationSuppressedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse notification payload", ex);
        }
    }

    public NotificationDeliveryRequest prepare(NotificationInboxEvent event) {
        JsonNode payload=objectMapper.readTree(event.getPayload());
        policyGuard.requireAllowed(event,payload);
        return request(event,objectMapper);
    }

    static NotificationDeliveryRequest request(NotificationInboxEvent event,ObjectMapper mapper) {
        JsonNode payload=mapper.readTree(event.getPayload());
        return new NotificationDeliveryRequest(event.getEventId(),text(payload,"template"),text(payload,"channel"),
            text(payload,"recipientType"),text(payload,"recipientId"),event.getPayload());
    }

    public NotificationSubmissionResult submit(NotificationInboxEvent event) {
        return deliveryProvider.submit(prepare(event));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
