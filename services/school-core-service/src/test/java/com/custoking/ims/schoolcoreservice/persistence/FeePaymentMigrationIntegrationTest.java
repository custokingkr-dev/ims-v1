package com.custoking.ims.schoolcoreservice.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

class FeePaymentMigrationIntegrationTest {
    @Test void upgradeRetainsHistoricalCollisionsButRejectsNewDuplicateNumbers() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        try (var pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg.start();
            for (String schema : new String[] {"tenant_school", "student", "fee"}) {
                var configuration = Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner")
                        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/" + schema);
                if (schema.equals("fee")) configuration.target("9");
                configuration.load().migrate();
            }
            var jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
            jdbc.sql("""
                INSERT INTO fee.payment_records(id, amount, school_id, student_id, receipt_number)
                VALUES ('old-one', 100, 10, 1, 'RCPT-OLD'), ('old-two', 200, 20, 2, 'RCPT-OLD'),
                       ('reserved', 300, 30, 3, 'RCPT-V2-12')
                """).update();
            jdbc.sql("CREATE ROLE fee_migrator LOGIN PASSWORD 'fixture-only' NOSUPERUSER NOBYPASSRLS").update();
            jdbc.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime-only' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
            jdbc.sql("ALTER DEFAULT PRIVILEGES FOR ROLE fee_migrator GRANT ALL ON SEQUENCES TO app_rt").update();
            jdbc.sql("GRANT USAGE ON SCHEMA fee TO app_rt").update();
            jdbc.sql("ALTER SCHEMA fee OWNER TO fee_migrator").update();
            jdbc.sql("ALTER TABLE fee.flyway_schema_history OWNER TO fee_migrator").update();
            jdbc.sql("ALTER TABLE fee.payment_records OWNER TO fee_migrator").update();
            jdbc.sql("ALTER TABLE fee.payment_records FORCE ROW LEVEL SECURITY").update();
            try (var migrationPool = new HikariDataSource()) {
                migrationPool.setJdbcUrl(pg.getJdbcUrl());
                migrationPool.setUsername("fee_migrator");
                migrationPool.setPassword("fixture-only");
                // Flyway keeps its schema-history connection while running migrations.
                migrationPool.setMaximumPoolSize(2);
                migrationPool.setMinimumIdle(1);
                migrationPool.setConnectionInitSql("SELECT set_config('app.current_school_id', '10', false), set_config('app.bypass_rls', 'off', false)");
                var scopedJdbc = JdbcClient.create(migrationPool);
                assertThat(scopedJdbc.sql("SELECT count(*) FROM fee.payment_records").query(Long.class).single()).isEqualTo(1);
                Flyway.configure().dataSource(migrationPool)
                        .schemas("fee").defaultSchema("fee").locations("classpath:db/migration/fee").load().migrate();
                // Reused migration connections must retain their original tenant scope.
                assertThat(scopedJdbc.sql("SELECT current_setting('app.bypass_rls')").query(String.class).single()).isEqualTo("off");
                assertThat(scopedJdbc.sql("SELECT count(*) FROM fee.payment_records").query(Long.class).single()).isEqualTo(1);
            }
            assertThat(jdbc.sql("SELECT count(*) FROM fee.payment_records WHERE receipt_number = 'RCPT-OLD' AND legacy_receipt_collision").query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT nextval('fee.payment_receipt_seq')").query(Long.class).single()).isEqualTo(13);
            var runtime = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "runtime-only"));
            assertThat(runtime.sql("SELECT nextval('fee.payment_receipt_seq')").query(Long.class).single()).isEqualTo(14);
            assertThatThrownBy(() -> runtime.sql("SELECT setval('fee.payment_receipt_seq',1,false)").query(Long.class).single())
                    .rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThat(runtime.sql("SELECT nextval('fee.payment_receipt_seq')").query(Long.class).single()).isEqualTo(15);
            var receipts = new FeeReceiptRepository(jdbc, new FeeDocumentRenderer());
            assertThat(receipts.byPaymentId("old-two")).containsEntry("amount", 200L);
            assertThatThrownBy(() -> receipts.byReceiptNumber("RCPT-OLD")).isInstanceOf(PaymentConflictException.class);
            assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO fee.payment_records(id, amount, school_id, student_id, receipt_number)
                VALUES ('duplicate', 300, 10, 3, 'RCPT-V2-12')
                """).update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }
    }
}
