package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.infrastructure.ApprovalCommandClient;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the SP7 (read-rewrite, phase 1) swap of the fully-unblocked reporting reads onto the
 * local {@code reporting.fact_*} tables: {@code vendorDues}, {@code reorderSignals} and the
 * school-scoped {@code commandCenterSummary} KPIs must read ONLY the reporting schema.
 *
 * <p>The database migrates ONLY {@code reporting} — the {@code catalog}/{@code fee}/
 * {@code firefighting}/{@code attendance}/{@code student}/{@code tenant_school} schemas are never
 * created — so any surviving cross-schema read would fail with "relation does not exist". Passing
 * proves these reads are decoupled.
 */
class ReportingFactReadIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingReadRepository reporting;
    private ReportingApprovalRepository approvals;

    @BeforeAll
    static void setUpContainer() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping reporting fact read-swap integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .load().migrate();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setMaximumPoolSize(2);
        dataSource = pool;
        jdbcClient = JdbcClient.create(dataSource);

        // Minimal same-service (notification schema is owned by platform-service too) supporting
        // table for the LEFT JOIN LATERAL / subquery reminder lookups exercised by feeDefaulters()
        // and lowAttendanceStudents(). No fee/student/tenant_school/attendance/catalog schema is
        // ever created here, proving those five reads no longer depend on them.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS notification");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS notification.notification_logs (
                        id VARCHAR(36) PRIMARY KEY,
                        school_id BIGINT,
                        student_id BIGINT,
                        notification_type VARCHAR(80) NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        sent_at TIMESTAMPTZ
                    )
                    """);
        }
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        reporting = new ReportingReadRepository(jdbcClient);
        approvals = new ReportingApprovalRepository(jdbcClient, org.mockito.Mockito.mock(ApprovalCommandClient.class));
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String t : List.of("fact_catalog_order", "fact_firefighting_request", "fact_payment",
                    "fact_fee_assignment", "fact_attendance_daily", "fact_student_review_item",
                    "command_center_actions", "dim_student", "dim_section", "dim_academic_year",
                    "event_student_contributions", "academic_events")) {
                st.execute("TRUNCATE reporting." + t + " CASCADE");
            }
            st.execute("TRUNCATE notification.notification_logs");
        }
    }

    private void seedActiveYear(String id) {
        jdbcClient.sql("""
                INSERT INTO reporting.dim_academic_year (id, label, active, updated_at)
                VALUES (:id, :id, true, now())
                """).param("id", id).update();
    }

    private void seedSection(String id, long schoolId, String className, String sectionName) {
        jdbcClient.sql("""
                INSERT INTO reporting.dim_section (id, name, school_id, class_id, class_name, active, updated_at)
                VALUES (:id, :name, :schoolId, :classId, :className, true, now())
                """)
                .param("id", id).param("name", sectionName).param("schoolId", schoolId)
                .param("classId", "class-" + id).param("className", className)
                .update();
    }

    private void seedStudent(long id, long schoolId, String sectionId, String fullName, String admissionNo,
                              String fatherName, String parentContact, Double attendancePercent) {
        jdbcClient.sql("""
                INSERT INTO reporting.dim_student
                    (id, school_id, admission_no, full_name, section_id, class_id, parent_contact, phone,
                     father_name, attendance_percent, active, updated_at)
                VALUES (:id, :schoolId, :admissionNo, :fullName, :sectionId, :classId, :parentContact, :parentContact,
                        :fatherName, :attendancePercent, true, now())
                """)
                .param("id", id).param("schoolId", schoolId).param("admissionNo", admissionNo)
                .param("fullName", fullName).param("sectionId", sectionId).param("classId", "class-" + sectionId)
                .param("parentContact", parentContact).param("fatherName", fatherName)
                .param("attendancePercent", attendancePercent)
                .update();
    }

    private void seedFeeAssignment(String id, long studentId, long schoolId, String yearId,
                                    long netPayable, long paidAmount, OffsetDateTime assignedAt) {
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment
                    (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, assigned_at, updated_at)
                VALUES (:id, :studentId, :schoolId, :yearId, :net, :paid, :net - :paid, 'PARTIAL', :assignedAt, now())
                """)
                .param("id", id).param("studentId", studentId).param("schoolId", schoolId).param("yearId", yearId)
                .param("net", netPayable).param("paid", paidAmount).param("assignedAt", assignedAt)
                .update();
    }

    private void seedCatalogOrder(String id, long schoolId, String category, String status, long totalPaise) {
        jdbcClient.sql("""
                INSERT INTO reporting.fact_catalog_order (id, school_id, category, status, total_amount, created_at, updated_at)
                VALUES (:id, :s, :cat, :st, :amt, now(), now())
                """).param("id", id).param("s", schoolId).param("cat", category).param("st", status).param("amt", totalPaise).update();
    }

    private void seedFirefighting(String code, long schoolId, String status, Long winnerAmount) {
        jdbcClient.sql("""
                INSERT INTO reporting.fact_firefighting_request (code, title, category, status, estimated_budget, school_id, winner_amount, created_at, updated_at)
                VALUES (:c, :c, 'PLUMBING', :st, 9000, :s, :wa, now(), now())
                """).param("c", code).param("st", status).param("s", schoolId).param("wa", winnerAmount).update();
    }

    @Test
    void vendorDues_readsFactsOnly() {
        seedCatalogOrder("ord-1", 100L, "BOOKS", "APPROVED", 5000L);   // unpaid → a due
        seedCatalogOrder("ord-2", 100L, "BOOKS", "PROCESSING", 999L);  // not APPROVED/FULFILLED → excluded
        seedFirefighting("ff-1", 100L, "APPROVED", 8000L);             // unpaid winner → a due
        seedFirefighting("ff-2", 100L, "AWAITING_BURSAR", null);       // not APPROVED / no winner → excluded

        Map<String, Object> dues = reporting.vendorDues(100L);
        assertEquals(1L, dues.get("catalogOrderCount"));
        assertEquals(5000L, dues.get("catalogOrderTotalPaise"));
        assertEquals(1L, dues.get("firefightingCount"));
        assertEquals(8000L, dues.get("firefightingTotalPaise"));
        assertEquals(13000L, dues.get("totalDuesPaise"));
        assertEquals(2, ((List<?>) dues.get("items")).size());
    }

    @Test
    void reorderSignals_readsFactCatalogOnly() {
        seedCatalogOrder("ord-1", 100L, "BOOKS", "APPROVED", 5000L);
        Map<String, Object> signals = reporting.reorderSignals(100L);
        // one category present; the method must run purely off reporting.fact_catalog_order
        assertEquals(1, ((List<?>) signals.get("items")).size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_schoolScope_kpisReadFactsOnly() {
        seedActiveYear("ay_2025_26");
        seedCatalogOrder("ord-1", 100L, "BOOKS", "PROCESSING", 4000L);            // 1 active order
        seedFirefighting("ff-1", 100L, "APPROVED", 8000L);                        // open (non-FULFILLED)
        seedFirefighting("ff-2", 100L, "AWAITING_BURSAR", null);                  // open + pending approval
        jdbcClient.sql("""
                INSERT INTO reporting.fact_payment (id, assignment_id, school_id, student_id, amount, paid_at, updated_at)
                VALUES ('pay-1', 'fa-1', 100, 1, 12000, now(), now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, updated_at)
                VALUES ('fa-1', 1, 100, 'ay_2025_26', 10000, 3000, 7000, 'PARTIAL', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-1', 100, CURRENT_DATE, 'ay_2025_26', 5, 10, now())
                """).update();

        Map<String, Object> summary = reporting.commandCenterSummary(100L, false);
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        assertEquals("2", kpiValue(kpis, "open_firefighting"));      // both ff rows are non-FULFILLED
        assertEquals("1", kpiValue(kpis, "orders_in_progress"));     // ord-1 PROCESSING
        assertEquals("1 sections", kpiValue(kpis, "attendance_today"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_attendanceTodayKpi_isScopedToRequestingSchool() {
        // School 100 has one attendance row today; school 200 (a different school) also has one.
        // The attendance_today KPI for school 100 must count ONLY school 100's sections, not both.
        seedActiveYear("ay_2025_26");
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-1', 100, CURRENT_DATE, 'ay_2025_26', 5, 10, now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-2', 200, CURRENT_DATE, 'ay_2025_26', 8, 12, now())
                """).update();

        Map<String, Object> summary = reporting.commandCenterSummary(100L, false);
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        assertEquals("1 sections", kpiValue(kpis, "attendance_today"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_usesResolvedActiveYear_notHardcodedLiteral() {
        // Active year is 'ay_2026_27' (NOT the historically hardcoded 'ay_2025_26'). Fee/attendance
        // rows tagged with the active year must count; a row tagged with a different year must not,
        // proving the query binds the resolved active year rather than a literal.
        seedActiveYear("ay_2026_27");
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, updated_at)
                VALUES ('fa-cur', 1, 100, 'ay_2026_27', 10000, 3000, 7000, 'PARTIAL', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, updated_at)
                VALUES ('fa-old', 2, 100, 'ay_2025_26', 10000, 2000, 8000, 'PARTIAL', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-cur', 100, CURRENT_DATE, 'ay_2026_27', 5, 10, now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-old', 100, CURRENT_DATE, 'ay_2025_26', 5, 10, now())
                """).update();

        Map<String, Object> summary = reporting.commandCenterSummary(100L, false);
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        assertEquals("1 sections", kpiValue(kpis, "attendance_today"));
        assertEquals("1 students overdue", kpiDelta(kpis, "fees_collected"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_noActiveYear_kpisAreZeroWithoutError() {
        // No reporting.dim_academic_year row at all (no active year resolvable). The overdue-count
        // and attendance-sections KPIs must degrade to zero rather than throwing on a null bind.
        jdbcClient.sql("""
                INSERT INTO reporting.fact_fee_assignment (id, student_id, school_id, academic_year_id, net_payable, paid_amount, due_amount, status, updated_at)
                VALUES ('fa-1', 1, 100, 'ay_2025_26', 10000, 3000, 7000, 'PARTIAL', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily (id, school_id, attendance_date, academic_year_id, present_count, total_enrolled, updated_at)
                VALUES ('ad-1', 100, CURRENT_DATE, 'ay_2025_26', 5, 10, now())
                """).update();

        Map<String, Object> summary = reporting.commandCenterSummary(100L, false);
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        assertEquals("0 sections", kpiValue(kpis, "attendance_today"));
        assertEquals("0 students overdue", kpiDelta(kpis, "fees_collected"));
    }

    private static String kpiValue(List<Map<String, Object>> kpis, String key) {
        return (String) kpis.stream().filter(k -> key.equals(k.get("key"))).findFirst().orElseThrow().get("value");
    }

    private static String kpiDelta(List<Map<String, Object>> kpis, String key) {
        return (String) kpis.stream().filter(k -> key.equals(k.get("key"))).findFirst().orElseThrow().get("delta");
    }

    // ---- Phase 2 read-swap coverage: feeDefaulters / lowAttendanceSections / lowAttendanceStudents /
    // classPhotographyPaymentStatus / catalogApprovals now read exclusively off reporting.dim_*/fact_*. ----

    @Test
    void feeDefaulters_returnsOverdueRowWithDaysOverdueDerivedFromAssignedAt() {
        seedActiveYear("ay_2025_26");
        seedSection("sec-1", 100L, "Grade 5", "A");
        seedStudent(1L, 100L, "sec-1", "Asha Rao", "ADM-1", "Ramesh Rao", "9990001111", 90.0);
        OffsetDateTime assignedAt = OffsetDateTime.now().minusDays(10);
        seedFeeAssignment("fa-1", 1L, 100L, "ay_2025_26", 10000L, 4000L, assignedAt);

        Map<String, Object> result = reporting.feeDefaulters(100L, null, null, null, null, 0, 20);

        assertEquals(1L, result.get("totalDefaulters"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("Asha Rao", item.get("studentName"));
        assertEquals("ADM-1", item.get("admissionNo"));
        assertEquals("Grade 5", item.get("className"));
        assertEquals("A", item.get("sectionName"));
        assertEquals("Ramesh Rao", item.get("parentName"));
        assertEquals("9990001111", item.get("parentPhone"));
        assertEquals(6000L, item.get("dueAmount"));
        assertEquals(10, item.get("daysOverdue"));
    }

    @Test
    void lowAttendanceStudents_filtersByAttendancePercentBelow75() {
        seedSection("sec-2", 200L, "Grade 6", "B");
        seedStudent(10L, 200L, "sec-2", "Below Threshold", "ADM-10", "Father A", "9990002222", 60.0);
        seedStudent(11L, 200L, "sec-2", "Above Threshold", "ADM-11", "Father B", "9990003333", 95.0);

        List<Map<String, Object>> result = reporting.lowAttendanceStudents(200L, "sec-2");

        assertEquals(1, result.size());
        assertEquals("Below Threshold", result.get(0).get("studentName"));
        assertEquals("Father A", result.get(0).get("fatherName"));
        assertEquals("9990002222", result.get(0).get("fatherContact"));
        assertEquals("Grade 6", result.get(0).get("className"));
        assertEquals("B", result.get(0).get("sectionName"));
    }

    @Test
    void lowAttendanceSections_readsFactAttendanceAndDimSectionOnly() {
        seedActiveYear("ay_2025_26");
        seedSection("sec-3", 300L, "Grade 7", "C");
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily
                    (id, school_id, section_id, academic_year_id, attendance_date, present_count, total_enrolled, updated_at)
                VALUES ('ad-3', 300, 'sec-3', 'ay_2025_26', CURRENT_DATE, 5, 10, now())
                """).update();

        Map<String, Object> result = reporting.lowAttendanceSections(300L, LocalDate.now());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) result.get("sections");
        assertEquals(1, sections.size());
        assertEquals("sec-3", sections.get(0).get("sectionId"));
        assertEquals("C", sections.get(0).get("sectionName"));
        assertEquals("Grade 7", sections.get(0).get("className"));
    }

    @Test
    void classPhotographyPaymentStatus_readsDimStudentAndDimSectionOnly() {
        seedSection("sec-4", 400L, "Grade 8", "D");
        seedStudent(20L, 400L, "sec-4", "Photo Student", "ADM-20", "Father C", "9990004444", 88.0);
        jdbcClient.sql("""
                INSERT INTO reporting.academic_events
                    (id, school_id, title, event_type, status, total_budget, school_contribution,
                     student_contribution_target, created_at, updated_at)
                VALUES ('evt-1', 400, 'Class Photo Day', 'CLASS_PHOTOGRAPHY', 'ACTIVE', 50000, 20000, 30000, now(), now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.event_student_contributions
                    (id, event_id, student_id, school_id, expected_amount, paid_amount, status, created_at, updated_at)
                VALUES ('esc-1', 'evt-1', 20, 400, 1000, 500, 'PARTIAL', now(), now())
                """).update();

        Map<String, Object> result = reporting.classPhotographyPaymentStatus(400L, null, null, null, 0, 20);

        assertEquals("evt-1", result.get("eventId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertEquals(1, students.size());
        assertEquals("Photo Student", students.get(0).get("studentName"));
        assertEquals("Grade 8", students.get(0).get("className"));
        assertEquals("D", students.get(0).get("sectionName"));
        assertEquals("9990004444", students.get(0).get("parentPhone"));
    }

    @Test
    void catalogApprovals_returnsPendingOrderIncludingNotes() {
        jdbcClient.sql("""
                INSERT INTO reporting.dim_school (id, name, active, updated_at)
                VALUES (500, 'Greenwood High', true, now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_catalog_order
                    (id, school_id, category, status, total_amount, superadmin_approval_status, notes, created_at, updated_at)
                VALUES ('ord-approval-1', 500, 'UNIFORMS', 'PROCESSING', 12000, 'PENDING', 'Please expedite', now(), now())
                """).update();

        List<Map<String, Object>> result = approvals.approvals(50);

        assertEquals(1, result.size());
        Map<String, Object> item = result.get(0);
        assertEquals("catalog:ord-approval-1", item.get("id"));
        assertEquals("CATALOG", item.get("sourceType"));
        assertEquals("Greenwood High", item.get("schoolName"));
        assertEquals("Please expedite", item.get("notes"));
        assertEquals(12000L, item.get("amount"));
    }

    // ---- SP7 (student-review) coverage: dashboardCommandCenter now reads exclusively off
    // reporting.fact_*/dim_* — no fee/student/attendance/tenant_school schema is ever created in
    // this test class, proving the fee-defaulter, low-attendance and pending-review reads are
    // fully decoupled. ----

    @SuppressWarnings("unchecked")
    @Test
    void dashboardCommandCenter_readsFactsOnly() {
        seedActiveYear("ay_2025_26");
        seedFeeAssignment("fa-1", 1L, 600L, "ay_2025_26", 10000L, 4000L, OffsetDateTime.now().minusDays(5));
        jdbcClient.sql("""
                INSERT INTO reporting.fact_attendance_daily
                    (id, school_id, section_id, academic_year_id, attendance_date, present_count, total_enrolled, updated_at)
                VALUES ('ad-dash-1', 600, 'sec-dash-1', 'ay_2025_26', CURRENT_DATE, 5, 10, now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_student_review_item (id, school_id, campaign_id, status, updated_at)
                VALUES ('item-dash-1', 600, 'campaign-dash-1', 'PENDING', now())
                """).update();
        jdbcClient.sql("""
                INSERT INTO reporting.fact_student_review_item (id, school_id, campaign_id, status, updated_at)
                VALUES ('item-dash-2', 600, 'campaign-dash-1', 'COMPLETED', now())
                """).update();

        Map<String, Object> result = reporting.dashboardCommandCenter(600L);

        Map<String, Object> fees = (Map<String, Object>) result.get("fees");
        assertEquals(1L, fees.get("defaulterCount"));
        assertEquals(6000L, fees.get("totalOverdueAmountPaise"));
        assertEquals(5, fees.get("oldestDueDays"));

        Map<String, Object> attendance = (Map<String, Object>) result.get("attendance");
        assertEquals(1, attendance.get("sectionsBelowThresholdCount"));

        Map<String, Object> lifecycle = (Map<String, Object>) result.get("lifecycle");
        assertEquals(1, lifecycle.get("pendingReviewCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void pendingReviewCount_excludesCompletedCampaigns() {
        seedActiveYear("ay_2025_26");
        // Two PENDING items: one in an ACTIVE campaign, one in a COMPLETED campaign.
        jdbcClient.sql("""
                INSERT INTO reporting.fact_student_review_item (id, school_id, campaign_id, status, campaign_status, updated_at)
                VALUES ('r1', :s, 'c-active', 'PENDING', 'ACTIVE', now()),
                       ('r2', :s, 'c-done', 'PENDING', 'COMPLETED', now())
                """).param("s", 700L).update();

        Map<String, Object> result = reporting.dashboardCommandCenter(700L);

        Map<String, Object> lifecycle = (Map<String, Object>) result.get("lifecycle");
        assertEquals(1, lifecycle.get("pendingReviewCount"));
    }
}
