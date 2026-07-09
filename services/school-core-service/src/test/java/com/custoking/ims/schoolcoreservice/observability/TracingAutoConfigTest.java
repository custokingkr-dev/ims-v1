package com.custoking.ims.schoolcoreservice.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that OpenTelemetry tracing (via the Micrometer Tracing OTel bridge)
 * is wired into the school-core-service application context, and that
 * starting a span makes {@code traceId} available in the SLF4J MDC so the
 * JSON logs can be trace-correlated (Cloud Logging format).
 *
 * <p>Before the tracing dependencies/config were added, no {@link Tracer}
 * bean existed in the context and this test failed with a
 * {@code NoSuchBeanDefinitionException}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TracingAutoConfigTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16")
                    .withUsername("owner")
                    .withPassword("owner");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("flyway.url",      PG::getJdbcUrl);
        r.add("flyway.user",     PG::getUsername);
        r.add("flyway.password", PG::getPassword);
    }

    @Autowired
    Tracer tracer;

    @Test
    void tracerBeanIsPresent_andSpanPropagatesTraceIdIntoMdc() {
        assertThat(tracer).isNotNull();

        Span span = tracer.nextSpan().name("tracing-autoconfig-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String traceIdFromMdc = MDC.get("traceId");
            assertThat(traceIdFromMdc).isNotNull().isNotBlank();
            assertThat(traceIdFromMdc).isEqualTo(span.context().traceId());
        } finally {
            span.end();
        }
    }
}
