package com.custoking.ims.billingservice.outbox;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for deployed environments (the {@code prod} profile): the
 * outbox relay MUST have a real messaging-backed {@link DomainEventPublisher}.
 *
 * <p>If the no-op {@link LoggingDomainEventPublisher} is active in prod — which
 * happens when the topic is misconfigured (e.g. the env var was named
 * {@code BILLING_OUTBOX_PUBSUB_TOPIC} instead of {@code ..._TOPIC_ID}, so
 * {@code @ConditionalOnProperty("billing.outbox.pubsub.topic-id")} never matched
 * and {@link PubSubDomainEventPublisher} never registered) — the relay would
 * mark outbox rows published while emitting nothing, silently dropping every
 * domain event. Rather than lose events silently, refuse to start so the
 * misconfiguration surfaces at deploy time instead of in production data drift.
 */
@Component
@Profile("prod")
public class OutboxPublisherStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherStartupCheck.class);

    private final DomainEventPublisher publisher;

    public OutboxPublisherStartupCheck(DomainEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostConstruct
    void verifyRealPublisherActive() {
        if (publisher instanceof LoggingDomainEventPublisher) {
            throw new IllegalStateException(
                    "FATAL: 'prod' profile is active but the DomainEventPublisher is the no-op "
                            + "LoggingDomainEventPublisher — outbox events would be silently dropped. "
                            + "Set the BILLING_OUTBOX_PUBSUB_TOPIC_ID env var so the real "
                            + "PubSubDomainEventPublisher activates.");
        }
        log.info("Outbox publisher startup check passed: {} is active",
                publisher.getClass().getSimpleName());
    }
}
