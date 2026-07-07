package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.outbox.OutboxWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the firefighting approval chain's read-side fixes (SP-firefighting Task 1):
 * {@code pending()} surfaces APPROVED (awaiting-custoking) requests, {@code markVendorPaid}
 * enforces a status guard, and {@code timeline()} / {@code detail()} surface the
 * custoking/fulfilled/rejected lifecycle.
 */
class FirefightingReadRepositoryIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;

    FirefightingReadRepository repository;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("firefighting")
                .defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting")
                .load()
                .migrate();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setSchema("firefighting");
        pool.setMaximumPoolSize(3);
        dataSource = pool;
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) {
            PG.stop();
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM firefighting.outbox_events");
            st.execute("DELETE FROM firefighting.ff_quotations");
            st.execute("DELETE FROM firefighting.firefighting_requests");
        }
        OutboxWriter outboxWriter = new OutboxWriter(jdbc, new ObjectMapper(), "firefighting");
        repository = new FirefightingReadRepository(jdbc, outboxWriter, new ObjectMapper());
    }

    private Map<String, Object> newRequest(long schoolId, String title) {
        Map<String, Object> request = new HashMap<>();
        request.put("schoolId", schoolId);
        request.put("title", title);
        request.put("category", "Other");
        request.put("urgency", "HIGH");
        request.put("estimatedBudget", 5000L);
        return request;
    }

    private String driveToApproved(long schoolId, String title) {
        Map<String, Object> created = repository.createRequest(newRequest(schoolId, title));
        String code = (String) created.get("code");
        repository.submit(code);
        repository.approveBursar(code, Map.of("note", "ok"));
        repository.approvePrincipal(code, Map.of());
        return code;
    }

    private String driveToCustokingApproved(long schoolId, String title) {
        String code = driveToApproved(schoolId, title);
        repository.approveCustoking(code);
        return code;
    }

    private String driveToFulfilled(long schoolId, String title) {
        String code = driveToCustokingApproved(schoolId, title);
        repository.fulfill(code);
        return code;
    }

    @Test
    void pending_surfacesApprovedRequest() {
        long schoolId = 500L;
        String code = driveToApproved(schoolId, "Extinguisher refill");

        assertThat(repository.pending(schoolId, 100))
                .anySatisfy(r -> assertThat(r.status()).isEqualTo("APPROVED"));
        assertThat(repository.pending(schoolId, 100))
                .extracting(FirefightingReadRepository.FirefightingRequestRow::code)
                .contains(code);
    }

    @Test
    void markVendorPaid_rejectsBeforeCustokingApproved_allowsAfter() {
        long schoolId = 501L;
        String draftCode = (String) repository.createRequest(newRequest(schoolId, "Draft request")).get("code");

        assertThatThrownBy(() -> repository.markVendorPaid(draftCode, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        String approvedCode = driveToApproved(schoolId, "Approved request");
        assertThatThrownBy(() -> repository.markVendorPaid(approvedCode, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        String custokingApprovedCode = driveToCustokingApproved(schoolId, "Custoking-approved request");
        repository.markVendorPaid(custokingApprovedCode, Map.of("paidBy", 1));
    }

    @Test
    void timeline_includesCustokingApprovedAndFulfilledEvents() {
        long schoolId = 502L;
        String code = driveToFulfilled(schoolId, "Fulfilled request");

        assertThat(repository.timeline(code)).anySatisfy(e -> assertThat(e.get("status")).isEqualTo("CUSTOKING_APPROVED"));
        assertThat(repository.timeline(code)).anySatisfy(e -> assertThat(e.get("status")).isEqualTo("FULFILLED"));
    }

    @Test
    void timeline_includesRejectedEvent() {
        long schoolId = 504L;
        Map<String, Object> created = repository.createRequest(newRequest(schoolId, "Rejected request"));
        String code = (String) created.get("code");
        repository.submit(code);

        repository.reject(code, Map.of("reason", "Not needed"));

        assertThat(repository.timeline(code)).anySatisfy(e -> assertThat(e.get("status")).isEqualTo("REJECTED"));
    }

    @Test
    void detail_surfacesCustokingCriteria() {
        long schoolId = 503L;
        Map<String, Object> request = newRequest(schoolId, "Criteria request");
        request.put("category", "Furniture & fixtures");
        Map<String, Object> created = repository.createRequest(request);
        String code = (String) created.get("code");

        Map<String, Object> detail = repository.detail(code);
        assertThat(detail.get("custokingCriteria")).isEqualTo(Map.of("met", true));
    }
}
