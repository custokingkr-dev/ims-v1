package com.custoking.ims.platformservice.infrastructure;
import com.custoking.ims.platformservice.persistence.*;
import com.custoking.ims.platformservice.application.*;
import java.nio.charset.StandardCharsets;

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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class Msg91SmsV3ReportBridgePostgresTest {
    static PostgreSQLContainer<?> pg;static JdbcClient owner,app;static TransactionTemplate tx,ownerTx;
    static JdbcTransactionManager manager;static GenericNotificationReportRepository reports;static GenericNotificationSubmissionRepository submissions;
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
        try(var stream=Msg91SmsV3ReportBridgePostgresTest.class.getResourceAsStream("/db/migration/notification/afterMigrate__dedicated_runtime_acl.sql")) {
            String callback=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            owner.sql(callback).update();owner.sql(callback).update();
        }
        var runtime=new DriverManagerDataSource(pg.getJdbcUrl(),"ims_platform_rt","synthetic-only");
        app=JdbcClient.create(runtime);manager=new JdbcTransactionManager(runtime);tx=new TransactionTemplate(manager);tx.setTimeout(10);
        reports=new GenericNotificationReportRepository(app);submissions=new GenericNotificationSubmissionRepository(app);
        assertThat(owner.sql("SELECT NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb) FROM pg_roles WHERE rolname='ims_platform_rt'").query(Boolean.class).single()).isTrue();
    }
    @AfterAll static void stop(){if(pg!=null)pg.stop();}


    private final String event="bridge-pg-event";
    private String payload(){return "{\"schoolId\":10,\"studentId\":301,\"template\":\"synthetic\",\"channel\":\"SMS\",\"recipientType\":\"PARENT\",\"recipientId\":\"301\",\"destination\":\"919000000001\"}";}
    private String hash(){return NotificationSubmissionResult.requestSha256(new NotificationDeliveryRequest(event,"synthetic","SMS","PARENT","301",payload()));}
    private void seed(String state) {
        owner.sql("INSERT INTO notification.generic_submissions(event_id,school_id,request_sha256,status,correlation_id,provider_request_id,submitted_at) VALUES(:event,10,:hash,:state,:correlation,:provider,now()-interval '1 minute')")
            .param("event",event).param("hash",hash()).param("state",state).param("correlation",NotificationSubmissionResult.correlationId(event)).param("provider",state.equals("ACCEPTED")?"synthetic-provider":null).update();
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status) VALUES(:event,'notification.requested.v1',:payload,:state)")
            .param("event",event).param("payload",payload()).param("state",state).update();
    }
    private byte[] raw(){String date=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
        return ("{\"date\":\""+date+"\",\"number\":\"919000000001\",\"senderId\":\"SYNTH\",\"amount\":\"0.25\",\"requestId\":\"synthetic-provider\",\"INMSID\":\"synthetic\",\"CRQID\":\""+NotificationSubmissionResult.correlationId(event)+"\",\"credit\":\"1\",\"userId\":\"synthetic-user\",\"campaignName\":\"synthetic\",\"status\":\"1\",\"desc\":\"DELIVERED\"}").getBytes(StandardCharsets.UTF_8);}
    private Msg91SmsV3ReportBridge bridge(){return new Msg91SmsV3ReportBridge(new NotificationReportAuthority(t->Optional.of("reports@synthetic.iam.gserviceaccount.com"),true,"r".repeat(40),"s".repeat(40),"p".repeat(40),"reports@synthetic.iam.gserviceaccount.com","gateway@synthetic.iam.gserviceaccount.com"),new GenericAcceptedReportBindingRepository(app),reports,manager,new Msg91SmsV3ReportCodec.Profile("synthetic-user","SYNTH",ZoneOffset.UTC));}
    @BeforeEach void clean(){owner.sql("TRUNCATE notification.generic_unknown_report_assertions,notification.generic_delivery_reports,notification.generic_delivery_results,notification.generic_submissions,notification.notification_delivery_attempts,notification.notification_inbox_events,reporting.student_projection_tombstones").update();}
    @Test void realAcceptedReportReplayAndErasureUseExistingAtomicPipeline() {
        seed("ACCEPTED");byte[] bytes=raw();var bridge=bridge();
        assertThat(bridge.reconcile("Bearer signed","r".repeat(40),10,event,bytes)).isTrue();
        assertThat(bridge.reconcile("Bearer signed","r".repeat(40),10,event,bytes)).isTrue();
        assertThat(owner.sql("SELECT count(*) FROM notification.generic_delivery_reports").query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT delivery_status FROM notification.generic_delivery_results").query(String.class).single()).isEqualTo("DELIVERED");
        assertThat(owner.sql("SELECT status FROM notification.notification_inbox_events").query(String.class).single()).isEqualTo("ACCEPTED");
        owner.sql("INSERT INTO reporting.student_projection_tombstones(student_id,deleted_at) VALUES(301,now())").update();
        assertThat(bridge.reconcile("Bearer signed","r".repeat(40),10,event,bytes)).isFalse();
        assertThat(owner.sql("SELECT delivery_status FROM notification.generic_delivery_results").query(String.class).single()).isEqualTo("SUPPRESSED");
    }
    @Test void unknownSelfAssertionForeignScopeAndForgedProviderCannotWrite() {
        seed("UNKNOWN");var bridge=bridge();
        assertThat(bridge.reconcile("Bearer signed","r".repeat(40),10,event,raw())).isFalse();
        assertThat(owner.sql("SELECT count(*) FROM notification.generic_unknown_report_assertions").query(Long.class).single()).isZero();
        clean();seed("ACCEPTED");assertThat(bridge.reconcile("Bearer signed","r".repeat(40),20,event,raw())).isFalse();
        byte[] forged=new String(raw(),StandardCharsets.UTF_8).replace("synthetic-provider","foreign-provider").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->bridge.reconcile("Bearer signed","r".repeat(40),10,event,forged)).hasMessage("Unrecognized SMS-v3 report binding");
        assertThat(owner.sql("SELECT count(*) FROM notification.generic_delivery_reports").query(Long.class).single()).isZero();
    }
}
