package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Opt-in certification for the supported 10,000-student onboarding operating model.
 *
 * <p>Run explicitly with {@code -Donboarding.scale.certification=true}. It deliberately
 * executes the production repository path as twenty 500-row preview/confirm transactions;
 * it is not included in every fast unit-test run.</p>
 */
class StudentOnboardingScaleCertificationIntegrationTest {
    private static final int BATCH_SIZE = 500;
    private static final int STUDENT_COUNT = 10_000;
    private static final int BATCH_COUNT = STUDENT_COUNT / BATCH_SIZE;

    static PostgreSQLContainer<?> postgres;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    static StudentReadRepository students;
    static SchoolStructureReadRepository schools;
    static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("onboarding.scale.certification"),
                "Explicit opt-in required: -Donboarding.scale.certification=true");
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        for (String schema : new String[] {"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load()
                    .migrate();
        }

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        OutboxWriter outbox = new OutboxWriter(jdbc, objectMapper, "tenant_school");
        students = new StudentReadRepository(jdbc, mock(StudentPhotoStorage.class), outbox);
        schools = new SchoolStructureReadRepository(jdbc, outbox);
        seedClassCatalog();
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void tenThousandStudentsCompleteAsTwentyBoundedRetrySafeReconciledBatches() {
        long schoolId = seedSchool("CERT-10K", 15, 26);
        List<String> fileTokens = new ArrayList<>();
        List<String> jobIds = new ArrayList<>();
        List<Long> batchDurationsMillis = new ArrayList<>();
        long started = System.nanoTime();
        long slowestBatchMillis = 0;

        for (int batch = 0; batch < BATCH_COUNT; batch++) {
            long batchStarted = System.nanoTime();
            int firstStudent = batch * BATCH_SIZE;
            Map<String, Object> preview = inTransaction(() -> students.previewImport(Map.of(
                    "schoolId", schoolId,
                    "rows", rows("CERT10K", firstStudent, BATCH_SIZE))));
            assertThat(preview)
                    .containsEntry("validCount", BATCH_SIZE)
                    .containsEntry("errorCount", 0);

            String fileToken = String.valueOf(preview.get("fileToken"));
            Map<String, Object> confirmation = confirm(schoolId, fileToken);
            assertThat(confirmation)
                    .containsEntry("totalRows", BATCH_SIZE)
                    .containsEntry("inserted", BATCH_SIZE)
                    .containsEntry("skipped", 0)
                    .containsEntry("done", true);
            assertThat((List<?>) confirmation.get("insertedStudents")).hasSize(BATCH_SIZE);
            fileTokens.add(fileToken);
            jobIds.add(String.valueOf(confirmation.get("jobId")));
            long batchMillis = elapsedMillis(batchStarted);
            batchDurationsMillis.add(batchMillis);
            slowestBatchMillis = Math.max(slowestBatchMillis, batchMillis);
        }

        // A retry after completion must replay the durable result, not insert again.
        Map<String, Object> retry = confirm(schoolId, fileTokens.getFirst());
        assertThat(retry)
                .containsEntry("jobId", jobIds.getFirst())
                .containsEntry("inserted", BATCH_SIZE)
                .containsEntry("skipped", 0)
                .containsEntry("done", true);

        assertExactImportReconciliation(schoolId, STUDENT_COUNT, BATCH_COUNT);
        long totalMillis = elapsedMillis(started);
        List<Long> orderedBatchMillis = batchDurationsMillis.stream().sorted(Comparator.naturalOrder()).toList();
        long p95BatchMillis = orderedBatchMillis.get((int) Math.ceil(orderedBatchMillis.size() * 0.95) - 1);
        double meanBatchMillis = batchDurationsMillis.stream().mapToLong(Long::longValue).average().orElse(0);
        double studentsPerSecond = STUDENT_COUNT / (totalMillis / 1000.0);
        long databaseBytes = jdbc.sql("SELECT pg_database_size(current_database())")
                .query(Long.class).single();
        System.out.printf(
                "IMS_ONBOARDING_10K_RESULT|students=%d|batches=%d|durationMs=%d|studentsPerSecond=%.2f|meanBatchMs=%.2f|p95BatchMs=%d|slowestBatchMs=%d|databaseBytes=%d%n",
                STUDENT_COUNT, BATCH_COUNT, totalMillis, studentsPerSecond, meanBatchMillis,
                p95BatchMillis, slowestBatchMillis, databaseBytes);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void twoSchoolsConfirmConcurrentlyAndCannotCrossReadTokensOrJobs() throws Exception {
        long schoolA = seedSchool("CERT-CONCURRENT-A", 15, 26);
        long schoolB = seedSchool("CERT-CONCURRENT-B", 15, 26);
        Map<String, Object> previewA = preview(schoolA, "CCA", 0, BATCH_SIZE);
        Map<String, Object> previewB = preview(schoolB, "CCB", 0, BATCH_SIZE);
        String tokenA = String.valueOf(previewA.get("fileToken"));
        String tokenB = String.valueOf(previewB.get("fileToken"));

        assertThatThrownBy(() -> confirm(schoolB, tokenA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Preview token not found");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            long started = System.nanoTime();
            Future<Map<String, Object>> resultA = executor.submit(() -> {
                await(start);
                return confirm(schoolA, tokenA);
            });
            Future<Map<String, Object>> resultB = executor.submit(() -> {
                await(start);
                return confirm(schoolB, tokenB);
            });
            start.countDown();
            Map<String, Object> confirmedA = resultA.get(3, TimeUnit.MINUTES);
            Map<String, Object> confirmedB = resultB.get(3, TimeUnit.MINUTES);
            long durationMillis = elapsedMillis(started);

            assertThat(confirmedA).containsEntry("inserted", BATCH_SIZE).containsEntry("schoolId", schoolA);
            assertThat(confirmedB).containsEntry("inserted", BATCH_SIZE).containsEntry("schoolId", schoolB);
            assertThat(jobCountForSchool(String.valueOf(confirmedA.get("jobId")), schoolB)).isZero();
            assertThat(jobCountForSchool(String.valueOf(confirmedB.get("jobId")), schoolA)).isZero();
            assertExactImportReconciliation(schoolA, BATCH_SIZE, 1);
            assertExactImportReconciliation(schoolB, BATCH_SIZE, 1);
            System.out.printf(
                    "IMS_ONBOARDING_CONCURRENT_RESULT|schools=2|studentsPerSchool=%d|durationMs=%d%n",
                    BATCH_SIZE, durationMillis);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void simultaneousSameTokenConfirmationsReturnOneDurableResult() throws Exception {
        long schoolId = seedSchool("CERT-RETRY", 15, 26);
        Map<String, Object> preview = preview(schoolId, "RETRY", 0, 100);
        String token = String.valueOf(preview.get("fileToken"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            long started = System.nanoTime();
            Future<Map<String, Object>> first = executor.submit(() -> {
                await(start);
                return confirm(schoolId, token);
            });
            Future<Map<String, Object>> second = executor.submit(() -> {
                await(start);
                return confirm(schoolId, token);
            });
            start.countDown();
            Map<String, Object> a = first.get(2, TimeUnit.MINUTES);
            Map<String, Object> b = second.get(2, TimeUnit.MINUTES);

            assertThat(a.get("jobId")).isEqualTo(b.get("jobId"));
            assertThat(a).containsEntry("inserted", 100).containsEntry("skipped", 0);
            assertThat(b).containsEntry("inserted", 100).containsEntry("skipped", 0);
            assertExactImportReconciliation(schoolId, 100, 1);
            System.out.printf(
                    "IMS_ONBOARDING_SAME_TOKEN_RESULT|attempts=2|students=100|durationMs=%d|sameJobId=true|duplicates=0%n",
                    elapsedMillis(started));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void inMemoryExportIsChecksummedAndSchoolCoreEraseLeavesControlTenantUntouched() throws Exception {
        long targetSchool = seedSchool("CERT-ERASE-TARGET", 15, 26);
        long controlSchool = seedSchool("CERT-ERASE-CONTROL", 15, 26);
        confirm(targetSchool, String.valueOf(preview(targetSchool, "ERASE", 0, 20).get("fileToken")));
        confirm(controlSchool, String.valueOf(preview(controlSchool, "CONTROL", 0, 20).get("fileToken")));

        Map<String, Long> targetBefore = schoolCoreCounts(targetSchool);
        Map<String, Long> controlBefore = schoolCoreCounts(controlSchool);
        List<Map<String, Object>> exportRows = jdbc.sql("""
                        SELECT admission_no, full_name, class_id, section_id, academic_year_id
                        FROM student.students
                        WHERE school_id = :schoolId
                        ORDER BY admission_no
                        """)
                .param("schoolId", targetSchool)
                .query((rs, rowNumber) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("admissionNumber", rs.getString("admission_no"));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("classId", rs.getString("class_id"));
                    row.put("sectionId", rs.getString("section_id"));
                    row.put("academicYearId", rs.getString("academic_year_id"));
                    return row;
                }).list();
        byte[] exportBytes = objectMapper.writeValueAsBytes(Map.of(
                "schemaVersion", 1,
                "synthetic", true,
                "schoolId", targetSchool,
                "students", exportRows));
        String exportSha256 = sha256(exportBytes);
        assertThat(exportRows).hasSize(20);
        assertThat(exportSha256).hasSize(64);
        assertThat(new String(exportBytes, StandardCharsets.UTF_8)).doesNotContain("CONTROL-");

        inTransaction(() -> {
            eraseSchoolCoreTenant(targetSchool);
            return null;
        });

        Map<String, Long> targetAfter = schoolCoreCounts(targetSchool);
        Map<String, Long> controlAfter = schoolCoreCounts(controlSchool);
        assertThat(targetAfter.values()).allMatch(count -> count == 0L);
        assertThat(controlAfter).isEqualTo(controlBefore);
        System.out.printf(
                "IMS_ONBOARDING_ERASE_RESULT|synthetic=true|exportRows=%d|sha256=%s|targetBefore=%s|targetAfter=%s|controlBefore=%s|controlAfter=%s%n",
                exportRows.size(), exportSha256,
                objectMapper.writeValueAsString(targetBefore), objectMapper.writeValueAsString(targetAfter),
                objectMapper.writeValueAsString(controlBefore), objectMapper.writeValueAsString(controlAfter));
    }

    private static Map<String, Object> preview(long schoolId, String prefix, int first, int count) {
        return inTransaction(() -> students.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", rows(prefix, first, count))));
    }

    private static Map<String, Object> confirm(long schoolId, String token) {
        return inTransaction(() -> students.confirmImport(Map.of(
                "schoolId", schoolId,
                "fileToken", token)));
    }

    private static List<Map<String, Object>> rows(String prefix, int first, int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int index = first + offset;
            int classNumber = (index % 12) + 1;
            String section = String.valueOf((char) ('A' + ((index / 12) % 26)));
            rows.add(Map.of(
                    "__rowNumber", index + 2,
                    "Name", "Synthetic Student " + index,
                    "Class", String.valueOf(classNumber),
                    "Section", section,
                    "AdmissionNo", prefix + "-" + String.format("%05d", index),
                    "Gender", index % 2 == 0 ? "Female" : "Male",
                    "Phone", "9000000000"));
        }
        return rows;
    }

    private static void assertExactImportReconciliation(long schoolId, int expectedStudents, int expectedBatches) {
        assertThat(count("student.students", schoolId)).isEqualTo(expectedStudents);
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT admission_no)
                        FROM student.students WHERE school_id = :schoolId
                        """)
                .param("schoolId", schoolId).query(Long.class).single()).isEqualTo(expectedStudents);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM student.import_batches
                        WHERE school_id = :schoolId AND status = 'DONE' AND pct = 100
                        """)
                .param("schoolId", schoolId).query(Long.class).single()).isEqualTo(expectedBatches);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM student.import_rows
                        WHERE school_id = :schoolId AND status = 'Imported'
                          AND applied_student_id IS NOT NULL AND applied_at IS NOT NULL
                        """)
                .param("schoolId", schoolId).query(Long.class).single()).isEqualTo(expectedStudents);
        assertThat(count("student.student_enrollments", schoolId)).isEqualTo(expectedStudents);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM tenant_school.outbox_events
                        WHERE school_id = :schoolId AND event_type = 'student.upserted.v1'
                        """)
                .param("schoolId", schoolId).query(Long.class).single()).isEqualTo(expectedStudents);
    }

    private static long seedSchool(String shortCode, int classCount, int sectionCount) {
        Long schoolId = jdbc.sql("""
                        INSERT INTO tenant_school.schools
                            (name, short_code, city, state, active,
                             configured_class_count, configured_section_count, created_at)
                        VALUES (:name, :shortCode, 'Synthetic City', 'Synthetic State', true,
                                :classCount, :sectionCount, now())
                        RETURNING id
                        """)
                .param("name", "Synthetic " + shortCode)
                .param("shortCode", shortCode)
                .param("classCount", classCount)
                .param("sectionCount", sectionCount)
                .query(Long.class).single();
        inTransaction(() -> schools.updateStructure(schoolId, classCount, sectionCount));
        return schoolId;
    }

    private static void seedClassCatalog() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // V16 + V19 own the canonical 15-class catalogue. Do not add aliases:
            // duplicate display names would consume configured-class ranking slots.
            statement.execute("""
                    INSERT INTO tenant_school.academic_years (id, label, active)
                    VALUES ('2026-27', '2026-27', true)
                    ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label, active = true
                    """);
        }
    }

    private static long jobCountForSchool(String jobId, long schoolId) {
        return jdbc.sql("SELECT count(*) FROM student.import_batches WHERE job_id = :jobId AND school_id = :schoolId")
                .param("jobId", jobId)
                .param("schoolId", schoolId)
                .query(Long.class).single();
    }

    private static Map<String, Long> schoolCoreCounts(long schoolId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("students", count("student.students", schoolId));
        counts.put("enrollments", count("student.student_enrollments", schoolId));
        counts.put("importRows", count("student.import_rows", schoolId));
        counts.put("importBatches", count("student.import_batches", schoolId));
        counts.put("guardians", count("student.guardians", schoolId));
        counts.put("consents", count("student.student_consent_events", schoolId));
        counts.put("outbox", count("tenant_school.outbox_events", schoolId));
        counts.put("sections", count("tenant_school.school_sections", schoolId));
        counts.put("schools", jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId).query(Long.class).single());
        return counts;
    }

    private static long count(String qualifiedTable, long schoolId) {
        return jdbc.sql("SELECT count(*) FROM " + qualifiedTable + " WHERE school_id = :schoolId")
                .param("schoolId", schoolId).query(Long.class).single();
    }

    private static void eraseSchoolCoreTenant(long schoolId) {
        // Test-only rehearsal of the known student/tenant-school dependency order.
        // Production offboarding remains a separately approved, resumable workflow.
        for (String statement : List.of(
                "DELETE FROM student.student_consent_events WHERE school_id = :schoolId",
                "DELETE FROM student.student_guardians WHERE school_id = :schoolId",
                "DELETE FROM student.guardians WHERE school_id = :schoolId",
                "DELETE FROM student.student_review_items WHERE school_id = :schoolId",
                "DELETE FROM student.student_review_campaigns WHERE school_id = :schoolId",
                "DELETE FROM student.student_promotion_batch_items WHERE school_id = :schoolId",
                "DELETE FROM student.student_promotion_batches WHERE school_id = :schoolId",
                "DELETE FROM student.student_enrollments WHERE school_id = :schoolId",
                "DELETE FROM student.photo_import_rows WHERE school_id = :schoolId",
                "DELETE FROM student.photo_import_column_mappings WHERE school_id = :schoolId",
                "DELETE FROM student.photo_import_sources WHERE school_id = :schoolId",
                "DELETE FROM student.photo_import_batches WHERE school_id = :schoolId",
                "DELETE FROM student.photo_import_drive_folders WHERE school_id = :schoolId",
                "DELETE FROM student.import_rows WHERE school_id = :schoolId",
                "DELETE FROM student.students WHERE school_id = :schoolId",
                "DELETE FROM student.import_batches WHERE school_id = :schoolId",
                "DELETE FROM tenant_school.outbox_events WHERE school_id = :schoolId",
                "DELETE FROM tenant_school.school_module_entitlements WHERE school_id = :schoolId",
                "DELETE FROM tenant_school.school_sections WHERE school_id = :schoolId",
                "DELETE FROM tenant_school.schools WHERE id = :schoolId")) {
            jdbc.sql(statement).param("schoolId", schoolId).update();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static <T> T inTransaction(ThrowingSupplier<T> work) {
        return transaction.execute(status -> {
            try {
                return work.get();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test", ex);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
