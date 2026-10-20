package com.custoking.ims.migration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class MigrationOnlyMainIntegrationTest {
    @Test void ownerProcessRunsExactHistoryThenClosesAndRuntimeCredentialsCannotMutateHistory() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        try (var pg = new PostgreSQLContainer<>("postgres:16").withUsername("bootstrap").withPassword("bootstrap")) {
            pg.start();
            var admin = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),"bootstrap","bootstrap"));
            admin.sql("CREATE ROLE migration_owner LOGIN PASSWORD 'migration-only' NOSUPERUSER NOBYPASSRLS").update();
            admin.sql("GRANT CREATE ON DATABASE test TO migration_owner").update();
            var env = Map.of("APP_MIGRATION_SERVICE","platform-service","FLYWAY_URL",pg.getJdbcUrl(),"FLYWAY_USERNAME","migration_owner","FLYWAY_PASSWORD","migration-only");
            MigrationOnlyMain.migrate(env);
            MigrationOnlyMain.migrate(env); // Idempotent second rollout preserves existing histories/checksums.
            for (String schema : MigrationOnlyMain.plan("platform-service").schemas()) {
                String history = schema.equals("tenant_school") ? "flyway_schema_history_tenant_school" : "flyway_schema_history";
                assertThat(admin.sql("SELECT count(*) FROM " + schema + "." + history + " WHERE success").query(Long.class).single()).isPositive();
                assertThat(admin.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=:schema AND c.relrowsecurity AND NOT c.relforcerowsecurity")
                    .param("schema",schema).query(Long.class).single()).isZero();
            }
            assertThat(admin.sql("SELECT count(*) FROM pg_stat_activity WHERE usename='migration_owner'").query(Long.class).single()).isZero();
            admin.sql("CREATE ROLE isolated_runtime LOGIN PASSWORD 'runtime-only' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
            var runtime = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),"isolated_runtime","runtime-only"));
            String first = MigrationOnlyMain.plan("platform-service").schemas().getFirst();
            assertThatThrownBy(() -> runtime.sql("DROP SCHEMA " + first + " CASCADE").update())
                .rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> MigrationOnlyMain.plan("identity-service")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("match this image");
        }
    }
}
