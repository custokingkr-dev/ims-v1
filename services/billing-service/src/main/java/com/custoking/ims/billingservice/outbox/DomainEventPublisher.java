package com.custoking.ims.billingservice.outbox;

/**
 * Seam through which the {@link OutboxRelay} publishes canonical event
 * envelopes to the outside world. The default implementation
 * ({@link LoggingDomainEventPublisher}) just logs; a real Pub/Sub-backed
 * implementation is added at deploy time and takes precedence over it.
 */
public interface DomainEventPublisher {

    void publish(EventEnvelope envelope);
}
