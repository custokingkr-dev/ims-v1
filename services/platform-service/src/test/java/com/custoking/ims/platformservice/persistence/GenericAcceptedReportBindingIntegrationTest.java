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

/** Actual complete notification/reporting/audit chains and non-bypass dedicated runtime. */
class GenericAcceptedReportBindingIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner,app;
    static TransactionTemplate tx,ownerTx;
    static GenericNotificationReportRepository reports;
    static GenericNotificationSubmissionRepository submissions;
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
        try(var stream=GenericAcceptedReportBindingIntegrationTest.class.getResourceAsStream("/db/migration/notification/afterMigrate__dedicated_runtime_acl.sql")) {
            String callback=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            owner.sql(callback).update();owner.sql(callback).update();
        }
        var runtime=new DriverManagerDataSource(pg.getJdbcUrl(),"ims_platform_rt","synthetic-only");
        app=JdbcClient.create(runtime);tx=new TransactionTemplate(new JdbcTransactionManager(runtime));tx.setTimeout(10);
        reports=new GenericNotificationReportRepository(app);submissions=new GenericNotificationSubmissionRepository(app);
        assertThat(owner.sql("SELECT NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb) FROM pg_roles WHERE rolname='ims_platform_rt'").query(Boolean.class).single()).isTrue();
    }
    @AfterAll static void stop(){if(pg!=null)pg.stop();}

    @BeforeEach void clean(){owner.sql("TRUNCATE notification.generic_unknown_report_assertions,notification.generic_delivery_reports,notification.generic_delivery_results,notification.generic_submissions,notification.notification_delivery_attempts,notification.notification_inbox_events,reporting.student_projection_tombstones").update();}
    private void seed(String state,long school,String event,String inbox) {
        owner.sql("INSERT INTO notification.generic_submissions(event_id,school_id,request_sha256,status,correlation_id,provider_request_id,submitted_at) VALUES(:event,:school,:hash,:state,:correlation,:provider,now()-interval '1 minute')")
            .param("event",event).param("school",school).param("hash","a".repeat(64)).param("state",state)
            .param("correlation",NotificationSubmissionResult.correlationId(event)).param("provider",state.equals("ACCEPTED")?"synthetic-provider":null).update();
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status) VALUES(:event,'notification.requested.v1',:payload,:state)")
            .param("event",event).param("payload","{\"schoolId\":"+school+",\"studentId\":301,\"channel\":\"SMS\"}").param("state",inbox).update();
    }
    private Optional<GenericAcceptedReportBindingRepository.Binding> lookup(long school,String event){return tx.execute(t->new GenericAcceptedReportBindingRepository(app).find(school,event));}
    @Test void onlyExactAcceptedSchoolBindingIsVisibleUnderForcedRls() {
        seed("ACCEPTED",10,"owned-accepted","ACCEPTED");seed("ACCEPTED",20,"foreign-accepted","ACCEPTED");
        assertThat(lookup(10,"owned-accepted")).isPresent();assertThat(lookup(10,"foreign-accepted")).isEmpty();
        assertThat(lookup(20,"owned-accepted")).isEmpty();
        assertThat(owner.sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='notification.generic_submissions'::regclass").query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->new GenericAcceptedReportBindingRepository(app).find(10,"owned-accepted")).hasMessage("Report binding requires transaction");
        assertThatThrownBy(()->lookup(10,"injection' OR true--")).hasMessage("Invalid report lookup scope");
    }
    @Test void UnknownRejectedReservedAndSuppressedNeverYieldReportBinding() {
        for(String state:List.of("UNKNOWN","REJECTED","SUBMITTING")){seed(state,10,"event-"+state,state);assertThat(lookup(10,"event-"+state)).isEmpty();}
        seed("ACCEPTED",10,"erased-event","SUPPRESSED");assertThat(lookup(10,"erased-event")).isEmpty();
        assertThat(owner.sql("SELECT count(*) FROM notification.generic_delivery_results").query(Long.class).single()).isZero();
    }
    @Test void acceptedPayloadCannotBeChangedAndErasureSuppressesBeforeLookup() {
        seed("ACCEPTED",10,"erase-event","ACCEPTED");
        assertThatThrownBy(()->owner.sql("UPDATE notification.notification_inbox_events SET payload='{}' WHERE event_id='erase-event'").update()).hasMessageContaining("Reserved notification cannot be reset");
        owner.sql("INSERT INTO reporting.student_projection_tombstones(student_id,deleted_at) VALUES(301,now())").update();
        assertThat(lookup(10,"erase-event")).isEmpty();
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions WHERE event_id='erase-event'").query(String.class).single()).isEqualTo("ACCEPTED");
    }
    @Test void oversizedServerPayloadAndSpoofedBypassFailClosed() {
        seed("ACCEPTED",10,"huge-event","SUPPRESSED");
        // SUPPRESSED is deliberately immutable to readmission; add oversized fresh accepted row directly as owner fixture.
        owner.sql("INSERT INTO notification.generic_submissions(event_id,school_id,request_sha256,status,correlation_id,provider_request_id) VALUES('large-event',10,:hash,'ACCEPTED',:correlation,'synthetic-provider')")
            .param("hash","a".repeat(64)).param("correlation",NotificationSubmissionResult.correlationId("large-event")).update();
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status) VALUES('large-event','notification.requested.v1',:payload,'ACCEPTED')")
            .param("payload"," ".repeat(65537)).update();
        assertThat(lookup(10,"large-event")).isEmpty();
        seed("ACCEPTED",20,"other-school","ACCEPTED");
        Optional<GenericAcceptedReportBindingRepository.Binding> scoped=tx.execute(t->{app.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();return new GenericAcceptedReportBindingRepository(app).find(10,"other-school");});
        assertThat(scoped).isEmpty();
    }
}
