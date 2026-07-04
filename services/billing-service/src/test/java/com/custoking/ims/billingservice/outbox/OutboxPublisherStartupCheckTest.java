package com.custoking.ims.billingservice.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the deploy-time fail-fast guard: the startup check must reject the
 * no-op {@link LoggingDomainEventPublisher} (which would silently drop outbox
 * events in prod) and accept any real publisher.
 */
class OutboxPublisherStartupCheckTest {

    @Test
    void throwsWhenNoOpLoggingPublisherIsActive() {
        OutboxPublisherStartupCheck check =
                new OutboxPublisherStartupCheck(new LoggingDomainEventPublisher());

        assertThatThrownBy(check::verifyRealPublisherActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BILLING_OUTBOX_PUBSUB_TOPIC_ID");
    }

    @Test
    void passesWhenRealPublisherIsActive() {
        // Any non-logging implementation stands in for the real Pub/Sub publisher.
        DomainEventPublisher realPublisher = envelope -> { /* no-op stub, not the logging one */ };
        OutboxPublisherStartupCheck check = new OutboxPublisherStartupCheck(realPublisher);

        assertThatCode(check::verifyRealPublisherActive).doesNotThrowAnyException();
    }
}
