package com.custoking.ims;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests. A single PostgreSQL container is shared
 * across all subclasses so it is only started once per test run.
 *
 * <p>The container is intentionally <b>not</b> annotated {@code @Container}.
 * Letting the Testcontainers extension manage a {@code static} container's
 * lifecycle stops it in each class's {@code afterAll}; the next class then reuses
 * the <i>cached</i> Spring context, whose Hikari pool is still bound to the now
 * dead container. Every test in that class then fails with
 * "Connection is not available, request timed out ... (total=0, ...)".
 *
 * <p>Instead it is a singleton started once in a static initializer and kept
 * alive for the whole JVM; Testcontainers' Ryuk reaps it at exit. Flyway
 * migrations run against it automatically on first application-context load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    @SuppressWarnings("resource") // singleton — reaped by Testcontainers Ryuk at JVM exit
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("custoking_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        // Guarded so the static init does not fail when Docker is unavailable;
        // @Testcontainers(disabledWithoutDocker = true) then skips the tests.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            postgres.start();
        }
    }
}
