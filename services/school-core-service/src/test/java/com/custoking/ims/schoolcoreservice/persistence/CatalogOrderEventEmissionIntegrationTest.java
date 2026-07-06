package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves catalog order mutations emit {@code catalog-order.upserted.v1} onto
 * tenant_school.outbox_events inside the same transaction (Reporting Decoupling SP4, mirrors
 * {@link ReferenceEventEmissionIntegrationTest}).
 */
class CatalogOrderEventEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static CatalogReadRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "catalog"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load()
                    .migrate();
        }
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        repo = new CatalogReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM catalog.catalog_orders");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("""
                    INSERT INTO tenant_school.schools
                        (id, name, short_code, active, created_at)
                    VALUES (1, 'Test School', 'TS', true, now())
                    """);
        }
    }

    static long countOutbox() {
        return jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();
    }

    @Test
    void createOrderEmitsCatalogOrderUpsertedInSameTransaction() {
        Map<String, Object> request = Map.of(
                "schoolId", 1,
                "category", "STATIONERY",
                "subtotal", 1000,
                "gst", 180,
                "totalAmount", 1180,
                "status", "DRAFT");

        var created = repo.createOrder(request);

        List<String[]> rows = jdbc.sql("""
                SELECT event_type, event_key
                FROM tenant_school.outbox_events
                WHERE aggregate_type = 'CatalogOrder' AND aggregate_id = :id
                """)
                .param("id", created.id())
                .query((rs, n) -> new String[] {rs.getString("event_type"), rs.getString("event_key")})
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("catalog-order.upserted.v1");
        assertThat(rows.get(0)[1]).isEqualTo("CatalogOrderUpserted:" + created.id());
    }

    @Test
    void placeOrderEmitsAnotherCatalogOrderUpsertedEvent() {
        var created = repo.createOrder(Map.of(
                "schoolId", 1,
                "category", "STATIONERY",
                "subtotal", 1000,
                "gst", 180,
                "totalAmount", 1180,
                "status", "DRAFT"));
        long before = countOutbox();

        repo.placeOrder(created.id(), null);

        assertThat(countOutbox()).isEqualTo(before + 1);
        String latestType = jdbc.sql("""
                SELECT event_type FROM tenant_school.outbox_events
                WHERE aggregate_type = 'CatalogOrder' AND aggregate_id = :id
                ORDER BY id DESC LIMIT 1
                """)
                .param("id", created.id())
                .query(String.class)
                .single();
        assertThat(latestType).isEqualTo("catalog-order.upserted.v1");
    }
}
