package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.GenericNotificationReport;
import com.custoking.ims.platformservice.application.NotificationSubmissionResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Full three-schema chain, actual broad owner defaults and dedicated non-bypass runtime. */
class GenericNotificationReportIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner,app;
    static TransactionTemplate tx,ownerTx;
    static GenericNotificationReportRepository reports;
    static GenericNotificationSubmissionRepository submissions;
    private static final String EVENT="owned-report-event",REQUEST="a".repeat(64),PROVIDER="5762846b4f8d285d378b4567";
    @BeforeAll static void setup() throws Exception {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        pg=new PostgreSQLContainer<>("postgres:16").withUsername("postgres").withPassword("owner");pg.start();
        var data=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        owner=JdbcClient.create(data);ownerTx=new TransactionTemplate(new JdbcTransactionManager(data));ownerTx.setTimeout(10);
        Path repository=Path.of("").toAbsolutePath();
        while(repository!=null && !Files.exists(repository.resolve("deploy/local/initdb/00-app-rt.sql")))repository=repository.getParent();
        if(repository==null)throw new IllegalStateException("Actual runtime bootstrap unavailable");
        owner.sql(Files.readString(repository.resolve("deploy/local/initdb/00-app-rt.sql"))).update();
        owner.sql("CREATE SCHEMA reporting; CREATE SCHEMA notification; CREATE SCHEMA audit; CREATE ROLE ims_platform_rt LOGIN PASSWORD 'synthetic-only' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        pg.copyFileToContainer(MountableFile.forHostPath(repository.resolve("scripts/create-app-rt-role.sql")),"/tmp/actual-bootstrap.sql");
        var bootstrap=pg.execInContainer("psql","-U","postgres","-d",pg.getDatabaseName(),"-v","owner=postgres","-v","app_rt_password=synthetic-only","-f","/tmp/actual-bootstrap.sql");
        assertThat(bootstrap.getExitCode()).isZero();
        // Assert broad global/schema defaults really precede the final restrictive migration.
        owner.sql("ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA notification GRANT ALL ON TABLES TO app_rt; CREATE TABLE notification.default_acl_probe(id bigint)").update();
        assertThat(owner.sql("SELECT has_table_privilege('app_rt','notification.default_acl_probe','DELETE') AND has_table_privilege('app_rt','notification.default_acl_probe','TRUNCATE')").query(Boolean.class).single()).isTrue();
        owner.sql("DROP TABLE notification.default_acl_probe").update();
        for(String schema:List.of("reporting","notification","audit")) {
            Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/"+schema).load().migrate();
        }
        try(var stream=GenericNotificationReportIntegrationTest.class.getResourceAsStream("/db/migration/notification/afterMigrate__dedicated_runtime_acl.sql")) {
            String callback=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            owner.sql(callback).update();owner.sql(callback).update();
        }
        var runtime=new DriverManagerDataSource(pg.getJdbcUrl(),"ims_platform_rt","synthetic-only");
        app=JdbcClient.create(runtime);tx=new TransactionTemplate(new JdbcTransactionManager(runtime));tx.setTimeout(10);
        reports=new GenericNotificationReportRepository(app);submissions=new GenericNotificationSubmissionRepository(app);
        assertThat(owner.sql("SELECT NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb) FROM pg_roles WHERE rolname='ims_platform_rt'").query(Boolean.class).single()).isTrue();
    }
    @AfterAll static void stop(){if(pg!=null)pg.stop();}
    @BeforeEach void clean(){owner.sql("TRUNCATE notification.generic_delivery_reports,notification.generic_delivery_results,notification.generic_submissions,notification.notification_delivery_attempts,notification.notification_inbox_events,reporting.student_projection_tombstones").update();}
    private void seed(String submission,String inbox) {
        owner.sql("INSERT INTO notification.generic_submissions(event_id,school_id,request_sha256,status,correlation_id,provider_request_id,submitted_at) VALUES(:event,10,:hash,:status,:correlation,:provider,now()-interval '1 minute')")
                .param("event",EVENT).param("hash",REQUEST).param("status",submission).param("correlation",NotificationSubmissionResult.correlationId(EVENT))
                .param("provider","ACCEPTED".equals(submission)?PROVIDER:null).update();
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status) VALUES(:event,'notification.requested.v1','{\"schoolId\":10,\"studentId\":301,\"channel\":\"SMS\"}',:status)")
                .param("event",EVENT).param("status",inbox).update();
    }
    private GenericNotificationReport report(GenericNotificationReport.Status status,String evidence) {
        return new GenericNotificationReport(10,EVENT,REQUEST,NotificationSubmissionResult.correlationId(EVENT),PROVIDER,status,
                OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS),evidence.repeat(64));
    }
    private boolean apply(GenericNotificationReport report){return Boolean.TRUE.equals(tx.execute(t->reports.reconcile(report)));}
    private String delivery(){return owner.sql("SELECT delivery_status FROM notification.generic_delivery_results WHERE event_id=:event").param("event",EVENT).query(String.class).single();}
    private long evidenceCount(){return owner.sql("SELECT count(*) FROM notification.generic_delivery_reports").query(Long.class).single();}
    @Test void repeatedAcceptedReportsNeverRewriteSubmissionOrMakeAnyReservationRetryable() {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");
        assertThat(apply(delivered)).isTrue();assertThat(apply(delivered)).isTrue();assertThat(evidenceCount()).isEqualTo(1);assertThat(delivery()).isEqualTo("DELIVERED");
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions").query(String.class).single()).isEqualTo("ACCEPTED");
        assertThat(owner.sql("SELECT status FROM notification.notification_inbox_events").query(String.class).single()).isEqualTo("ACCEPTED");
        assertThat((Boolean)tx.execute(t->submissions.reserve(EVENT,10,REQUEST))).isFalse();
    }
    @Test void exactBindingUnknownAttemptsAndUntrustedTimesNeverInventDelivery() {
        seed("ACCEPTED","ACCEPTED");var valid=report(GenericNotificationReport.Status.DELIVERED,"b");
        for(var bad:List.of(new GenericNotificationReport(20,EVENT,REQUEST,valid.correlationId(),PROVIDER,valid.status(),valid.occurredAt(),valid.evidenceSha256()),
                new GenericNotificationReport(10,"unknown-event",REQUEST,NotificationSubmissionResult.correlationId("unknown-event"),PROVIDER,valid.status(),valid.occurredAt(),valid.evidenceSha256()),
                new GenericNotificationReport(10,EVENT,"c".repeat(64),valid.correlationId(),PROVIDER,valid.status(),valid.occurredAt(),valid.evidenceSha256()),
                new GenericNotificationReport(10,EVENT,REQUEST,valid.correlationId(),"different-provider",valid.status(),valid.occurredAt(),valid.evidenceSha256()),
                new GenericNotificationReport(10,EVENT,REQUEST,valid.correlationId(),PROVIDER,valid.status(),valid.occurredAt().minusDays(1),valid.evidenceSha256()),
                new GenericNotificationReport(10,EVENT,REQUEST,valid.correlationId(),PROVIDER,valid.status(),valid.occurredAt().plusDays(1),valid.evidenceSha256()))) assertThat(apply(bad)).isFalse();
        assertThat(evidenceCount()).isZero();
        for(String uncertainty:List.of("UNKNOWN","SUBMITTING","REJECTED")) {
            clean();seed(uncertainty,uncertainty);assertThat(apply(valid)).isFalse();
            assertThat(evidenceCount()).isZero();assertThat((Boolean)tx.execute(t->submissions.reserve(EVENT,10,REQUEST))).isFalse();
            assertThat(owner.sql("SELECT status FROM notification.generic_submissions").query(String.class).single()).isEqualTo(uncertainty);
        }
    }
    @Test void concurrentDuplicateReportsHaveOneEvidenceAndContradictionsAreTerminal() throws Exception {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("start deadline");return apply(delivered);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();assertThat(b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(evidenceCount()).isEqualTo(1);
        assertThat(apply(report(GenericNotificationReport.Status.DELIVERY_FAILED,"c"))).isTrue();assertThat(delivery()).isEqualTo("REPORT_CONFLICT");
        assertThat(apply(report(GenericNotificationReport.Status.DELIVERED,"d"))).isTrue();assertThat(delivery()).isEqualTo("REPORT_CONFLICT");assertThat(evidenceCount()).isEqualTo(3);
    }
    @Test void acceptedAfterDeliveryCannotDowngradeButChangedSameEvidenceFailsClosed() {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");apply(delivered);
        apply(report(GenericNotificationReport.Status.ACCEPTED,"c"));assertThat(delivery()).isEqualTo("DELIVERED");
        apply(new GenericNotificationReport(10,EVENT,REQUEST,delivered.correlationId(),PROVIDER,delivered.status(),delivered.occurredAt().plusSeconds(1),delivered.evidenceSha256()));
        assertThat(delivery()).isEqualTo("REPORT_CONFLICT");
    }
    @Test void concurrentContraryReportsCommitBothEvidenceAndTerminalConflict() throws Exception {
        seed("ACCEPTED","ACCEPTED");var start=new CountDownLatch(1);
        var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");var failed=report(GenericNotificationReport.Status.DELIVERY_FAILED,"c");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{if(!start.await(3,TimeUnit.SECONDS))throw new IllegalStateException("start deadline");return apply(delivered);});
            var second=pool.submit(()->{if(!start.await(3,TimeUnit.SECONDS))throw new IllegalStateException("start deadline");return apply(failed);});
            start.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();assertThat(second.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(evidenceCount()).isEqualTo(2);assertThat(delivery()).isEqualTo("REPORT_CONFLICT");
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions").query(String.class).single()).isEqualTo("ACCEPTED");
    }
    @Test void rollbackLosesNoOriginalSubmissionAndReportReplayAfterLostResponseIsIdempotent() {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");
        assertThatThrownBy(()->tx.executeWithoutResult(t->{reports.reconcile(delivered);throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(evidenceCount()).isZero();assertThat(owner.sql("SELECT count(*) FROM notification.generic_delivery_results").query(Long.class).single()).isZero();
        apply(delivered); // Simulate committed result whose HTTP acknowledgement was lost.
        var restarted=new GenericNotificationReportRepository(app);
        tx.executeWithoutResult(t->restarted.reconcile(delivered));assertThat(evidenceCount()).isEqualTo(1);assertThat(delivery()).isEqualTo("DELIVERED");
    }
    @Test void realTombstoneErasureTerminallySuppressesEarlierDeliveryAndBlocksLateEvidence() {
        seed("ACCEPTED","ACCEPTED");apply(report(GenericNotificationReport.Status.DELIVERED,"b"));
        owner.sql("INSERT INTO reporting.student_projection_tombstones(student_id,deleted_at) VALUES(301,now())").update();
        assertThat(delivery()).isEqualTo("SUPPRESSED");
        assertThat(owner.sql("SELECT status FROM notification.notification_inbox_events").query(String.class).single()).isEqualTo("SUPPRESSED");
        assertThat(apply(report(GenericNotificationReport.Status.DELIVERY_FAILED,"c"))).isFalse();assertThat(evidenceCount()).isEqualTo(1);
        assertThatThrownBy(()->tx.executeWithoutResult(t->{app.sql("SELECT set_config('app.current_school_id','10',true)").query(String.class).single();
            app.sql("UPDATE notification.generic_delivery_results SET delivery_status='DELIVERED'").update();})).rootCause().hasMessageContaining("cannot be downgraded");
    }
    @Test void reportWaitsForErasureCommitAndSuppressionWinsWithoutAppendingEvidence() throws Exception {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var erasure=pool.submit(()->ownerTx.executeWithoutResult(t->{
                owner.sql("UPDATE notification.notification_inbox_events SET status='SUPPRESSED',payload='{\"redacted\":true}' WHERE event_id=:event").param("event",EVENT).update();locked.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("release deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();var receipt=pool.submit(()->apply(delivered));
            // An independent connection observes the exact PostgreSQL blocked reader, not a timing guess.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean waiting=false;
            while(System.nanoTime()<deadline && !waiting) {
                waiting=owner.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE usename='ims_platform_rt' AND wait_event_type='Lock')").query(Boolean.class).single();
                if(!waiting)Thread.sleep(10);
            }
            assertThat(waiting).isTrue();release.countDown();erasure.get(5,TimeUnit.SECONDS);assertThat(receipt.get(5,TimeUnit.SECONDS)).isFalse();
        } finally{release.countDown();}
        assertThat(evidenceCount()).isZero();assertThat(owner.sql("SELECT count(*) FROM notification.generic_delivery_results").query(Long.class).single()).isZero();
    }
    @Test void erasureWaitsForReportCommitAndThenAtomicallySuppressesItsResult() throws Exception {
        seed("ACCEPTED","ACCEPTED");var delivered=report(GenericNotificationReport.Status.DELIVERED,"b");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var receipt=pool.submit(()->tx.executeWithoutResult(t->{
                assertThat(reports.reconcile(delivered)).isTrue();locked.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("release deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
            var erasure=pool.submit(()->owner.sql("INSERT INTO reporting.student_projection_tombstones(student_id,deleted_at) VALUES(301,now())").update());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean waiting=false;
            while(System.nanoTime()<deadline && !waiting) {
                waiting=owner.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE usename='postgres' AND wait_event_type='Lock' AND query LIKE 'INSERT INTO reporting.student_projection_tombstones%')").query(Boolean.class).single();
                if(!waiting)Thread.sleep(10);
            }
            assertThat(waiting).isTrue();release.countDown();receipt.get(5,TimeUnit.SECONDS);assertThat(erasure.get(5,TimeUnit.SECONDS)).isEqualTo(1);
        } finally{release.countDown();}
        assertThat(evidenceCount()).isEqualTo(1);assertThat(delivery()).isEqualTo("SUPPRESSED");
    }
    @Test void broadDefaultsCannotReintroduceMutableEvidenceOrBindingGrantsInDedicatedRole() {
        seed("ACCEPTED","ACCEPTED");apply(report(GenericNotificationReport.Status.ACCEPTED,"b"));
        for(String table:List.of("generic_delivery_results","generic_delivery_reports")) {
            assertThat(owner.sql("SELECT has_table_privilege('ims_platform_rt',:table,'DELETE') OR has_table_privilege('ims_platform_rt',:table,'TRUNCATE') OR has_table_privilege('ims_platform_rt',:table,'TRIGGER') OR has_table_privilege('ims_platform_rt',:table,'REFERENCES')")
                    .param("table","notification."+table).query(Boolean.class).single()).isFalse();
        }
        assertThat(owner.sql("SELECT has_table_privilege('ims_platform_rt','notification.generic_delivery_reports','INSERT') OR has_table_privilege('ims_platform_rt','notification.generic_delivery_reports','UPDATE')").query(Boolean.class).single()).isFalse();
        assertThat(owner.sql("SELECT has_column_privilege('ims_platform_rt','notification.generic_delivery_reports','status','INSERT')").query(Boolean.class).single()).isTrue();
        assertThat(owner.sql("SELECT has_column_privilege('ims_platform_rt','notification.generic_delivery_reports','received_at','INSERT') OR has_column_privilege('ims_platform_rt','notification.generic_delivery_results','provider_request_id','UPDATE')").query(Boolean.class).single()).isFalse();
        assertThat(owner.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN LATERAL aclexplode(c.relacl) a WHERE n.nspname='notification' AND c.relname IN ('generic_delivery_results','generic_delivery_reports') AND a.grantee=0").query(Long.class).single()).isZero();
        assertThat(app.sql("SELECT count(*) FROM notification.generic_delivery_reports").query(Long.class).single()).isZero();
        assertThatThrownBy(()->app.sql("DELETE FROM notification.generic_delivery_reports").update()).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(()->tx.executeWithoutResult(t->{app.sql("SELECT set_config('app.current_school_id','10',true)").query(String.class).single();
            app.sql("UPDATE notification.generic_delivery_results SET delivery_status='DELIVERED'").update();})).rootCause().hasMessageContaining("matching appended evidence");
        assertThat(delivery()).isEqualTo("ACCEPTED");
    }
}
