package com.custoking.ims.schoolcoreservice.erasure;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.BroadcastRecipientPolicyRepository;
import com.custoking.ims.schoolcoreservice.persistence.GuardianCommunicationDispatchPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Actual source transactions with all four owning service migration chains, no cloud access. */
class StudentErasureJournalIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    static OutboxWriter outbox;

    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16").withDatabaseName("custoking_dev").withUsername("owner").withPassword("owner");
        postgres.start();
        var ownerSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "owner", "owner");
        var bootstrap = JdbcClient.create(ownerSource);
        bootstrap.sql("CREATE ROLE app_rt NOLOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        bootstrap.sql("CREATE ROLE ims_school_core_rt LOGIN PASSWORD 'synthetic-runtime-only' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        // Match the real owner bootstrap default ACLs rather than granting broad capabilities
        // after migration (which would erase the migration's column/append-only restrictions).
        bootstrap.sql("ALTER DEFAULT PRIVILEGES FOR ROLE owner GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO app_rt").update();
        bootstrap.sql("ALTER DEFAULT PRIVILEGES FOR ROLE owner GRANT USAGE,SELECT ON SEQUENCES TO app_rt").update();
        for (String schema : new String[]{"tenant_school", "student", "fee", "attendance"}) {
            Flyway.configure().dataSource(postgres.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(source);
        transaction = new TransactionTemplate(new JdbcTransactionManager(source)); transaction.setTimeout(45);
        outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        jdbc.sql("INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES('c1','1',1) ON CONFLICT(id) DO NOTHING").update();
        jdbc.sql("INSERT INTO tenant_school.academic_years(id,label,active) VALUES('erasure-test-year','2026-27',true)").update();
    }
    @AfterAll static void cleanup() { if (postgres != null) postgres.stop(); }

    @Test void wrongConfirmationNeverCreatesAnExternalIntent() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore());
        assertThatThrownBy(() -> transaction.execute(status -> fixture.students.deleteStudent(fixture.student, "wrong")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.store.objects).isEmpty();
        assertUnchanged(fixture);
    }

    @Test void independentlyConnectedWrongDatabaseCannotClaimReplayOrAuthorizeDelivery() {
        var otherSource = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/custoking_dev", "/postgres"), "owner", "owner");
        var otherJdbc = JdbcClient.create(otherSource);
        var otherTransaction = new TransactionTemplate(new JdbcTransactionManager(otherSource));
        otherTransaction.setTimeout(45);
        // Only the read-before-authorization columns are needed: the guard must prevent every
        // subsequent source mutation and external call on this independently connected database.
        otherJdbc.sql("CREATE SCHEMA student").update();
        otherJdbc.sql("""
                CREATE TABLE student.students(id bigint PRIMARY KEY,school_id bigint,admission_no text,
                    photo_url text,deleted_at timestamptz)
                """).update();
        otherJdbc.sql("INSERT INTO student.students(id,school_id,admission_no) VALUES(301,101,'ERASE-WRONG-DB')").update();
        var store = new StudentErasureJournalTest.MemoryStore();
        var journal = new StudentErasureJournal(StudentErasureJournalTest.configuration(), store);
        var otherStudents = new StudentReadRepository(otherJdbc, mock(StudentPhotoStorage.class),
                new OutboxWriter(otherJdbc, new ObjectMapper(), "tenant_school"));
        otherStudents.setErasureJournal(journal);
        assertThatThrownBy(() -> otherTransaction.execute(status -> otherStudents.deleteStudent(301L, "ERASE-WRONG-DB")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThatThrownBy(() -> otherTransaction.execute(status -> otherStudents.reconcileErasure(
                new StudentErasureJournal.IntentReference("intents/irrelevant", 11, "0".repeat(64)))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        var decision = otherTransaction.execute(status -> new GuardianCommunicationDispatchPolicy(otherJdbc, journal)
                .evaluate(101, 301, "SMS", "school-core:absentee:synthetic"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("ERASURE_FENCE_UNCONFIRMED");
        assertThat(store.controlReads).isZero(); assertThat(store.objects).isEmpty();
        assertThat(otherJdbc.sql("SELECT count(*) FROM student.students").query(Long.class).single()).isEqualTo(1);
    }

    @Test void journalFailureReturns503BeforeErasingSourceOrCreatingPhotoWork() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore() {
            @Override public long createOnly(String bucket, String object, byte[] body) {
                throw new IllegalStateException("Controlled unavailable provider secret");
            }
        });
        assertThatThrownBy(() -> transaction.execute(status -> fixture.students.deleteStudent(fixture.student, fixture.admission)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503").hasNoCause();
        assertThat(fixture.store.objects).isEmpty();
        assertUnchanged(fixture);
    }

    @Test void externalIntentSurvivesSqlRollbackThenRetryCommitsReceiptOutboxAndPhotoIntent() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore());
        transaction.executeWithoutResult(status -> {
            assertThat(fixture.students.deleteStudent(fixture.student, fixture.admission))
                    .containsEntry("deleted", true).containsEntry("permanent", true);
            status.setRollbackOnly();
        });
        assertUnchanged(fixture);
        assertThat(fixture.store.objects).hasSize(1);
        String originalObject = fixture.store.objects.keySet().iterator().next();

        transaction.executeWithoutResult(status -> fixture.students.deleteStudent(fixture.student, fixture.admission));
        assertThat(studentCount(fixture.student)).isZero();
        assertThat(fixture.store.objects).containsOnlyKeys(originalObject);
        assertThat(count("student.erasure_journal_receipts", fixture.student)).isEqualTo(1);
        assertThat(count("tenant_school.photo_cleanup_outbox", fixture.student)).isEqualTo(1);
        var evidence = jdbc.sql("SELECT payload::text FROM tenant_school.outbox_events WHERE event_type='student.deleted.v1' AND aggregate_id=:id")
                .param("id", Long.toString(fixture.student)).query(String.class).single();
        String operation = jdbc.sql("SELECT operation_id::text FROM student.erasure_journal_receipts WHERE student_id=:id")
                .param("id", fixture.student).query(String.class).single();
        assertThat(evidence).contains("erasureIntentId", "studentIncarnation", "restoreEpoch", operation);
        assertThat(jdbc.sql("SELECT erasure_operation_id::text FROM tenant_school.photo_cleanup_outbox WHERE student_id=:id")
                .param("id", fixture.student).query(String.class).single()).isEqualTo(operation);
        verify(fixture.photos, never()).deleteStoredPhoto(anyString());
    }

    @Test void activeEpochChangeLeavesAuthorizedIntentButRollsBackEverySqlWrite() {
        var store = new StudentErasureJournalTest.MemoryStore(); store.changeEpochAfterCreate = true;
        Fixture fixture = fixture(store);
        assertThatThrownBy(() -> transaction.execute(status -> fixture.students.deleteStudent(fixture.student, fixture.admission)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(store.objects).hasSize(1);
        assertUnchanged(fixture);
    }

    @Test void externalOnlyIntentAfterRollbackFencesActualOwnerConsentAndMakesZeroProviderCalls() {
        var store = new StudentErasureJournalTest.MemoryStore(); store.loseCreateResponse = true;
        Fixture fixture = fixture(store);
        String guardian = "erasure-guardian-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.guardians(id,school_id,full_name,phone,email,contact_verified_at)
                VALUES(:guardian,:school,'Synthetic guardian','9999999999','fixture@example.invalid',now())
                """).param("guardian", guardian).param("school", fixture.school).update();
        jdbc.sql("""
                INSERT INTO student.student_guardians(id,school_id,student_id,guardian_id,relationship,is_primary)
                VALUES(:id,:school,:student,:guardian,'GUARDIAN',true)
                """).param("id", UUID.randomUUID().toString()).param("school", fixture.school)
                .param("student", fixture.student).param("guardian", guardian).update();
        jdbc.sql("""
                INSERT INTO student.student_consent_events
                    (id,school_id,student_id,guardian_id,purpose,status,notice_version,evidence_source,effective_at)
                VALUES(:id,:school,:student,:guardian,'SCHOOL_COMMUNICATIONS','GRANTED','v1','SCHOOL_RECORD',now()-interval '1 minute')
                """).param("id", UUID.randomUUID().toString()).param("school", fixture.school)
                .param("student", fixture.student).param("guardian", guardian).update();
        var journal = new StudentErasureJournal(StudentErasureJournalTest.configuration(), store);
        var owner = new BroadcastRecipientPolicyRepository(jdbc, journal);
        UUID broadcast = UUID.randomUUID();
        assertThat(transaction.execute(status -> owner.resolve(fixture.school, broadcast, java.util.List.of("SMS"),
                java.util.List.of(fixture.student))).getFirst()).containsEntry("allowed", true);
        transaction.executeWithoutResult(status -> {
            fixture.students.deleteStudent(fixture.student, fixture.admission); status.setRollbackOnly();
        });
        assertUnchanged(fixture); assertThat(store.objects).hasSize(1);
        Runnable provider = mock(Runnable.class);
        var reviewed = transaction.execute(status -> owner.resolve(fixture.school, broadcast, java.util.List.of("SMS"),
                java.util.List.of(fixture.student))).getFirst();
        assertThat(reviewed).containsEntry("allowed", false).containsEntry("reason", "ERASURE_FENCE_UNCONFIRMED")
                .doesNotContainKeys("destination", "policyEvidence");
        if (Boolean.TRUE.equals(reviewed.get("allowed"))) provider.run();
        var dispatch = transaction.execute(status -> new GuardianCommunicationDispatchPolicy(jdbc, journal)
                .evaluate(fixture.school, fixture.student, "SMS", "school-core:absentee:synthetic"));
        assertThat(dispatch.allowed()).isFalse();
        if (dispatch.allowed()) provider.run();
        verify(provider, never()).run();
    }

    @Test void migratedIncarnationCannotChangeOrBeReinsertedAfterItsTerminalReceipt() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore());
        UUID original = jdbc.sql("SELECT erasure_incarnation FROM student.students WHERE id=:id")
                .param("id", fixture.student).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE student.students SET erasure_incarnation=gen_random_uuid() WHERE id=:id")
                .param("id", fixture.student).update()).hasMessageContaining("incarnation cannot change");
        transaction.executeWithoutResult(status -> fixture.students.deleteStudent(fixture.student, fixture.admission));
        assertThatThrownBy(() -> insertStudent(fixture.school, fixture.student, fixture.admission, fixture.section, original))
                .hasMessageContaining("incarnation cannot be reused");
        assertThat(studentCount(fixture.student)).isZero();
        insertStudent(fixture.school, fixture.student, fixture.admission, fixture.section, UUID.randomUUID());
        assertThat(studentCount(fixture.student)).isEqualTo(1);
    }

    @Test void pinnedIntentReconciliationReusesFullOwnerWorkflowAndIsIdempotentForItsProvenAbsentTarget() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore());
        transaction.executeWithoutResult(status -> {
            fixture.students.deleteStudent(fixture.student, fixture.admission); status.setRollbackOnly();
        });
        var entry = fixture.store.objects.entrySet().iterator().next();
        var reference = new StudentErasureJournal.IntentReference(entry.getKey(), entry.getValue().generation(),
                StudentErasureJournal.sha256(entry.getValue().body()));
        fixture.students.setErasureJournal(new StudentErasureJournal(StudentErasureJournalTest.reconcilingConfiguration(fixture.store), fixture.store));
        Map<String, Object> firstReplay = transaction.execute(status -> fixture.students.reconcileErasure(reference));
        assertThat(firstReplay)
                .containsEntry("permanent", true).containsEntry("deleted", true);
        Map<String, Object> secondReplay = transaction.execute(status -> fixture.students.reconcileErasure(reference));
        assertThat(secondReplay)
                .containsEntry("alreadyAbsent", true);
        assertThat(studentCount(fixture.student)).isZero();
        assertThat(count("student.erasure_journal_receipts", fixture.student)).isEqualTo(1);
        assertThat(count("tenant_school.photo_cleanup_outbox", fixture.student)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE event_type='student.deleted.v1' AND aggregate_id=:id")
                .param("id", Long.toString(fixture.student)).query(Long.class).single()).isEqualTo(1);
    }

    @Test void replayCannotBlindlyEraseAReusedStudentIdOrClaimAbsentIncarnationCoverage() {
        Fixture fixture = fixture(new StudentErasureJournalTest.MemoryStore());
        transaction.executeWithoutResult(status -> {
            fixture.students.deleteStudent(fixture.student, fixture.admission); status.setRollbackOnly();
        });
        var entry = fixture.store.objects.entrySet().iterator().next();
        var reference = new StudentErasureJournal.IntentReference(entry.getKey(), entry.getValue().generation(),
                StudentErasureJournal.sha256(entry.getValue().body()));
        jdbc.sql("DELETE FROM student.students WHERE id=:id").param("id", fixture.student).update();
        fixture.students.setErasureJournal(new StudentErasureJournal(StudentErasureJournalTest.reconcilingConfiguration(fixture.store), fixture.store));
        assertThatThrownBy(() -> transaction.execute(status -> fixture.students.reconcileErasure(reference)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        insertStudent(fixture.school, fixture.student, fixture.admission, fixture.section, UUID.randomUUID());
        assertThatThrownBy(() -> transaction.execute(status -> fixture.students.reconcileErasure(reference)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("incarnation");
        assertThat(studentCount(fixture.student)).isEqualTo(1);
        assertThat(count("student.erasure_journal_receipts", fixture.student)).isZero();
        assertThat(count("tenant_school.photo_cleanup_outbox", fixture.student)).isZero();
    }

    @Test void dedicatedRuntimeCreatesFreshIncarnationButCannotInsertRetiredUuidAcrossTenantsAfterCallbacks() throws Exception {
        Fixture erased = fixture(new StudentErasureJournalTest.MemoryStore());
        UUID retired = jdbc.sql("SELECT erasure_incarnation FROM student.students WHERE id=:id")
                .param("id", erased.student).query(UUID.class).single();
        transaction.executeWithoutResult(status -> erased.students.deleteStudent(erased.student, erased.admission));
        Fixture target = fixture(new StudentErasureJournalTest.MemoryStore());
        // Independently execute the actual post-migration callback again, twice. It must copy
        // column privileges, not restore table-level INSERT from a stale baseline assumption.
        try (var input = StudentErasureJournalIntegrationTest.class.getResourceAsStream(
                "/db/migration/student/afterMigrate__dedicated_runtime_acl.sql")) {
            String callback = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            jdbc.sql(callback).update(); jdbc.sql(callback).update();
        }
        assertThat(jdbc.sql("SELECT has_table_privilege('ims_school_core_rt','student.students','INSERT')").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT has_column_privilege('ims_school_core_rt','student.students','erasure_incarnation','INSERT')").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT has_column_privilege('ims_school_core_rt','student.students','full_name','INSERT')").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT has_table_privilege('ims_school_core_rt','student.erasure_journal_receipts','UPDATE') OR has_table_privilege('ims_school_core_rt','student.erasure_journal_receipts','DELETE')").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.table_privileges WHERE table_schema='student' AND table_name='erasure_journal_receipts' AND grantee='PUBLIC'")
                .query(Long.class).single()).isZero();

        var runtimeSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "ims_school_core_rt", "synthetic-runtime-only");
        var runtime = JdbcClient.create(runtimeSource);
        var runtimeTransaction = new TransactionTemplate(new JdbcTransactionManager(runtimeSource)); runtimeTransaction.setTimeout(45);
        var runtimeStudents = new StudentReadRepository(runtime, mock(StudentPhotoStorage.class),
                new OutboxWriter(runtime, new ObjectMapper(), "tenant_school"));
        String admission = "RUNTIME-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = runtimeTransaction.execute(status -> {
            runtimeScope(runtime, target.school);
            // Even a submitted JSON field does not become source incarnation input.
            return runtimeStudents.createStudent(Map.of("schoolId", target.school, "admissionNumber", admission,
                    "fullName", "Synthetic runtime student", "classId", "c1", "sectionId", target.section,
                    "erasureIncarnation", retired.toString()));
        });
        long createdId = ((Number) created.get("id")).longValue();
        assertThat(jdbc.sql("SELECT erasure_incarnation FROM student.students WHERE id=:id")
                .param("id", createdId).query(UUID.class).single()).isNotEqualTo(retired);
        assertThatThrownBy(() -> runtimeTransaction.executeWithoutResult(status -> {
            runtimeScope(runtime, target.school);
            assertThat(runtime.sql("SELECT count(*) FROM student.erasure_journal_receipts WHERE student_incarnation=:inc")
                    .param("inc", retired).query(Long.class).single()).isZero();
            runtime.sql("""
                    INSERT INTO student.students(school_id,admission_no,full_name,class_id,section_id,academic_year_id,erasure_incarnation)
                    VALUES(:school,'RETIRED-UUID','Synthetic attempt','c1',:section,'erasure-test-year',:inc)
                    """).param("school", target.school).param("section", target.section).param("inc", retired).update();
        })).rootCause().isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permission denied")
                .satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501"));
        assertThat(jdbc.sql("SELECT count(*) FROM student.students WHERE erasure_incarnation=:inc")
                .param("inc", retired).query(Long.class).single()).isZero();
        assertThat(count("student.erasure_journal_receipts", erased.student)).isEqualTo(1);
    }

    private static void runtimeScope(JdbcClient runtime, long school) {
        runtime.sql("SELECT set_config('app.bypass_rls','off',true)").query(String.class).single();
        runtime.sql("SELECT set_config('app.current_school_id',:school,true)")
                .param("school", Long.toString(school)).query(String.class).single();
    }

    private Fixture fixture(StudentErasureJournalTest.MemoryStore store) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long school = jdbc.sql("""
                INSERT INTO tenant_school.schools(name,short_code,city,state,active,configured_class_count,configured_section_count,created_at)
                VALUES('Synthetic erasure',:code,'Synthetic','Synthetic',true,3,2,now()) RETURNING id
                """).param("code", "ERASE-" + suffix).query(Long.class).single();
        new SchoolStructureReadRepository(jdbc, outbox).updateStructure(school, 3, 2);
        String section = jdbc.sql("SELECT id FROM tenant_school.school_sections WHERE school_id=:id AND school_class_id='c1' ORDER BY id LIMIT 1")
                .param("id", school).query(String.class).single();
        long student = jdbc.sql("SELECT nextval('student.seq_students')").query(Long.class).single();
        String admission = "ERASE-" + suffix;
        insertStudent(school, student, admission, section, UUID.randomUUID());
        String photo = "schools/" + school + "/students/" + student + "/photos/" + "a".repeat(64) + ".jpg";
        jdbc.sql("UPDATE student.students SET photo_url=:photo WHERE id=:id").param("photo", photo).param("id", student).update();
        StudentPhotoStorage photos = mock(StudentPhotoStorage.class);
        when(photos.cleanupTarget(org.mockito.ArgumentMatchers.eq(photo), anyString(), org.mockito.ArgumentMatchers.eq(student)))
                .thenReturn(Optional.of(new StudentPhotoStorage.CleanupTarget("synthetic-photos", photo)));
        StudentReadRepository students = new StudentReadRepository(jdbc, photos, outbox);
        students.setErasureJournal(new StudentErasureJournal(StudentErasureJournalTest.configuration(), store));
        return new Fixture(students, store, photos, school, student, admission, section);
    }

    private static void insertStudent(long school, long student, String admission, String section, UUID incarnation) {
        jdbc.sql("""
                INSERT INTO student.students(id,school_id,admission_no,full_name,class_id,section_id,academic_year_id,erasure_incarnation,created_at)
                VALUES(:id,:school,:admission,'Synthetic erasure student','c1',:section,'erasure-test-year',:incarnation,now())
                """).param("id", student).param("school", school).param("admission", admission)
                .param("section", section).param("incarnation", incarnation).update();
    }
    private static long studentCount(long id) {
        return jdbc.sql("SELECT count(*) FROM student.students WHERE id=:id").param("id", id).query(Long.class).single();
    }
    private static long count(String table, long id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE student_id=:id").param("id", id).query(Long.class).single();
    }
    private static void assertUnchanged(Fixture fixture) {
        assertThat(studentCount(fixture.student)).isEqualTo(1);
        assertThat(count("student.erasure_journal_receipts", fixture.student)).isZero();
        assertThat(count("tenant_school.photo_cleanup_outbox", fixture.student)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE event_type='student.deleted.v1' AND aggregate_id=:id")
                .param("id", Long.toString(fixture.student)).query(Long.class).single()).isZero();
        verify(fixture.photos, never()).deleteStoredPhoto(anyString());
    }
    private record Fixture(StudentReadRepository students, StudentErasureJournalTest.MemoryStore store,
                           StudentPhotoStorage photos, long school, long student, String admission, String section) {}
}
