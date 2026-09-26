package com.custoking.ims.identityservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

/** Runtime imports may strip defaults; deployed migration owners may grant too much. */
class PasswordResetRuntimePrivilegesIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetDeliveryConfirmationAndCleanupUseOnlyExplicitRuntimeGrants(boolean broadDefaults) {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        try (var pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg.start();
            var owner = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
            owner.execute("CREATE ROLE app_rt LOGIN PASSWORD 'runtime-only' NOSUPERUSER NOBYPASSRLS NOINHERIT");
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("identity").defaultSchema("identity")
                    .locations("classpath:db/migration").target("7").load().migrate();
            owner.execute("GRANT USAGE ON SCHEMA identity TO app_rt");
            // Existing objects retain their deployment grants; V8 must grant its own new objects.
            owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA identity TO app_rt");
            owner.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA identity TO app_rt");
            if (broadDefaults) {
                owner.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt");
                owner.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO PUBLIC");
            }
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("identity").defaultSchema("identity")
                    .locations("classpath:db/migration").load().migrate();
            var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "runtime-only");
            var jdbc = new JdbcTemplate(dataSource);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var resets = new PasswordResetRepository(jdbc);
            var quotas = new SharedQuotaRepository(jdbc);
            var encoder = new BCryptPasswordEncoder(4);
            assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            owner.update("INSERT INTO identity.app_users(full_name,email,password_hash,role,created_at) VALUES ('Runtime Test','runtime@example.invalid',?,'ADMIN',now())", encoder.encode("original-password"));
            long user = owner.queryForObject("SELECT id FROM identity.app_users WHERE email='runtime@example.invalid'", Long.class);

            resets.enqueue("runtime@example.invalid");
            var job = tx.execute(status -> resets.claim().orElseThrow());
            var issued = tx.execute(status -> resets.issueForDelivery(job, "R".repeat(43)));
            assertThat(issued).contains("runtime@example.invalid");
            resets.finishDelivery(job, true);
            Boolean confirmed = tx.execute(status -> resets.confirm("R".repeat(43), "replacement-password", encoder));
            Boolean replayed = tx.execute(status -> resets.confirm("R".repeat(43), "replacement-password", encoder));
            assertThat(confirmed).isTrue();
            assertThat(replayed).isFalse();
            assertThat(jdbc.queryForObject("SELECT credential_version FROM identity.app_users WHERE id=?", Long.class, user)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT status FROM identity.password_reset_deliveries WHERE id=?", String.class, job.id())).isEqualTo("SENT");

            owner.update("INSERT INTO identity.request_quotas(quota_key,used,expires_at) VALUES ('expired',1,now()-interval '2 days')");
            owner.update("INSERT INTO identity.password_reset_tokens(token_hash,user_id,credential_version,expires_at) VALUES (repeat('f',64),?,0,now()-interval '2 days')", user);
            owner.update("INSERT INTO identity.password_reset_deliveries(id,user_id,credential_version,created_at,status) VALUES(gen_random_uuid(),?,0,now()-interval '8 days','SENT')", user);
            for (int i=0;i<128;i++) assertThat(quotas.consume("runtime-quota", 128, 60)).isTrue();
            assertThat(quotas.consume("runtime-quota", 128, 60)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.request_quotas WHERE quota_key='expired'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.password_reset_tokens WHERE token_hash=repeat('f',64)", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.password_reset_deliveries", Long.class)).isEqualTo(1L);
            for (String table : new String[]{"request_quotas","password_reset_tokens","password_reset_deliveries"}) {
                assertThatThrownBy(() -> jdbc.execute("TRUNCATE identity."+table))
                        .rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            }
        }
    }
}
