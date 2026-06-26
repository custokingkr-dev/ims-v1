package com.custoking.ims.notificationservice.application;

import com.custoking.ims.notificationservice.persistence.NotificationInboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryService {

    private final ObjectMapper objectMapper;
    private final NotificationDeliveryProvider deliveryProvider;

    public NotificationDeliveryService(ObjectMapper objectMapper, NotificationDeliveryProvider deliveryProvider) {
        this.objectMapper = objectMapper;
        this.deliveryProvider = deliveryProvider;
    }

    public void deliver(NotificationInboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            deliveryProvider.deliver(new NotificationDeliveryRequest(
                    event.getEventId(),
                    text(payload, "template"),
                    text(payload, "channel"),
                    text(payload, "recipientType"),
                    text(payload, "recipientId"),
                    event.getPayload()));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse notification payload", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
