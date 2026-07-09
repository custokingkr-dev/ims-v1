package com.custoking.ims.operationsservice.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the deploy-time fail-fast guard: the startup check must reject the
 * no-op {@link LoggingDomainEventPublisher} unless local compose explicitly
 * disables the real publisher requirement.
 */
class OutboxPublisherStartupCheckTest {

    @Test
    void throwsWhenNoOpLoggingPublisherIsActive() {
        OutboxPublisherStartupCheck check =
                new OutboxPublisherStartupCheck(new LoggingDomainEventPublisher(), true);

        assertThatThrownBy(check::verifyRealPublisherActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID");
    }

    @Test
    void passesWhenRealPublisherIsActive() {
        DomainEventPublisher realPublisher = envelope -> { /* no-op stub, not the logging one */ };
        OutboxPublisherStartupCheck check = new OutboxPublisherStartupCheck(realPublisher, true);

        assertThatCode(check::verifyRealPublisherActive).doesNotThrowAnyException();
    }

    @Test
    void passesWhenLocalComposeDisablesRealPublisherRequirement() {
        OutboxPublisherStartupCheck check =
                new OutboxPublisherStartupCheck(new LoggingDomainEventPublisher(), false);

        assertThatCode(check::verifyRealPublisherActive).doesNotThrowAnyException();
    }
}
