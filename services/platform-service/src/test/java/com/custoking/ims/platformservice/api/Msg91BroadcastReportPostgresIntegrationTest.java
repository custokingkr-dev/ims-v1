package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Prepared;
import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual notification migrations, non-bypass runtime, locked SQL, rollback and real async slot recovery. */
class Msg91BroadcastReportPostgresIntegrationTest {
    private static final String SERVICE="synthetic-service",WEBHOOK="w".repeat(40),CORRELATION="ims"+"b".repeat(48);
    private static final String DESTINATION="guardian@synthetic.invalid",SENDER="sender@synthetic.invalid";
    private static PostgreSQLContainer<?> pg;
    private static DriverManagerDataSource ownerData;
    private static JdbcClient owner;
    private static Tomcat tomcat;
    private static Path temporary;
    private static URI uri;
    private static HttpClient client;
    private static final UUID BROADCAST=UUID.randomUUID(),RECIPIENT=UUID.randomUUID();
    private static final String EVENT="broadcast:"+BROADCAST+":1:EMAIL";

    @BeforeAll static void start() throws Exception {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        pg=new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");pg.start();
        ownerData=new DriverManagerDataSource(pg.getJdbcUrl(),"owner","owner");owner=JdbcClient.create(ownerData);
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'synthetic-only' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEROLE NOCREATEDB; ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt").update();
        Flyway.configure().dataSource(pg.getJdbcUrl(),"owner","owner").schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration/notification").load().migrate();
        owner.sql("GRANT USAGE ON SCHEMA notification TO app_rt").update();
        assertThat(owner.sql("SELECT NOT(rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb) FROM pg_roles WHERE rolname='app_rt'").query(Boolean.class).single()).isTrue();
        var runtime=new DriverManagerDataSource(pg.getJdbcUrl(),"app_rt","synthetic-only");
        var manager=new DataSourceTransactionManager(runtime);var live=new BroadcastLiveRepository(JdbcClient.create(runtime));
        owner.sql("""
            INSERT INTO notification.notification_broadcasts
            (id,school_id,title,message,audience_type,channels,communication_category,status,dispatch_mode,approval_mode)
            VALUES(:id,10,'Synthetic notice','Synthetic message','ALL_PARENTS','EMAIL','SCHOOL_NOTICE','QUEUED','LIVE','LIVE')
            """).param("id",BROADCAST).update();
        owner.sql("""
            INSERT INTO notification.notification_broadcast_recipients
            (id,broadcast_id,school_id,student_id,channel,event_id,guardian_id,destination_sha256,status)
            VALUES(:id,:broadcast,10,1,'EMAIL',:event,'synthetic-guardian',:destination,'QUEUED')
            """).param("id",RECIPIENT).param("broadcast",BROADCAST).param("event",EVENT).param("destination",sha(DESTINATION)).update();
        var seeding=new TransactionTemplate(manager);seeding.setTimeout(10);
        seeding.executeWithoutResult(tx->assertThat(live.reserve(new QueuedRecipient(RECIPIENT,BROADCAST,10,1,"EMAIL",EVENT,"synthetic-guardian",sha(DESTINATION),0,"Synthetic notice","Synthetic message","LIVE"),
                new Prepared("EMAIL",CORRELATION,sha(DESTINATION),"c".repeat(64),sha(SENDER),"synthetic payload"))).isTrue());
        var controller=new Msg91BroadcastReportController(SERVICE,new LiveBroadcastConfiguration(false,"EMAIL",true,"10","a".repeat(64),WEBHOOK),live,manager);
        temporary=Files.createTempDirectory("ims-email-report-pg-tomcat-");tomcat=new Tomcat();tomcat.setBaseDir(temporary.toString());tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");tomcat.getConnector().setProperty("connectionTimeout","10000");
        var context=tomcat.addContext("",temporary.toString());
        var servlet=Tomcat.addServlet(context,"reports",new HttpServlet(){
            @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException {
                try{controller.report(request.getHeader("X-Notification-Service-Token"),request.getHeader("X-MSG91-Webhook-Token"),request,response);}
                catch(ResponseStatusException failure){response.sendError(failure.getStatusCode().value(),failure.getReason());}
            }
        });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/api/v1/notifications/provider-reports/msg91/email","reports");tomcat.start();
        uri=URI.create("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+"/api/v1/notifications/provider-reports/msg91/email");
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void stop() throws Exception {
        if(client!=null)client.close();if(tomcat!=null){tomcat.stop();tomcat.destroy();}
        if(pg!=null)pg.stop();
        if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private static CompletableFuture<HttpResponse<String>> post(String id) {
        String body="""
            {"correlationId":"%s","providerMessageId":"%s","destination":"%s","sender":"%s","status":"DELIVERED","occurredAt":"%s"}
            """.formatted(CORRELATION,id,DESTINATION,SENDER,OffsetDateTime.now());
        return client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .header("X-Notification-Service-Token",SERVICE).header("X-MSG91-Webhook-Token",WEBHOOK)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void lockedReceiptUpdateTimesOutRollsBackPartialLedgerWritesAndReleasesBothSlots() throws Exception {
        try(var blocker=ownerData.getConnection()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM notification.notification_broadcast_recipients WHERE id=? FOR UPDATE")) {
                lock.setObject(1,RECIPIENT);try(var selected=lock.executeQuery()){assertThat(selected.next()).isTrue();}
            }
            long started=System.nanoTime();var first=post("blocked-provider-one");var second=post("blocked-provider-two");
            boolean reachedRecipient=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!reachedRecipient && System.nanoTime()<deadline) {
                reachedRecipient=owner.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE usename='app_rt' AND wait_event_type='Lock' AND query LIKE '%UPDATE notification.notification_broadcast_recipients%')").query(Boolean.class).single();
                if(!reachedRecipient)Thread.sleep(25);
            }
            assertThat(reachedRecipient).as("Report inserted and updated its ledger before reaching the held recipient row").isTrue();
            for(var result:List.of(first.get(9,TimeUnit.SECONDS),second.get(9,TimeUnit.SECONDS))) {
                assertThat(result.statusCode()).isEqualTo(503);assertThat(result.body()).doesNotContain(DESTINATION,SENDER,SERVICE,WEBHOOK,"blocked-provider");
            }
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(8));
            assertThat(owner.sql("SELECT count(*) FROM notification.broadcast_live_reports").query(Long.class).single()).isZero();
            var saved=owner.sql("SELECT submission_status,delivery_status,provider_message_id FROM notification.broadcast_live_submissions WHERE event_id=:event").param("event",EVENT).query().singleRow();
            assertThat(saved).containsEntry("submission_status","SUBMITTING").containsEntry("delivery_status",null).containsEntry("provider_message_id",null);
            assertThat(owner.sql("SELECT status FROM notification.notification_broadcast_recipients WHERE id=:id").param("id",RECIPIENT).query(String.class).single()).isEqualTo("SUBMITTING");
            blocker.rollback();
        }
        var recovery=post("recovered-provider").get(5,TimeUnit.SECONDS);
        assertThat(recovery.statusCode()).isEqualTo(202);assertThat(recovery.body()).isEqualTo("{\"accepted\":true}");
        assertThat(owner.sql("SELECT count(*) FROM notification.broadcast_live_reports").query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT delivery_status FROM notification.broadcast_live_submissions WHERE event_id=:event").param("event",EVENT).query(String.class).single()).isEqualTo("DELIVERED");
    }
    private static String sha(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
}
