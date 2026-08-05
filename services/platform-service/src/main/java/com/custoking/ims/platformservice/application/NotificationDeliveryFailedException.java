package com.custoking.ims.platformservice.application;

public class NotificationDeliveryFailedException extends IllegalStateException {
    public NotificationDeliveryFailedException(String eventId, Throwable cause) {
        super("Notification delivery failed for event " + eventId, cause);
    }
}
