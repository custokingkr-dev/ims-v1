package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
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

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnualPlanConfirmationRepositoryIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner, jdbc;
    static TransactionTemplate transactions;
    static AnnualPlanConfirmationRepository repository;
    static final ObjectMapper MAPPER = new ObjectMapper();
    static String currentYear;

    @BeforeAll static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'local-test-only' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
        // Match the broad defaults found on deployed migration owners. The new
        // migration must narrow these itself; no post-migration receipt grants.
        owner.sql("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt").update();
        owner.sql("ALTER DEFAULT PRIVILEGES GRANT USAGE,SELECT ON SEQUENCES TO app_rt").update();
        for (String schema : new String[]{"tenant_school", "catalog"}) {
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        owner.sql("GRANT USAGE ON SCHEMA tenant_school,catalog TO app_rt").update();
        var source = new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "local-test-only");
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new AnnualPlanConfirmationRepository(jdbc, new OutboxWriter(jdbc, MAPPER, "tenant_school"), MAPPER);
        currentYear = AcademicCalendar.currentAcademicYear(4).id();
    }

    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    @BeforeEach void seed() {
        owner.sql("DELETE FROM catalog.annual_plan_confirmations").update();
        owner.sql("DELETE FROM catalog.annual_plan_items").update();
        owner.sql("DELETE FROM tenant_school.outbox_events").update();
        owner.sql("DELETE FROM tenant_school.schools").update();
        owner.sql("INSERT INTO tenant_school.schools(id,name,short_code,active,created_at) VALUES (10,'School A','A',true,now()),(20,'School B','B',true,now()),(30,'Empty school','C',true,now())").update();
        item("a",10,currentYear,120L); item("old",10,"ay_2000_01",999L); item("b",20,currentYear,800L);
    }

    static void item(String id,long school,String year,long amount) {
        owner.sql("INSERT INTO catalog.annual_plan_items(id,school_id,academic_year_id,category,description,quantity,estimated_amount,status) VALUES(:id,:school,:year,'STATIONERY','Exercise books','12',:amount,'PLANNED')")
                .param("id",id).param("school",school).param("year",year).param("amount",amount).update();
    }

    static <T> T scoped(long school,Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT set_config('app.current_school_id',:school,true),set_config('app.bypass_rls','off',true),set_config('app.operator_schools','',true)")
                    .param("school",Long.toString(school)).query().singleRow();
            return operation.get();
        });
    }

    String fingerprint(long school) { return scoped(school, () -> (String) repository.review(school).get("fingerprint")); }

    @Test void storesOnlyTheReviewedCurrentYearSnapshotAndReplaysWithoutAnotherEvent() {
        String fingerprint = fingerprint(10);
        var saved = scoped(10, () -> repository.confirm(10L,77L,fingerprint));
        var replay = scoped(10, () -> repository.confirm(10L,88L,fingerprint));
        assertThat(saved).containsEntry("confirmed",true).containsEntry("schoolId",10L).containsEntry("revision",1)
                .containsEntry("itemCount",1).containsEntry("notificationStatus","NOT_SENT");
        assertThat(replay).isEqualTo(saved);
        assertThat(scoped(10, () -> repository.review(10L)).get("confirmation")).isEqualTo(saved);
        assertThat(owner.sql("SELECT confirmed_by FROM catalog.annual_plan_confirmations").query(Long.class).single()).isEqualTo(77);
        String snapshot = owner.sql("SELECT snapshot_json::text FROM catalog.annual_plan_confirmations").query(String.class).single();
        assertThat(snapshot).contains("Exercise books", "120").doesNotContain("999", "800");
        assertThat(owner.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE event_type='catalog.annual-plan-confirmed.v1'").query(Long.class).single()).isEqualTo(1);
        String payload = owner.sql("SELECT payload::text FROM tenant_school.outbox_events").query(String.class).single();
        assertThat(payload).contains("confirmationId", "fingerprint").doesNotContain("Exercise books", "description", "notified");
    }

    @Test void changedPlanRequiresFreshReviewAndStoresANewImmutableRevision() {
        String original = fingerprint(10);
        scoped(10, () -> repository.confirm(10L,77L,original));
        owner.sql("UPDATE catalog.annual_plan_items SET estimated_amount=250 WHERE id='a'").update();
        assertThat(scoped(10, () -> repository.review(10L)).get("confirmation")).isNull();
        assertThatThrownBy(() -> scoped(10, () -> repository.confirm(10L,77L,original)))
                .isInstanceOfSatisfying(ResponseStatusException.class,error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        String revised = fingerprint(10);
        assertThat(scoped(10, () -> repository.confirm(10L,77L,revised))).containsEntry("revision",2);
        assertThat(owner.sql("SELECT snapshot_json::text FROM catalog.annual_plan_confirmations WHERE revision=1").query(String.class).single()).contains("120");
        assertThat(owner.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).isEqualTo(2);
    }

    @Test void failsClosedForMissingReviewEmptyPlanActorAndSchoolScope() {
        assertThatThrownBy(() -> scoped(10, () -> repository.confirm(10L,77L,null))).isInstanceOf(ResponseStatusException.class);
        String empty = fingerprint(30);
        assertThatThrownBy(() -> scoped(30, () -> repository.confirm(30L,77L,empty))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> scoped(10, () -> repository.confirm(10L,null,fingerprint(10)))).isInstanceOf(ResponseStatusException.class);
        String otherSchool = fingerprint(20);
        assertThatThrownBy(() -> scoped(10, () -> repository.confirm(20L,77L,otherSchool))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> scoped(10, () -> repository.review(null))).isInstanceOf(ResponseStatusException.class);
        assertThat(owner.sql("SELECT count(*) FROM catalog.annual_plan_confirmations").query(Long.class).single()).isZero();
    }

    @Test void runtimeRlsHidesOtherSchoolsConfirmationAndRejectsForgedWrites() {
        String fingerprint = fingerprint(20);
        scoped(20, () -> repository.confirm(20L,77L,fingerprint));
        assertThat(scoped(10, () -> jdbc.sql("SELECT count(*) FROM catalog.annual_plan_confirmations").query(Long.class).single())).isZero();
        assertThatThrownBy(() -> scoped(10, () -> jdbc.sql("INSERT INTO catalog.annual_plan_confirmations(id,school_id,academic_year_id,revision,fingerprint,item_count,snapshot_json,confirmed_by) VALUES(gen_random_uuid(),20,'forged',1,'forged',1,'[]',77)").update()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void broadDefaultGrantsCannotRewriteOrDeleteAConfirmationEvenWithTenantBypass() {
        String fingerprint = fingerprint(10);
        scoped(10, () -> repository.confirm(10L,77L,fingerprint));
        for (String sql : new String[] {
                "UPDATE catalog.annual_plan_confirmations SET confirmed_by=999",
                "DELETE FROM catalog.annual_plan_confirmations",
                "TRUNCATE catalog.annual_plan_confirmations"}) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                jdbc.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();
                jdbc.sql(sql).update();
            })).rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        assertThat(scoped(10, () -> repository.confirm(10L,88L,fingerprint))).containsEntry("revision",1);
        assertThat(owner.sql("SELECT confirmed_by FROM catalog.annual_plan_confirmations").query(Long.class).single()).isEqualTo(77);
    }

    @Test void outboxFailureRollsBackConfirmation() {
        OutboxWriter failing = mock(OutboxWriter.class);
        doThrow(new IllegalStateException("Outbox unavailable")).when(failing).append(anyString(),anyString(),anyString(),anyString(),anyLong(),anyMap());
        var service = new AnnualPlanConfirmationRepository(jdbc,failing,MAPPER);
        String fingerprint = fingerprint(10);
        assertThatThrownBy(() -> scoped(10, () -> service.confirm(10L,77L,fingerprint))).isInstanceOf(IllegalStateException.class);
        assertThat(owner.sql("SELECT count(*) FROM catalog.annual_plan_confirmations").query(Long.class).single()).isZero();
    }

    @Test void concurrentRepeatProducesOneConfirmationAndOneEvent() throws Exception {
        String fingerprint = fingerprint(10);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> scoped(10, () -> repository.confirm(10L,77L,fingerprint)));
            var two = workers.submit(() -> scoped(10, () -> repository.confirm(10L,88L,fingerprint)));
            assertThat(one.get(15,TimeUnit.SECONDS)).isEqualTo(two.get(15,TimeUnit.SECONDS));
        }
        assertThat(owner.sql("SELECT count(*) FROM catalog.annual_plan_confirmations").query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).isEqualTo(1);
    }

    @Test void clientItemReferenceRetryCannotDuplicateOrMoveAnotherSchoolsOrYearsItem() {
        var catalog = new CatalogReadRepository(jdbc,new OutboxWriter(jdbc,MAPPER,"tenant_school"));
        Map<String,Object> request = Map.of("id","client-reference","category","UNIFORMS","estimatedAmount",250);
        scoped(10, () -> catalog.saveAnnualPlanItem(10L,request));
        scoped(10, () -> catalog.saveAnnualPlanItem(10L,request));
        assertThat(owner.sql("SELECT count(*) FROM catalog.annual_plan_items WHERE id='client-reference'").query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> scoped(10, () -> catalog.saveAnnualPlanItem(10L,Map.of("id","b","category","FORGED"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped(10, () -> catalog.saveAnnualPlanItem(10L,Map.of("id","old","category","FORGED"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(owner.sql("SELECT school_id FROM catalog.annual_plan_items WHERE id='b'").query(Long.class).single()).isEqualTo(20);
        assertThat(owner.sql("SELECT academic_year_id FROM catalog.annual_plan_items WHERE id='old'").query(String.class).single()).isEqualTo("ay_2000_01");
    }
}
