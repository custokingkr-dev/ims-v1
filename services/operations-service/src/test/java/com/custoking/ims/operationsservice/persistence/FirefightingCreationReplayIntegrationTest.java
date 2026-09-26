package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.outbox.OutboxWriter;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes direct repository calls inside real transactions and runtime RLS, including replay races. */
class FirefightingCreationReplayIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static HikariDataSource pool;
    static JdbcClient admin, jdbc;
    static TransactionTemplate transactions;
    static FirefightingReadRepository repository;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner"); pg.start();
        admin = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),"owner","owner"));
        admin.sql("CREATE ROLE app_rt LOGIN PASSWORD 'local-test-only' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
        admin.sql("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt").update();
        admin.sql("ALTER DEFAULT PRIVILEGES GRANT USAGE,SELECT ON SEQUENCES TO app_rt").update();
        Flyway.configure().dataSource(pg.getJdbcUrl(),"owner","owner").schemas("firefighting").defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting").load().migrate();
        admin.sql("GRANT USAGE ON SCHEMA firefighting TO app_rt").update();
        // Migrations must revoke broad defaults on append-only receipts themselves.
        pool = new HikariDataSource(); pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername("app_rt"); pool.setPassword("local-test-only");
        pool.setSchema("firefighting"); pool.setMaximumPoolSize(4);
        var dataSource = new TenantAwareDataSource(pool); jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new FirefightingReadRepository(jdbc,new OutboxWriter(jdbc,MAPPER,"firefighting"),MAPPER);
    }
    @AfterAll static void stop() { if (pool != null) pool.close(); if (pg != null) pg.stop(); }
    @AfterEach void clear() { TenantContext.clear(); }
    @BeforeEach void reset() {
        admin.sql("TRUNCATE firefighting.creation_replays,firefighting.outbox_events,firefighting.firefighting_requests CASCADE").update();
    }
    static <T> T scoped(long school,Supplier<T> operation) {
        TenantContext.set(new TenantContext(77L,"staff@example.test","ADMIN",school,null,Set.of("firefighting:read","firefighting:update")));
        try { return transactions.execute(status -> operation.get()); }
        finally { TenantContext.clear(); }
    }
    static Map<String,Object> request(long school,String key) {
        return Map.of("schoolId",school,"idempotencyKey",key,"title","Classroom repair","category","Other","estimatedBudget",500L,"actorId",77L);
    }
    static Map<String,Object> quote(String key) { return Map.of("idempotencyKey",key,"vendorName","Test vendor","amount",400L,"notes","Synthetic quotation"); }
    static String create(long school,String key) { return (String) scoped(school,() -> repository.createRequest(request(school,key))).get("code"); }
    static long count(String table) { return admin.sql("SELECT count(*) FROM firefighting."+table).query(Long.class).single(); }
    static void status(Throwable error,int expected) {
        assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,ex -> assertThat(ex.getStatusCode().value()).isEqualTo(expected));
    }

    @Test void requestAndQuotationReplayReturnSameIdentifiersWithoutExtraRowsOrEvents() {
        var original = scoped(7,() -> repository.createRequest(request(7,"request-1")));
        var replay = scoped(7,() -> repository.createRequest(request(7,"request-1")));
        assertThat(replay).isEqualTo(original);
        assertThat(count("firefighting_requests")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        String code = (String) original.get("code");
        var savedQuote = scoped(7,() -> repository.addQuotation(code,quote("quotation-1")));
        assertThat(scoped(7,() -> repository.addQuotation(code,quote("quotation-1")))).isEqualTo(savedQuote);
        assertThat(count("ff_quotations")).isEqualTo(1);
        assertThat(count("creation_replays")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
        String receipt = admin.sql("SELECT row_to_json(r)::text FROM firefighting.creation_replays r WHERE operation='request:create'").query(String.class).single();
        assertThat(receipt).contains("payload_sha256", "entity_id").doesNotContain("Classroom repair", "actorEmail", "staff@example.test");
    }

    @Test void sameKeyWithChangedRequestOrQuotePayloadConflictsWithoutMutation() {
        String code = create(7,"request-conflict");
        var changedRequest = new HashMap<>(request(7,"request-conflict")); changedRequest.put("title","Different repair");
        status(catchThrowable(() -> scoped(7,() -> repository.createRequest(changedRequest))),409);
        scoped(7,() -> repository.addQuotation(code,quote("quote-conflict")));
        var changedQuote = new HashMap<>(quote("quote-conflict")); changedQuote.put("amount",450L);
        status(catchThrowable(() -> scoped(7,() -> repository.addQuotation(code,changedQuote))),409);
        assertThat(count("firefighting_requests")).isEqualTo(1); assertThat(count("ff_quotations")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(admin.sql("SELECT amount FROM firefighting.ff_quotations").query(Long.class).single()).isEqualTo(400);
    }

    @Test void sameKeyIsIsolatedBySchoolAndRuntimeCannotReadOrForgeAnotherSchoolsReceipt() {
        String first = create(7,"same-key"), second = create(8,"same-key");
        assertThat(first).isNotEqualTo(second);
        assertThat(scoped(7,() -> jdbc.sql("SELECT entity_id FROM firefighting.creation_replays").query(String.class).list())).containsExactly(first);
        assertThat(scoped(8,() -> jdbc.sql("SELECT entity_id FROM firefighting.creation_replays").query(String.class).list())).containsExactly(second);
        assertThatThrownBy(() -> scoped(7,() -> repository.createRequest(request(8,"forged-key"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> scoped(7,() -> repository.addQuotation(second,quote("forged-quote")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("creation_replays")).isEqualTo(2); assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(admin.sql("SELECT has_table_privilege('app_rt','firefighting.creation_replays','DELETE')").query(Boolean.class).single()).isFalse();
    }

    @Test void missingOrInvalidKeysFailBeforeDomainOrReceiptWrites() {
        var missing = new HashMap<>(request(7,"placeholder")); missing.remove("idempotencyKey");
        status(catchThrowable(() -> scoped(7,() -> repository.createRequest(missing))),400);
        var invalid = new HashMap<>(request(7,"placeholder")); invalid.put("idempotencyKey","bad key with spaces");
        status(catchThrowable(() -> scoped(7,() -> repository.createRequest(invalid))),400);
        assertThat(count("firefighting_requests")).isZero(); assertThat(count("creation_replays")).isZero();
        String code = create(7,"valid-key");
        status(catchThrowable(() -> scoped(7,() -> repository.addQuotation(code,Map.of("vendorName","Test vendor","amount",100)))),400);
        assertThat(count("ff_quotations")).isZero(); assertThat(count("creation_replays")).isEqualTo(1);
    }

    @Test void runtimeCannotRewriteOrEraseSavedKeysDespiteBroadOwnerDefaults() {
        String code = create(7,"immutable-key");
        assertThat(admin.sql("SELECT has_column_privilege('app_rt','firefighting.quotation_documents','status','UPDATE')").query(Boolean.class).single()).isTrue();
        assertThat(admin.sql("SELECT has_column_privilege('app_rt','firefighting.quotation_documents','object_key','UPDATE')").query(Boolean.class).single()).isFalse();
        assertThat(admin.sql("SELECT has_table_privilege('app_rt','firefighting.quotation_documents','DELETE')").query(Boolean.class).single()).isFalse();
        for (String sql : new String[] {
                "UPDATE firefighting.creation_replays SET entity_id='forged'",
                "DELETE FROM firefighting.creation_replays",
                "TRUNCATE firefighting.creation_replays"}) {
            assertThatThrownBy(() -> scoped(7,() -> {
                jdbc.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();
                return jdbc.sql(sql).update();
            })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        assertThat(create(7,"immutable-key")).isEqualTo(code);
        assertThat(count("creation_replays")).isEqualTo(1);
    }

    @Test void lostSubmissionCanReplayWithoutExtraEventOrRegressingAnAdvancedStatus() {
        String code = create(7,"submit-key");
        var submitted = scoped(7,() -> repository.submit(code));
        assertThat(submitted).containsEntry("status","AWAITING_BURSAR");
        assertThat(scoped(7,() -> repository.submit(code))).isEqualTo(submitted);
        assertThat(count("outbox_events")).isEqualTo(2);
        scoped(7,() -> repository.approveBursar(code,Map.of()));
        assertThat(scoped(7,() -> repository.submit(code))).containsEntry("status","AWAITING_PRINCIPAL");
        assertThat(count("outbox_events")).isEqualTo(3);
    }

    @Test void removedQuotationKeepsReceiptAndReplayCannotRecreateIt() {
        String code = create(7,"deleted-quote-request");
        var saved = scoped(7,() -> repository.addQuotation(code,quote("removed-quote")));
        scoped(7,() -> repository.deleteQuotation(code,(String) saved.get("id")));
        status(catchThrowable(() -> scoped(7,() -> repository.addQuotation(code,quote("removed-quote")))),410);
        assertThat(count("ff_quotations")).isZero(); assertThat(count("creation_replays")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(3);
    }

    @Test void concurrentRequestsAndQuotationsHaveOneCommittedResultEach() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> create(7,"concurrent-request"));
            var two = workers.submit(() -> create(7,"concurrent-request"));
            String code = one.get(15,TimeUnit.SECONDS);
            assertThat(two.get(15,TimeUnit.SECONDS)).isEqualTo(code);
            var quoteOne = workers.submit(() -> scoped(7,() -> repository.addQuotation(code,quote("concurrent-quote"))));
            var quoteTwo = workers.submit(() -> scoped(7,() -> repository.addQuotation(code,quote("concurrent-quote"))));
            assertThat(quoteOne.get(15,TimeUnit.SECONDS)).isEqualTo(quoteTwo.get(15,TimeUnit.SECONDS));
        }
        assertThat(count("firefighting_requests")).isEqualTo(1); assertThat(count("ff_quotations")).isEqualTo(1);
        assertThat(count("creation_replays")).isEqualTo(2); assertThat(count("outbox_events")).isEqualTo(2);
    }

    @Test void outboxFailureRollsBackBothReceiptAndDomainWriteSoOriginalKeyCanRecover() {
        OutboxWriter failing = mock(OutboxWriter.class);
        doThrow(new IllegalStateException("Outbox unavailable")).when(failing).append(anyString(),anyString(),anyString(),anyString(),anyLong(),anyMap());
        var broken = new FirefightingReadRepository(jdbc,failing,MAPPER);
        assertThatThrownBy(() -> scoped(7,() -> broken.createRequest(request(7,"retry-after-rollback")))).isInstanceOf(IllegalStateException.class);
        assertThat(count("creation_replays")).isZero(); assertThat(count("firefighting_requests")).isZero();
        create(7,"retry-after-rollback");
        assertThat(count("creation_replays")).isEqualTo(1); assertThat(count("firefighting_requests")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }
}
