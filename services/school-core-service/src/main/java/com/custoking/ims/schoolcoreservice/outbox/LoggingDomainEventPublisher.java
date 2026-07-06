package com.custoking.ims.schoolcoreservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link DomainEventPublisher} used when no real publisher (e.g. a
 * Pub/Sub-backed implementation added at deploy time) is configured. Logs the
 * envelope at INFO so the outbox relay can run — and be observed — without any
 * messaging infrastructure wired up.
 *
 * <p>Registered via {@link OutboxPublisherConfiguration}'s {@code @Bean}
 * method (not directly {@code @Component}-annotated): a
 * {@code @ConditionalOnMissingBean} placed straight on a component-scanned
 * class matches its OWN bean definition (Spring Boot self-match pitfall),
 * which would prevent it from ever registering. A {@code @Bean} method
 * correctly excludes itself from the "missing bean" search.
 */
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(EventEnvelope envelope) {
        log.info("Publishing event envelope (logging-only publisher): {}", envelope);
    }
}
