package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.infrastructure.ApprovalCommandClient;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReportingApprovalRepository#decide} must write approval decisions into the owning
 * services (school-core-service, operations-service) over HTTP via {@link ApprovalCommandClient}
 * rather than UPDATE-ing their schemas directly — an event projection can only read/derive local
 * facts, never cross-service write. The firefighting current-status lookup here reads the locally
 * projected {@code reporting.fact_firefighting_request} fact, never {@code firefighting.firefighting_requests}.
 *
 * The HTTP client itself is mocked (Mockito) — this test never talks to a live school-core or
 * operations server. Only the local jdbc-backed status read uses a real (Testcontainers) Postgres,
 * matching this module's existing read-repository test conventions.
 */
class ReportingApprovalRepositoryTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ApprovalCommandClient commandClient;
    private ReportingApprovalRepository approvals;

    @BeforeAll
    static void setUpContainer() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping approval-command HTTP rewrite test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .load().migrate();

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setMaximumPoolSize(2);
        dataSource = pool;
        jdbcClient = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDownContainer() {
        if (PG != null) {
            PG.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE reporting.fact_firefighting_request");
        }
        commandClient = mock(ApprovalCommandClient.class);
        approvals = new ReportingApprovalRepository(jdbcClient, commandClient);
    }

    private void seedFirefighting(String code, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO reporting.fact_firefighting_request (code, status) VALUES ('"
                    + code + "', '" + status + "')");
        }
    }

    @Test
    void decideCatalogApproveCallsSchoolCoreApproveEndpoint() {
        when(commandClient.approveCatalog("CK-1")).thenReturn(Map.of());

        Map<String, Object> result = approvals.decide("catalog:CK-1", "approve", Map.of());

        verify(commandClient).approveCatalog("CK-1");
        verify(commandClient, never()).rejectCatalog(any(), any());
        assertThat(result.get("status")).isEqualTo("APPROVED");
        assertThat(result.get("sourceType")).isEqualTo("CATALOG");
        assertThat(result.get("sourceId")).isEqualTo("CK-1");
    }

    @Test
    void decideCatalogRejectCallsSchoolCoreRejectEndpointWithReason() {
        when(commandClient.rejectCatalog(eq("CK-2"), eq("Missing invoice"))).thenReturn(Map.of());

        Map<String, Object> result = approvals.decide("catalog:CK-2", "reject", Map.of("decisionNote", "Missing invoice"));

        verify(commandClient).rejectCatalog("CK-2", "Missing invoice");
        verify(commandClient, never()).approveCatalog(any());
        assertThat(result.get("status")).isEqualTo("REJECTED");
    }

    @Test
    void decideCatalogRejectDefaultsReasonWhenNoteMissing() {
        when(commandClient.rejectCatalog(eq("CK-3"), eq("Returned by Superadmin"))).thenReturn(Map.of());

        approvals.decide("catalog:CK-3", "reject", Map.of());

        verify(commandClient).rejectCatalog("CK-3", "Returned by Superadmin");
    }

    @Test
    void decideFirefightingAwaitingBursarCallsApproveBursarEndpoint() throws Exception {
        seedFirefighting("FF-1", "AWAITING_BURSAR");
        when(commandClient.approveFirefightingBursar(eq("FF-1"), any())).thenReturn(Map.of());

        Map<String, Object> result = approvals.decide("firefighting:FF-1", "approve", Map.of("decisionNote", "Looks good"));

        verify(commandClient).approveFirefightingBursar("FF-1", "Looks good");
        verify(commandClient, never()).approveFirefightingPrincipal(any(), any());
        verify(commandClient, never()).approveFirefightingCustoking(any());
        assertThat(result.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void decideFirefightingAwaitingPrincipalCallsApprovePrincipalEndpoint() throws Exception {
        seedFirefighting("FF-2", "AWAITING_PRINCIPAL");
        when(commandClient.approveFirefightingPrincipal(eq("FF-2"), any())).thenReturn(Map.of());

        approvals.decide("firefighting:FF-2", "approve", Map.of());

        verify(commandClient).approveFirefightingPrincipal("FF-2", "");
        verify(commandClient, never()).approveFirefightingBursar(any(), any());
        verify(commandClient, never()).approveFirefightingCustoking(any());
    }

    @Test
    void decideFirefightingAwaitingCustokingCallsApproveCustokingEndpoint() throws Exception {
        seedFirefighting("FF-3", "AWAITING_CUSTOKING");
        when(commandClient.approveFirefightingCustoking("FF-3")).thenReturn(Map.of());

        approvals.decide("firefighting:FF-3", "approve", Map.of());

        verify(commandClient).approveFirefightingCustoking("FF-3");
        verify(commandClient, never()).approveFirefightingBursar(any(), any());
        verify(commandClient, never()).approveFirefightingPrincipal(any(), any());
    }

    @Test
    void decideFirefightingRejectCallsRejectEndpointRegardlessOfStage() throws Exception {
        seedFirefighting("FF-4", "AWAITING_PRINCIPAL");
        when(commandClient.rejectFirefighting(eq("FF-4"), any(), any())).thenReturn(Map.of());

        Map<String, Object> result = approvals.decide("firefighting:FF-4", "reject",
                Map.of("actorName", "Jane Doe", "decisionNote", "Not needed"));

        verify(commandClient).rejectFirefighting("FF-4", "Jane Doe", "Not needed");
        verify(commandClient, never()).approveFirefightingPrincipal(any(), any());
        assertThat(result.get("status")).isEqualTo("REJECTED");
    }

    @Test
    void decideFirefightingUnknownCodeThrowsNotFound() {
        assertThatThrownBy(() -> approvals.decide("firefighting:MISSING", "approve", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval not found");

        verify(commandClient, never()).approveFirefightingBursar(any(), any());
    }

    @Test
    void decideFirefightingTerminalStatusThrowsNotFound() throws Exception {
        seedFirefighting("FF-5", "APPROVED");

        assertThatThrownBy(() -> approvals.decide("firefighting:FF-5", "approve", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval not found");
    }
}
