package com.custoking.ims.operationsservice.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the default {@link DomainEventPublisher} ({@link LoggingDomainEventPublisher})
 * only when no other {@link DomainEventPublisher} bean is present — e.g. a
 * real Pub/Sub-backed implementation wired in at deploy time takes precedence
 * and this default backs off.
 */
@Configuration
public class OutboxPublisherConfiguration {

    @Bean
    @ConditionalOnMissingBean(DomainEventPublisher.class)
    public DomainEventPublisher loggingDomainEventPublisher() {
        return new LoggingDomainEventPublisher();
    }
}
