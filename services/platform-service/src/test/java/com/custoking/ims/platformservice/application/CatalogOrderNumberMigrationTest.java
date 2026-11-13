package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.CatalogFactProjector;
import com.custoking.ims.platformservice.application.projection.ReportingEventProjector;
import com.custoking.ims.platformservice.observability.TraceContextBridge;
import com.custoking.ims.platformservice.persistence.CatalogFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.security.ProjectorRls;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reporting V36 (item order workflows design §3.6) on a database that already holds catalog facts and processed events, as
 * dev and prod do. School-core's catalog V37 re-emits every order's number minutes before platform migrates, and the
 * previous platform revision, which ignores orderNumber, keeps projecting until this one serves. V36 copies the numbers
 * already in the inbox onto the facts, and a trigger copies the number of every event any revision marks processed.
 */
class CatalogOrderNumberMigrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static JdbcClient runtime;
    static DataSourceTransactionManager runtimeTransactions;

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        flyway("35").migrate();
        // Facts and events as the previous revision left them: projected, processed, no order_number column yet.
        fact("CK-1", 1);
        fact("CK-2", 1);
        fact("CK-3", 2);
        event("e-1a", "CK-1", "{\"id\":\"CK-1\",\"schoolId\":1,\"status\":\"PROCESSING\",\"version\":3}", "PROCESSED", "2026-11-13T05:00:00Z");
        event("e-1b", "CK-1", "{\"id\":\"CK-1\",\"schoolId\":1,\"status\":\"PROCESSING\",\"version\":3,\"orderNumber\":\"FE-DPS_GUR-26-11-10-0002\",\"itemCode\":\"FE\"}",
                "PROCESSED", "2026-11-13T05:01:00Z");
        event("e-2", "CK-2", "{\"id\":\"CK-2\",\"schoolId\":1,\"status\":\"PROCESSING\",\"version\":2,\"orderNumber\":null}", "PROCESSED", "2026-11-13T05:01:00Z");
        event("e-3", "CK-3", "not json at all", "FAILED", "2026-11-13T05:01:00Z");
        event("e-9", "CK-9", "{\"id\":\"CK-9\",\"schoolId\":1,\"orderNumber\":\"NB-DPS_GUR-26-01-01-0001\"}", "PROCESSED", "2026-11-13T05:01:00Z");
        flyway(null).migrate();

        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA reporting TO app_rt").update();
        owner.sql("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA reporting TO app_rt").update();
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt");
        runtime = JdbcClient.create(dataSource);
        runtimeTransactions = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting");
        if (target != null) configuration = configuration.target(target);
        return configuration.load();
    }

    @Test
    void theNumbersAlreadyInTheInboxAreCopiedOntoTheFacts() {
        assertThat(number("CK-1")).as("from the re-emitted event, though an older one had none").isEqualTo("FE-DPS_GUR-26-11-10-0002");
        assertThat(number("CK-2")).as("an event without a number").isNull();
        assertThat(number("CK-3")).as("an unreadable payload is skipped, not an error").isNull();
        assertThat(owner.sql("SELECT count(*) FROM reporting.fact_catalog_order WHERE id = 'CK-9'").query(Long.class).single())
                .as("no fact is invented for an event that was never projected").isZero();
    }

    /**
     * The previous platform revision projects an event that carries a number with a projector that ignores it (its upsert,
     * verbatim from origin/dev), as the runtime role with row-level security forced, and marks it processed: the number
     * still reaches the fact. A later event without a number never clears it.
     */
    @Test
    void anEventThePreviousRevisionProjectsStillLeavesItsNumberOnTheFact() {
        record("e-4", "CK-4", "{\"id\":\"CK-4\",\"schoolId\":1,\"status\":\"PROCESSING\",\"version\":5,\"orderNumber\":\"FX-DPS_GUR-26-11-13-0007\"}");
        assertThat(processor(new PreviousCatalogProjector(runtime)).processBatch()).isEqualTo(1);
        assertThat(number("CK-4")).isEqualTo("FX-DPS_GUR-26-11-13-0007");

        record("e-4b", "CK-4", "{\"id\":\"CK-4\",\"schoolId\":1,\"status\":\"APPROVED\",\"version\":6}");
        assertThat(processor(new PreviousCatalogProjector(runtime)).processBatch()).isEqualTo(1);
        assertThat(number("CK-4")).isEqualTo("FX-DPS_GUR-26-11-13-0007");
        assertThat(owner.sql("SELECT status FROM reporting.fact_catalog_order WHERE id = 'CK-4'").query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test
    void thisRevisionsProjectorStoresTheNumberAsBefore() {
        record("e-5", "CK-5", "{\"id\":\"CK-5\",\"schoolId\":2,\"status\":\"ORDER_DETAILS\",\"version\":1,\"orderNumber\":\"NB-DPS2_GUR-26-11-13-0001\"}");
        var projector = new CatalogFactProjector(new CatalogFactReadRepository(runtime), new ObjectMapper());
        assertThat(processor(projector).processBatch()).isEqualTo(1);
        assertThat(number("CK-5")).isEqualTo("NB-DPS2_GUR-26-11-13-0001");
    }

    private static ReportingEventInboxProcessor processor(ReportingEventProjector projector) {
        return new ReportingEventInboxProcessor(new ReportingEventInboxRepository(runtime), new ReportingCommandRepository(runtime),
                List.of(projector), TraceContextBridge.noop(), 50, runtimeTransactions);
    }

    private static void record(String eventId, String orderId, String payload) {
        new ReportingEventInboxRepository(runtime).record(new ReportingEventInboxRepository.ReportingEventInboxRecord(eventId, null,
                "catalog-order.upserted.v1", "v1", "CatalogOrder", orderId, 1L, null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(),
                "{\"eventId\":\"" + eventId + "\",\"payload\":" + payload + "}", payload));
    }

    /** The previous revision's catalog projector: its upsert has no order_number (CatalogFactReadRepository at origin/dev). */
    private record PreviousCatalogProjector(JdbcClient jdbc) implements ReportingEventProjector {
        @Override public Set<String> handledEventTypes() { return Set.of("catalog-order.upserted.v1"); }
        @Override public boolean feedWorthy() { return false; }
        @Override public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
            var payload = new ObjectMapper().readTree(event.payload());
            ProjectorRls.allow(jdbc);
            jdbc.sql("""
                    INSERT INTO reporting.fact_catalog_order (id, school_id, category, status, total_amount, superadmin_approval_status,
                        form_version, pricing_status, source_version, updated_at)
                    VALUES (:id, :schoolId, 'FLEX', :status, 0, 'PENDING', 2, 'PENDING_PRICING', :sourceVersion, now())
                    ON CONFLICT (id) DO UPDATE SET school_id = EXCLUDED.school_id, status = EXCLUDED.status,
                        source_version = EXCLUDED.source_version, updated_at = now()
                    WHERE (reporting.fact_catalog_order.source_version IS NULL AND EXCLUDED.source_version IS NULL)
                       OR EXCLUDED.source_version >= COALESCE(reporting.fact_catalog_order.source_version, -1)
                    """).param("id", payload.get("id").asString()).param("schoolId", payload.get("schoolId").longValue())
                    .param("status", payload.get("status").asString()).param("sourceVersion", payload.get("version").longValue()).update();
        }
    }

    private static void fact(String id, long school) {
        owner.sql("""
                INSERT INTO reporting.fact_catalog_order (id, school_id, category, status, total_amount, superadmin_approval_status,
                    form_version, pricing_status, source_version, updated_at)
                VALUES (:id, :school, 'FLIERS', 'PROCESSING', 0, 'PENDING', 2, 'PENDING_PRICING', 3, now())
                """).param("id", id).param("school", school).update();
    }

    private static void event(String eventId, String orderId, String payload, String status, String receivedAt) {
        owner.sql("""
                INSERT INTO reporting.reporting_event_inbox (event_id, event_type, aggregate_type, aggregate_id, school_id, received_at,
                    status, envelope, payload)
                VALUES (:event, 'catalog-order.upserted.v1', 'CatalogOrder', :order, 1, CAST(:received AS timestamptz), :status, '{}', :payload)
                """).param("event", eventId).param("order", orderId).param("received", receivedAt).param("status", status)
                .param("payload", payload).update();
    }

    private static String number(String id) {
        return owner.sql("SELECT order_number FROM reporting.fact_catalog_order WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }
}
