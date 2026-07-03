package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.NotificationDeliveryProvider;
import com.custoking.ims.platformservice.application.NotificationDeliveryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notification.delivery", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingNotificationDeliveryProvider implements NotificationDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationDeliveryProvider.class);

    @Override
    public void deliver(NotificationDeliveryRequest request) {
        log.info("notification.deliver eventId={} template={} channel={} recipientType={} recipientId={}",
                request.eventId(),
                request.template(),
                request.channel(),
                request.recipientType(),
                request.recipientId());
    }
}
