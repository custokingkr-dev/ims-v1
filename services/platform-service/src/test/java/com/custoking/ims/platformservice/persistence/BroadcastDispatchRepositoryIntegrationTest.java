package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BroadcastDispatchRepositoryIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner, app;
    static TransactionTemplate transaction;
    static BroadcastDispatchRepository repository;
    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner"); pg.start();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
        owner.sql("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt").update();
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration/notification").load().migrate();
        owner.sql("GRANT USAGE ON SCHEMA notification TO app_rt").update();
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "test"); app = JdbcClient.create(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new BroadcastDispatchRepository(app, new ObjectMapper());
    }
    @AfterAll static void close() { if (pg != null) pg.stop(); }
    @BeforeEach void clean() { owner.sql("DELETE FROM notification.notification_broadcast_recipients").update(); owner.sql("DELETE FROM notification.notification_broadcasts").update(); }
    UUID seed(long school) {
        UUID id = UUID.randomUUID(); owner.sql("INSERT INTO notification.notification_broadcasts (id,school_id,title,message,audience_type,channels,communication_category,status) VALUES (:id,:school,'Notice','Message','ALL_PARENTS','SMS','SCHOOL_NOTICE','DRAFT')").param("id",id).param("school",school).update(); return id;
    }
    Recipient recipient(UUID id, long student, boolean allowed) { return new Recipient(10,student,"SMS","broadcast:"+id+":"+student+":SMS",allowed, allowed ? "ALLOWED" : "SCHOOL_COMMUNICATIONS_NOT_GRANTED","guardian","9999999999","same-hash", Map.of("consentEventId","grant")); }
    void scope(long school) { app.sql("SELECT set_config('app.current_school_id', :school, true)").param("school",String.valueOf(school)).query(String.class).single(); }
    @Test void migrationManifestDedupeQueueAndCompletionNeverClaimSent() {
        UUID id = seed(10);
        transaction.executeWithoutResult(tx -> {
            scope(10); var broadcast = repository.find(id,true);
            var recipients = List.of(recipient(id,1,true),recipient(id,2,true),recipient(id,3,false));
            repository.approve(broadcast,recipients,5L); repository.approve(broadcast,recipients,5L);
            assertThat(repository.outcomes(repository.find(id,false))).containsEntry("total",3);
            repository.queue(repository.find(id,true),"DRY_RUN",5L);
            assertThat(repository.find(id,false).status()).isEqualTo("QUEUED");
        });
        transaction.executeWithoutResult(tx -> {
            var row = repository.claim(OffsetDateTime.now()).orElseThrow();
            assertThat(row.schoolId()).isEqualTo(10); assertThat(row.studentId()).isEqualTo(1);
            repository.outcome(row,"DRY_RUN",null,"logging",null);
            var result = repository.outcomes(repository.find(id,false));
            assertThat(result).containsEntry("status","DRY_RUN_COMPLETE").containsEntry("delivered",0);
            assertThat((Map<?,?>)result.get("counts")).hasSize(3);
            assertThat(repository.retry(repository.find(id,true))).isZero();
            assertThat(repository.claim(OffsetDateTime.now())).isEmpty();
        });
        assertThat(owner.sql("SELECT sent_at IS NULL FROM notification.notification_broadcasts WHERE id = :id").param("id",id).query(Boolean.class).single()).isTrue();
    }
    @Test void recipientManifestIsInvisibleAndImmutableAcrossSchools() {
        UUID id = seed(10);
        transaction.executeWithoutResult(tx -> { scope(10); repository.approve(repository.find(id,true),List.of(recipient(id,1,true)),5L); });
        transaction.executeWithoutResult(tx -> {
            scope(20);
            assertThat(app.sql("SELECT count(*) FROM notification.notification_broadcast_recipients").query(Long.class).single()).isZero();
            assertThat(app.sql("UPDATE notification.notification_broadcast_recipients SET status = 'QUEUED' WHERE broadcast_id = :id").param("id",id).update()).isZero();
            assertThatThrownBy(() -> repository.find(id,false)).hasMessageContaining("not found");
        });
    }
    @Test void failedChecksKeepStableEventIdAndRespectRetryLimit() {
        UUID id = seed(10);
        transaction.executeWithoutResult(tx -> { scope(10); repository.approve(repository.find(id,true),List.of(recipient(id,1,true)),5L); repository.queue(repository.find(id,true),"DRY_RUN",5L); });
        transaction.executeWithoutResult(tx -> {
            var row = repository.claim(OffsetDateTime.now()).orElseThrow();
            repository.outcome(row,"FAILED","POLICY_OR_DELIVERY_UNAVAILABLE",null,OffsetDateTime.now().plusHours(1));
            assertThat(repository.claim(OffsetDateTime.now())).isEmpty();
            assertThat(repository.retry(repository.find(id,true))).isEqualTo(1);
            var retry = repository.claim(OffsetDateTime.now().plusSeconds(1)).orElseThrow();
            assertThat(retry.eventId()).isEqualTo(row.eventId()); assertThat(retry.attempts()).isEqualTo(1);
            repository.outcome(retry,"DEAD_LETTER","POLICY_OR_DELIVERY_UNAVAILABLE",null,null);
            assertThat(repository.retry(repository.find(id,true))).isZero();
            assertThat(repository.find(id,false).status()).isEqualTo("COMPLETED_WITH_FAILURES");
        });
    }

    @Test void runtimeCanAdvanceQueueButCannotReplaceOrEraseApprovedRecipientIdentity() {
        UUID id = seed(10);
        transaction.executeWithoutResult(tx -> { scope(10); repository.approve(repository.find(id,true),List.of(recipient(id,1,true)),5L); });
        for (String sql : new String[] {
                "UPDATE notification.notification_broadcast_recipients SET event_id='forged'",
                "UPDATE notification.notification_broadcast_recipients SET destination_sha256='forged'",
                "DELETE FROM notification.notification_broadcast_recipients",
                "TRUNCATE notification.notification_broadcast_recipients"}) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
                app.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();
                app.sql(sql).update();
            })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        transaction.executeWithoutResult(tx -> { scope(10); repository.queue(repository.find(id,true),"DRY_RUN",5L); });
        transaction.executeWithoutResult(tx -> {
            var row = repository.claim(OffsetDateTime.now()).orElseThrow();
            repository.outcome(row,"DRY_RUN",null,"logging",null);
            assertThat(repository.find(id,false).status()).isEqualTo("DRY_RUN_COMPLETE");
        });
    }
}
