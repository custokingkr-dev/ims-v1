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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a fee band that still has student fee assignments must be blocked with a
 * clear message (deleting it would violate the fee_assignments.band_id FK / orphan
 * student fee + payment data). An unused band deletes cleanly.
 */
class FeeBandDeleteIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static FeeReadRepository repo;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        // bandRecord() LEFT JOINs tenant_school.academic_years, so migrate both schemas.
        for (String schema : new String[] {"tenant_school", "fee"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        JdbcClient jdbc = JdbcClient.create(ds);
        repo = new FeeReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM fee.payment_records");
            st.execute("DELETE FROM fee.fee_assignments");
            st.execute("DELETE FROM fee.fee_items");
            st.execute("DELETE FROM fee.fee_bands");
            st.execute("INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id, school_id) VALUES " +
                    "('band-empty', 'Empty', 1, 2, 0.0, 'AY', 1), ('band-inuse', 'InUse', 3, 4, 0.0, 'AY', 1)");
            st.execute("INSERT INTO fee.fee_items(id, name, frequency, amount, band_id, school_id) VALUES " +
                    "('item-1', 'Tuition', 'Annual', 3000000, 'band-empty', 1)");
            st.execute("INSERT INTO fee.fee_assignments" +
                    "(id, band_discount, manual_discount, surcharge, net_payable, paid_amount, student_id, band_id, academic_year_id, version, school_id) VALUES " +
                    "('fa-1', 0.0, 0.0, 0.0, 5000, 0, 1, 'band-inuse', 'AY', 0, 1)");
        }
    }

    @Test
    void deleteBand_withAssignments_isBlockedWithCount() {
        assertThatThrownBy(() -> repo.deleteBand("band-inuse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 student");
        // Band and its assignment are untouched.
        long bands = JdbcClient.create(ds).sql("SELECT count(*) FROM fee.fee_bands WHERE id = 'band-inuse'")
                .query(Long.class).single();
        assertThat(bands).isEqualTo(1L);
    }

    @Test
    void deleteBand_unused_removesBandAndItems() {
        repo.deleteBand("band-empty");
        long bands = JdbcClient.create(ds).sql("SELECT count(*) FROM fee.fee_bands WHERE id = 'band-empty'")
                .query(Long.class).single();
        long items = JdbcClient.create(ds).sql("SELECT count(*) FROM fee.fee_items WHERE band_id = 'band-empty'")
                .query(Long.class).single();
        assertThat(bands).isZero();
        assertThat(items).isZero();
    }
}
