package com.custoking.ims.platformservice.persistence;

import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class GenericNotificationSubmissionIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner,app;
    static TransactionTemplate tx;
    static DataSourceTransactionManager manager;
    static GenericNotificationSubmissionRepository ledger;
    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        pg=new PostgreSQLContainer<>("postgres:16");pg.start();
        owner=JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEROLE NOCREATEDB").update();
        Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).schemas("notification")
            .defaultSchema("notification").locations("classpath:db/migration/notification").load().migrate();
        owner.sql("GRANT USAGE ON SCHEMA notification TO app_rt").update();
        var ds=new DriverManagerDataSource(pg.getJdbcUrl(),"app_rt","test");app=JdbcClient.create(ds);
        manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);tx.setTimeout(10);ledger=new GenericNotificationSubmissionRepository(app);
    }
    @AfterAll static void close() { if(pg!=null)pg.stop(); }
    @BeforeEach void clean() { owner.sql("DELETE FROM notification.generic_submissions").update();owner.sql("DELETE FROM notification.notification_inbox_events").update(); }
    @Test void committedReservationSurvivesFailedResultAndCannotBeRetried() {
        assertThat((Boolean)tx.execute(t->ledger.reserve("one",10,"a".repeat(64)))).isTrue();
        assertThatThrownBy(()->tx.executeWithoutResult(t->{ledger.finish("one",10,"a".repeat(64),"UNKNOWN");throw new IllegalStateException("lost commit");})).hasMessage("lost commit");
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions").query(String.class).single()).isEqualTo("SUBMITTING");
        assertThat((Boolean)tx.execute(t->ledger.reserve("one",10,"a".repeat(64)))).isFalse();
        tx.executeWithoutResult(t->ledger.finish("one",10,"a".repeat(64),"UNKNOWN"));
        assertThat((Boolean)tx.execute(t->ledger.reserve("one",10,"a".repeat(64)))).isFalse();
        assertThatThrownBy(()->tx.execute(t->ledger.reserve("one",10,"b".repeat(64)))).hasMessageContaining("binding mismatch");
    }
    @Test void concurrentAttemptsHaveOneWinner() throws Exception {
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> operation=()->{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("start deadline");return tx.execute(t->ledger.reserve("race",10,"a".repeat(64)));};
            var a=workers.submit(operation);var b=workers.submit(operation);start.countDown();
            assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void foreignSchoolHasNoVisibilityAndCannotReuseKeyOrDeleteEvidence() {
        tx.executeWithoutResult(t->ledger.reserve("one",10,"a".repeat(64)));
        assertThatThrownBy(()->tx.execute(t->ledger.reserve("one",20,"a".repeat(64)))).hasMessageContaining("binding mismatch");
        assertThat(app.sql("SELECT count(*) FROM notification.generic_submissions").query(Long.class).single()).isZero();
        assertThatThrownBy(()->app.sql("DELETE FROM notification.generic_submissions").update()).rootCause().hasMessageContaining("permission denied");
    }
    @Test void staleCommandCannotResetReservationButErasureSuppressionWins() {
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status,received_at) VALUES('one','notification.requested.v1','{}','SUBMITTING',now())").update();
        assertThatThrownBy(()->owner.sql("UPDATE notification.notification_inbox_events SET status='RECEIVED',payload='{\"changed\":true}' WHERE event_id='one'").update()).hasMessageContaining("cannot be reset");
        owner.sql("UPDATE notification.notification_inbox_events SET status='UNKNOWN' WHERE event_id='one'").update();
        assertThatThrownBy(()->owner.sql("UPDATE notification.notification_inbox_events SET status='PROCESSED' WHERE event_id='one'").update()).hasMessageContaining("cannot be reset");
        owner.sql("UPDATE notification.notification_inbox_events SET status='SUPPRESSED',payload='{\"redacted\":true}' WHERE event_id='one'").update();
        assertThat(owner.sql("SELECT status FROM notification.notification_inbox_events").query(String.class).single()).isEqualTo("SUPPRESSED");
    }
    @Test void actualWorkerCommitsReservationVisibleToIndependentConnectionBeforeIo() {
        var inbox=org.mockito.Mockito.mock(NotificationInboxRepository.class);
        var attempts=org.mockito.Mockito.mock(NotificationDeliveryAttemptRepository.class);
        var policy=org.mockito.Mockito.mock(com.custoking.ims.platformservice.application.CurrentNotificationRecipientPolicy.class);
        var delivery=org.mockito.Mockito.mock(com.custoking.ims.platformservice.application.NotificationDeliveryService.class);
        var event=new NotificationInboxEvent();event.setEventId("one");event.setEventType("notification.requested.v1");
        event.setPayload("{\"schoolId\":10,\"channel\":\"SMS\"}");
        org.mockito.Mockito.when(inbox.findByIdForUpdate("one")).thenReturn(java.util.Optional.of(event));
        org.mockito.Mockito.doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(owner.sql("SELECT status FROM notification.generic_submissions WHERE event_id='one'").query(String.class).single()).isEqualTo("SUBMITTING");
            throw new IllegalStateException("transport outcome unconfirmed");
        }).when(delivery).submit(event);
        var worker=new com.custoking.ims.platformservice.application.GenericNotificationSubmissionWorker(inbox,attempts,ledger,policy,delivery,new tools.jackson.databind.ObjectMapper(),manager);
        worker.process("one");worker.process("one");
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions WHERE event_id='one'").query(String.class).single()).isEqualTo("UNKNOWN");
        org.mockito.Mockito.verify(delivery,org.mockito.Mockito.times(1)).submit(event);
    }
    @Test void typedReceiptIsPersistedAndFinalAcceptanceCannotBeDowngradedOrRetried() {
        String correlation=com.custoking.ims.platformservice.application.NotificationSubmissionResult.correlationId("one");
        tx.executeWithoutResult(t->{
            ledger.reserve("one",10,"a".repeat(64));
            ledger.finish("one",10,"a".repeat(64),"ACCEPTED",correlation,"5762846b4f8d285d378b4567","PROVIDER_REQUEST_ACCEPTED");
        });
        var receipt=owner.sql("SELECT status,provider_request_id,correlation_id FROM notification.generic_submissions WHERE event_id='one'").query().singleRow();
        assertThat(receipt).containsEntry("status","ACCEPTED").containsEntry("provider_request_id","5762846b4f8d285d378b4567").containsEntry("correlation_id",correlation);
        assertThat((Boolean)tx.execute(t->ledger.reserve("one",10,"a".repeat(64)))).isFalse();
        tx.executeWithoutResult(t->ledger.finish("one",10,"a".repeat(64),"UNKNOWN"));
        assertThat(owner.sql("SELECT status FROM notification.generic_submissions").query(String.class).single()).isEqualTo("ACCEPTED");
        assertThatThrownBy(()->tx.executeWithoutResult(t->{
            ledger.reserve("one",10,"a".repeat(64));app.sql("UPDATE notification.generic_submissions SET status='UNKNOWN' WHERE event_id='one'").update();
        })).rootCause().hasMessageContaining("Final provider submission evidence is immutable");
        owner.sql("INSERT INTO notification.notification_inbox_events(event_id,event_type,payload,status,received_at) VALUES('one','notification.requested.v1','{}','SUBMITTING',now())").update();
        owner.sql("UPDATE notification.notification_inbox_events SET status='ACCEPTED' WHERE event_id='one'").update();
        assertThatThrownBy(()->owner.sql("UPDATE notification.notification_inbox_events SET status='RECEIVED' WHERE event_id='one'").update()).rootCause().hasMessageContaining("cannot be reset");
        owner.sql("UPDATE notification.notification_inbox_events SET status='SUPPRESSED',payload='{\"redacted\":true}' WHERE event_id='one'").update();
        assertThat(owner.sql("SELECT status FROM notification.notification_inbox_events").query(String.class).single()).isEqualTo("SUPPRESSED");
    }
}
