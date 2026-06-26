package com.custoking.ims.notificationservice.application;

public record NotificationDeliveryRequest(
        String eventId,
        String template,
        String channel,
        String recipientType,
        String recipientId,
        String payload
) {
}
